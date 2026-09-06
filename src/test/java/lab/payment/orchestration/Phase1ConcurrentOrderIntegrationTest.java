package lab.payment.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class Phase1ConcurrentOrderIntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE payment_orchestration.payments, payment_orchestration.orders, payment_orchestration.products RESTART IDENTITY CASCADE");
    }

    @Test
    void concurrentOrdersForSameProductAreSerializedAcrossSlowPgCalls() throws Exception {
        setStock("same-product", 2);

        List<TimedResponse> responses = concurrentlyOrderTwice("same-product", "DELAY_3S");

        assertThat(responses).allSatisfy(result -> assertThat(result.response().getStatusCode()).isEqualTo(HttpStatus.CREATED));
        assertThat(responses.stream().mapToLong(TimedResponse::elapsedMillis).max().orElseThrow())
                .as("one request waits for the row lock, then performs its own 3-second PG call")
                .isGreaterThanOrEqualTo(5_500);
        assertThat(stock("same-product")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_orchestration.payments WHERE status = 'SUCCESS'", Integer.class)).isEqualTo(2);
    }

    @Test
    void concurrentOrdersNeverMakeStockNegative() throws Exception {
        setStock("last-item", 1);

        List<TimedResponse> responses = concurrentlyOrderTwice("last-item", "DELAY_3S");

        assertThat(responses).extracting(result -> result.response().getStatusCode())
                .containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.CONFLICT);
        assertThat(stock("last-item")).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_orchestration.orders", Integer.class)).isEqualTo(1);
    }

    @Test
    void timeoutIsPersistedAsUnknownRatherThanFailed() {
        setStock("timeout-item", 1);

        ResponseEntity<String> response = order("timeout-item", "TIMEOUT");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(jdbc.queryForObject("SELECT status FROM payment_orchestration.payments", String.class)).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_orchestration.payments WHERE status = 'SUCCESS'", Integer.class)).isZero();
    }

    @Test
    void normalResponseApprovesPayment() {
        setStock("normal-item", 1);

        ResponseEntity<String> response = order("normal-item", "NORMAL");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(jdbc.queryForObject("SELECT status FROM payment_orchestration.payments", String.class)).isEqualTo("SUCCESS");
    }

    @Test
    void http500FromGatewayRollsBackTheWholeTransaction() {
        setStock("gateway-error-item", 1);

        ResponseEntity<String> response = order("gateway-error-item", "ERROR_500");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(stock("gateway-error-item")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM payment_orchestration.orders", Integer.class)).isZero();
    }

    private List<TimedResponse> concurrentlyOrderTwice(String sku, String mode) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<TimedResponse>> futures = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    long started = System.nanoTime();
                    ResponseEntity<String> response = order(sku, mode);
                    return new TimedResponse(response, Duration.ofNanos(System.nanoTime() - started).toMillis());
                }));
            }
            ready.await();
            start.countDown();
            List<TimedResponse> results = new ArrayList<>();
            for (Future<TimedResponse> future : futures) {
                results.add(future.get());
            }
            return results;
        }
    }

    private ResponseEntity<String> order(String sku, String mode) {
        return http.postForEntity("/api/orders", new OrderRequest(sku, 1, 1_000, mode), String.class);
    }

    private void setStock(String sku, int stock) {
        http.exchange("/api/lab/stock", HttpMethod.PUT, new HttpEntity<>(new StockRequest(sku, stock)), Void.class);
    }

    private int stock(String sku) {
        return jdbc.queryForObject("SELECT stock FROM payment_orchestration.products WHERE sku = ?", Integer.class, sku);
    }

    record OrderRequest(String sku, int quantity, long amount, String gatewayMode) {}
    record StockRequest(String sku, int stock) {}
    record TimedResponse(ResponseEntity<String> response, long elapsedMillis) {}
}
