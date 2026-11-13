package in.railops.reservation.application;

import in.railops.reservation.adapter.out.http.PaymentClient;
import in.railops.reservation.adapter.out.http.PaymentClient.PaymentIntent;
import in.railops.reservation.domain.error.DomainException;
import in.railops.reservation.domain.error.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Phase 5: the demo has no real payment gateway, and its simulated checkout page didn't exist outside local tests,
 * so a passenger on the live demo could book but never pay. When {@code railops.payments.simulated=true} (set only
 * where payment-service runs the "sim" profile), the booking's owner can finish the simulated checkout here.
 *
 * <p>Ownership, status and hold are checked by {@link PaymentIntentService#create} (404 for someone else's PNR). The
 * booking is then confirmed exactly as with a real gateway: webhook → payment-service → event → reservation.
 * No money exists anywhere in this path.
 */
@Service
public class SimulatedPaymentService {

    private final PaymentIntentService intents;
    private final PaymentClient payments;
    private final boolean enabled;

    public SimulatedPaymentService(PaymentIntentService intents, PaymentClient payments,
                                   @Value("${railops.payments.simulated:false}") boolean enabled) {
        this.intents = intents;
        this.payments = payments;
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }

    public PaymentIntent pay(java.util.UUID userId, String pnr, boolean success) {
        if (!enabled) {
            throw new DomainException(ErrorCode.RES_SIMULATED_PAYMENTS_OFF, "simulated payments are off on this server");
        }
        PaymentIntent intent = intents.create(userId, pnr);
        payments.simulateCheckout(intent.paymentId(), success);
        return intent;
    }
}
