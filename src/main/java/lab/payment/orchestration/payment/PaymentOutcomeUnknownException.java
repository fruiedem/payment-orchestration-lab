package lab.payment.orchestration.payment;

public class PaymentOutcomeUnknownException extends RuntimeException {
    public PaymentOutcomeUnknownException(String message) {
        super(message);
    }
}
