package in.railops.reservation.it;

import in.railops.reservation.adapter.out.http.PaymentClient;
import in.railops.reservation.application.BookingCommand;
import in.railops.reservation.domain.model.PassengerSpec;
import in.railops.reservation.application.BookingService;
import in.railops.reservation.application.PaymentIntentService;
import in.railops.reservation.application.SimulatedPaymentService;
import in.railops.reservation.domain.error.DomainException;
import in.railops.reservation.domain.error.ErrorCode;
import in.railops.reservation.domain.model.Quota;
import in.railops.reservation.domain.model.TravelClass;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Phase 5: the booking's owner can finish the simulated checkout; nobody else can; off unless configured. */
// The switch is passed to the constructor rather than set as a test property, so this class reuses a cached context.
class SimulatedPaymentIT extends AbstractReservationIT {

    @Autowired BookingService bookings;
    @Autowired PaymentIntentService intents;
    @MockitoBean PaymentClient payments;   // same overrides as HoldSweeperIT: shares its Spring context (DB connections)
    SimulatedPaymentService simulated;

    @org.junit.jupiter.api.BeforeEach
    void switchedOn() {
        simulated = new SimulatedPaymentService(intents, payments, true);
    }

    final UUID owner = UUID.randomUUID();

    String hold() {
        return bookings.create(owner, UUID.randomUUID().toString(), new BookingCommand(1, 0, 4, TravelClass.THIRD_AC, Quota.GN,
                List.of(new PassengerSpec(1, "Demo Passenger", 30, 'F', null, true)), false, "9820012345", null)).pnr();
    }

    UUID intentFor(String pnr) {
        BigDecimal fare = jdbc.sql("SELECT total_fare FROM reservation.booking WHERE pnr = :p").param("p", pnr).query(BigDecimal.class).single();
        UUID paymentId = UUID.randomUUID();
        when(payments.create(any())).thenReturn(new PaymentClient.PaymentIntent(paymentId, "https://pay.example/checkout/o", fare, "PENDING"));
        return paymentId;
    }

    @Test
    void the_owner_finishes_the_simulated_checkout_for_their_own_unpaid_booking() {
        String pnr = hold();
        UUID paymentId = intentFor(pnr);

        simulated.pay(owner, pnr, true);

        verify(payments).simulateCheckout(paymentId, true);
        assertThat(jdbc.sql("SELECT payment_id FROM reservation.booking WHERE pnr = :p").param("p", pnr).query(UUID.class).single())
                .as("the payment is bound to the booking, so its capture confirms it").isEqualTo(paymentId);
    }

    @Test
    void someone_elses_pnr_looks_like_no_booking_and_nothing_is_paid() {
        String pnr = hold();
        intentFor(pnr);
        assertThatThrownBy(() -> simulated.pay(UUID.randomUUID(), pnr, true))
                .isInstanceOf(DomainException.class).extracting(e -> ((DomainException) e).code()).isEqualTo(ErrorCode.RES_BOOKING_NOT_FOUND);
        verify(payments, never()).simulateCheckout(any(), anyBoolean());
    }

    @Test
    void an_expired_hold_cannot_be_paid() {
        String pnr = hold();
        intentFor(pnr);
        jdbc.sql("UPDATE reservation.booking SET hold_expires_at = now() - interval '1 second' WHERE pnr = :p").param("p", pnr).update();
        assertThatThrownBy(() -> simulated.pay(owner, pnr, true))
                .isInstanceOf(DomainException.class).extracting(e -> ((DomainException) e).code()).isEqualTo(ErrorCode.RES_HOLD_EXPIRED);
        verify(payments, never()).simulateCheckout(any(), anyBoolean());
    }

    @Test
    void off_by_default_so_a_real_gateway_deployment_never_exposes_it() {
        PaymentClient client = mock(PaymentClient.class);
        var off = new SimulatedPaymentService(mock(PaymentIntentService.class), client, false);
        assertThatThrownBy(() -> off.pay(owner, "1234567890", true))
                .isInstanceOf(DomainException.class).extracting(e -> ((DomainException) e).code()).isEqualTo(ErrorCode.RES_SIMULATED_PAYMENTS_OFF);
        verify(client, never()).simulateCheckout(any(), anyBoolean());
    }
}
