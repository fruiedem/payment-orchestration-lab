package lab.payment.orchestration.order;

import java.util.Map;

import lab.payment.orchestration.order.OrderService.OrderResult;
import lab.payment.orchestration.payment.FakePaymentGateway.Mode;
import lab.payment.orchestration.payment.PaymentGatewayException;
import lab.payment.orchestration.payment.PaymentOutcomeUnknownException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class OrderController {
    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping("/orders")
    ResponseEntity<OrderResult> placeOrder(@RequestBody PlaceOrderRequest request) {
        OrderResult result = orderService.placeOrder(
                request.sku(), request.quantity(), request.amount(), request.gatewayMode());
        HttpStatus status = result.status().equals("UNKNOWN") ? HttpStatus.ACCEPTED : HttpStatus.CREATED;
        return ResponseEntity.status(status).body(result);
    }

    @PutMapping("/lab/stock")
    void setStock(@RequestBody SetStockRequest request) {
        orderService.setStock(request.sku(), request.stock());
    }

    @ExceptionHandler(InsufficientStockException.class)
    ResponseEntity<Map<String, String>> insufficientStock(InsufficientStockException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("code", "INSUFFICIENT_STOCK", "message", exception.getMessage()));
    }

    @ExceptionHandler(PaymentOutcomeUnknownException.class)
    ResponseEntity<Map<String, String>> unknown(PaymentOutcomeUnknownException exception) {
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(Map.of("code", "PAYMENT_OUTCOME_UNKNOWN", "message", exception.getMessage()));
    }

    @ExceptionHandler(PaymentGatewayException.class)
    ResponseEntity<Map<String, String>> gatewayError(PaymentGatewayException exception) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("code", "PAYMENT_GATEWAY_ERROR", "message", exception.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> invalid(IllegalArgumentException exception) {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_REQUEST", "message", exception.getMessage()));
    }

    public record PlaceOrderRequest(String sku, int quantity, long amount, Mode gatewayMode) {
    }

    public record SetStockRequest(String sku, int stock) {
    }
}
