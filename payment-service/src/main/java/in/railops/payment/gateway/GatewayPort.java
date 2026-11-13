package in.railops.payment.gateway;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * What payment-service needs from a payment gateway. Every mutating call carries our own id as the
 * gateway-side idempotency key, so a retry after a timeout ("did the order get created?") is always safe.
 */
public interface GatewayPort {

    String name();

    record Order(String orderId, String checkoutUrl) {
    }

    record OrderStatus(String orderId, String status, BigDecimal capturedAmount, String gatewayPaymentId) {
    }

    record RefundAck(String gatewayRefundId) {
    }

    /**
     * Creates a gateway order. Do NOT assume the gateway dedupes on {@code paymentId}: many (e.g. order APIs whose
     * "receipt" is informational) create a new order per call. PaymentService therefore calls this at most once per
     * payment and reuses the stored order id afterwards; see PaymentService#createOrGet. {@code pnr} only shapes the
     * customer-facing page (where to return after paying); it is never trusted on the way back.
     */
    Order createOrder(UUID paymentId, BigDecimal amount, String currency, String pnr);

    /** Checkout location for an order created earlier: lets a retry hand out the SAME order, never a new one. */
    String checkoutUrl(String orderId);

    OrderStatus fetchOrder(String orderId);

    RefundAck refund(String orderId, UUID refundId, BigDecimal amount);
}
