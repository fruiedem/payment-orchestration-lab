package lab.payment.orchestration.order;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lab.payment.orchestration.payment.FakePaymentGateway;
import lab.payment.orchestration.payment.FakePaymentGateway.Approval;
import lab.payment.orchestration.payment.FakePaymentGateway.Mode;
import lab.payment.orchestration.payment.PaymentOutcomeUnknownException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OrderService {
    private final JdbcClient jdbc;
    private final FakePaymentGateway paymentGateway;
    private final TransactionTemplate transactionTemplate;
    private final Timer transactionDuration;

    public OrderService(DataSource dataSource, FakePaymentGateway paymentGateway,
                        TransactionTemplate transactionTemplate, MeterRegistry meterRegistry) {
        this.jdbc = JdbcClient.create(dataSource);
        this.paymentGateway = paymentGateway;
        this.transactionTemplate = transactionTemplate;
        this.transactionDuration = Timer.builder("payment.transaction.duration")
                .description("Duration of the deliberately broad order/payment DB transaction")
                .publishPercentileHistogram()
                .register(meterRegistry);
    }

    public OrderResult placeOrder(String sku, int quantity, long amount, Mode mode) {
        long started = System.nanoTime();
        try {
            return transactionTemplate.execute(status -> placeOrderInsideTransaction(sku, quantity, amount, mode));
        } finally {
            transactionDuration.record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    private OrderResult placeOrderInsideTransaction(String sku, int quantity, long amount, Mode mode) {
        if (quantity <= 0 || amount <= 0) {
            throw new IllegalArgumentException("quantity and amount must be positive");
        }

        Map<String, Object> product = jdbc.sql("""
                SELECT id, stock
                FROM payment_orchestration.products
                WHERE sku = :sku
                FOR UPDATE
                """).param("sku", sku).query().singleRow();
        long productId = ((Number) product.get("id")).longValue();
        int stock = ((Number) product.get("stock")).intValue();
        if (stock < quantity) {
            throw new InsufficientStockException("insufficient stock for " + sku);
        }

        jdbc.sql("UPDATE payment_orchestration.products SET stock = stock - :quantity WHERE id = :id")
                .param("quantity", quantity).param("id", productId).update();

        long orderId = jdbc.sql("""
                INSERT INTO payment_orchestration.orders(product_id, quantity, amount, status)
                VALUES (:productId, :quantity, :amount, 'PENDING')
                RETURNING id
                """).param("productId", productId).param("quantity", quantity).param("amount", amount)
                .query(Long.class).single();

        long paymentId = jdbc.sql("""
                INSERT INTO payment_orchestration.payments(order_id, status)
                VALUES (:orderId, 'PENDING')
                RETURNING id
                """).param("orderId", orderId).query(Long.class).single();

        // Deliberate Phase 1 flaw: the row lock and DB connection remain held during this call.
        Approval approval;
        try {
            approval = paymentGateway.approve(orderId, amount, mode);
        } catch (PaymentOutcomeUnknownException exception) {
            jdbc.sql("UPDATE payment_orchestration.payments SET status = 'UNKNOWN' WHERE id = :paymentId")
                    .param("paymentId", paymentId).update();
            return new OrderResult(orderId, paymentId, "UNKNOWN", null);
        }

        jdbc.sql("""
                UPDATE payment_orchestration.payments
                SET status = 'SUCCESS', pg_transaction_id = :pgTransactionId
                WHERE id = :paymentId
                """).param("pgTransactionId", approval.pgTransactionId()).param("paymentId", paymentId).update();
        jdbc.sql("UPDATE payment_orchestration.orders SET status = 'PAID' WHERE id = :orderId")
                .param("orderId", orderId).update();
        return new OrderResult(orderId, paymentId, "PAID", approval.pgTransactionId());
    }

    public void setStock(String sku, int stock) {
        if (stock < 0) {
            throw new IllegalArgumentException("stock must not be negative");
        }
        jdbc.sql("""
                INSERT INTO payment_orchestration.products(sku, stock)
                VALUES (:sku, :stock)
                ON CONFLICT (sku) DO UPDATE SET stock = EXCLUDED.stock
                """).param("sku", sku).param("stock", stock).update();
    }

    public record OrderResult(long orderId, long paymentId, String status, String pgTransactionId) {
    }
}
