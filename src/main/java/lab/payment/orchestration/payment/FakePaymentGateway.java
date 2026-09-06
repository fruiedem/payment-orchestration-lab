package lab.payment.orchestration.payment;

import java.util.UUID;

import org.springframework.stereotype.Component;

@Component
public class FakePaymentGateway {

    public Approval approve(long orderId, long amount, Mode mode) {
        switch (mode) {
            case NORMAL -> sleep(0);
            case DELAY_3S -> sleep(3_000);
            case DELAY_10S -> sleep(10_000);
            case TIMEOUT -> {
                sleep(1_000);
                throw new PaymentOutcomeUnknownException("PG request timed out; outcome is unknown");
            }
            case ERROR_500 -> throw new PaymentGatewayException("PG returned HTTP 500");
        }
        return new Approval("fake-pg-" + UUID.randomUUID());
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PaymentGatewayException("PG call interrupted", exception);
        }
    }

    public enum Mode {
        NORMAL,
        DELAY_3S,
        DELAY_10S,
        TIMEOUT,
        ERROR_500
    }

    public record Approval(String pgTransactionId) {
    }
}
