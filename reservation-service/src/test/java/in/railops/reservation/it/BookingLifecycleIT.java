package in.railops.reservation.it;

import in.railops.reservation.application.*;
import in.railops.reservation.contracts.PaymentEvents.PaymentCaptured;
import in.railops.reservation.domain.model.PassengerSpec;
import in.railops.reservation.domain.model.Quota;
import in.railops.reservation.domain.model.TravelClass;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Hold -> pay -> confirm -> cancel -> promote -> expire, through the real services and SQL. MONEY paths flagged. */
class BookingLifecycleIT extends AbstractReservationIT {

    @Autowired BookingService bookings;
    @Autowired PaymentConfirmationService payments;
    @Autowired CancellationService cancellations;
    @Autowired PromotionService promotions;
    @Autowired HoldReleaser holds;

    record Booked(UUID user, String pnr, UUID paymentId, BigDecimal fare) {
    }

    Booked book(String name) {
        UUID user = UUID.randomUUID();
        BookingView v = bookings.create(user, UUID.randomUUID().toString(), new BookingCommand(1, 0, 4, TravelClass.THIRD_AC,
                Quota.GN, List.of(new PassengerSpec(1, name, 40, 'M', null, true)), false, "9820012345", null));
        UUID paymentId = UUID.randomUUID();   // what PaymentIntentService would have bound after calling payment-service
        jdbc.sql("UPDATE reservation.booking SET payment_id = :p WHERE pnr = :pnr").param("p", paymentId).param("pnr", v.pnr()).update();
        return new Booked(user, v.pnr(), paymentId, v.totalFare());
    }

    void pay(Booked b) {
        payments.onCaptured(UUID.randomUUID(), new PaymentCaptured(b.paymentId(), b.pnr(), b.fare(), "INR"));
    }

    String status(String pnr) {
        return jdbc.sql("SELECT status FROM reservation.booking WHERE pnr = :p").param("p", pnr).query(String.class).single();
    }

    String passenger(String pnr) {
        return jdbc.sql("""
                SELECT p.current_status_text FROM reservation.passenger p JOIN reservation.booking b
                  ON b.id = p.booking_id AND b.run_date = p.run_date WHERE b.pnr = :p""").param("p", pnr).query(String.class).single();
    }

    long events(String type, String pnr) {
        return jdbc.sql("SELECT count(*) FROM reservation.outbox WHERE event_type = :t AND payload->>'pnr' = :p")
                .param("t", type).param("p", pnr).query(Long.class).single();
    }

    @Test
    void cancelling_a_confirmed_berth_refunds_and_promotes_the_chain() {
        Booked c1 = book("Asha Rao"), c2 = book("Ravi Iyer"), c3 = book("Meena Das");
        Booked r1 = book("Kiran Shah"), r2 = book("Vikram Sen");
        Booked w1 = book("Neha Pillai");
        for (Booked b : List.of(c1, c2, c3, r1, r2, w1)) pay(b);
        assertThat(passenger(c1.pnr())).startsWith("CNF");
        assertThat(passenger(r1.pnr())).startsWith("RAC");
        assertThat(passenger(w1.pnr())).startsWith("WL");

        var result = cancellations.cancel(c1.user(), "cancel-" + c1.pnr(), c1.pnr(), List.of(1));
        assertThat(status(c1.pnr())).isEqualTo("CANCELLED");
        // MONEY: a refund request is emitted in the same transaction, with a deterministic idempotency key.
        assertThat(events("RefundRequested", c1.pnr())).isEqualTo(1);
        assertThat(result.refundAmount()).isPositive().isLessThan(c1.fare());   // cancellation charge was deducted

        promotions.promoteAll(1, "3A", "GN");
        assertThat(passenger(r1.pnr())).as("first RAC moves up").startsWith("CNF");
        assertThat(passenger(w1.pnr())).as("first WL moves into the freed RAC slot").startsWith("RAC");
        assertThat(events("PassengerPromoted", r1.pnr())).isEqualTo(1);

        promotions.promoteAll(1, "3A", "GN");   // redelivered InventoryChanged: nothing more to do
        assertThat(jdbc.sql("SELECT count(*) FROM reservation.outbox WHERE event_type='PassengerPromoted'")
                .query(Long.class).single()).isEqualTo(2);
        assertInventoryInvariants();
        assertOutboxHonoursContracts();
    }

    @Test
    void the_same_capture_delivered_twice_confirms_once() {
        Booked b = book("Asha Rao");
        UUID eventId = UUID.randomUUID();
        var e = new PaymentCaptured(b.paymentId(), b.pnr(), b.fare(), "INR");
        payments.onCaptured(eventId, e);
        payments.onCaptured(eventId, e);                     // Kafka redelivery: same event id
        payments.onCaptured(UUID.randomUUID(), e);           // gateway double-webhook: new event, same payment
        assertThat(status(b.pnr())).isEqualTo("CONFIRMED");
        assertThat(events("BookingConfirmed", b.pnr())).isEqualTo(1);
        assertThat(events("RefundRequested", b.pnr())).as("same payment twice is not a second payment").isZero();
    }

    @Test
    void a_capture_for_the_wrong_amount_never_confirms_and_is_refunded() {
        // MONEY: the gateway (or an attacker replaying a webhook) captured less than the fare.
        Booked b = book("Asha Rao");
        payments.onCaptured(UUID.randomUUID(), new PaymentCaptured(b.paymentId(), b.pnr(), b.fare().subtract(BigDecimal.ONE), "INR"));
        assertThat(status(b.pnr())).isNotEqualTo("CONFIRMED");
        assertThat(jdbc.sql("SELECT payload->>'reason' FROM reservation.outbox WHERE event_type='RefundRequested'")
                .query(String.class).single()).isEqualTo("AMOUNT_MISMATCH");
        assertInventoryInvariants();
    }

    @Test
    void a_stray_payment_for_a_bound_booking_is_refunded_not_applied() {
        Booked b = book("Asha Rao");
        payments.onCaptured(UUID.randomUUID(), new PaymentCaptured(UUID.randomUUID(), b.pnr(), b.fare(), "INR"));
        assertThat(status(b.pnr())).isEqualTo("PENDING_PAYMENT");
        assertThat(events("RefundRequested", b.pnr())).isEqualTo(1);
    }

    @Test
    void a_capture_for_an_unknown_pnr_is_refunded() {
        payments.onCaptured(UUID.randomUUID(), new PaymentCaptured(UUID.randomUUID(), "2999999998", new BigDecimal("100.00"), "INR"));
        assertThat(jdbc.sql("SELECT payload->>'reason' FROM reservation.outbox WHERE event_type='RefundRequested'")
                .query(String.class).single()).isEqualTo("UNKNOWN_PNR");
    }

    @Test
    void an_unpaid_hold_expires_and_returns_its_berth() {
        Booked b = book("Asha Rao");
        jdbc.sql("UPDATE reservation.booking SET hold_expires_at = now() - interval '1 second' WHERE pnr = :p").param("p", b.pnr()).update();
        jdbc.sql("UPDATE reservation.allocation SET hold_expires_at = now() - interval '1 second' WHERE hold_expires_at IS NOT NULL").update();
        assertThat(holds.sweep(100)).isEqualTo(1);
        assertThat(status(b.pnr())).isEqualTo("EXPIRED");
        assertThat(jdbc.sql("SELECT sum(cnf_used) FROM reservation.segment_inventory").query(Integer.class).single()).isZero();
        assertThat(events("BookingExpired", b.pnr())).isEqualTo(1);
        assertThat(holds.sweep(100)).as("idempotent").isZero();
        assertInventoryInvariants();
        assertOutboxHonoursContracts();
    }

    @Test
    void a_late_payment_after_expiry_reallocates_if_a_berth_is_free() {
        Booked b = book("Asha Rao");
        jdbc.sql("UPDATE reservation.booking SET hold_expires_at = now() - interval '1 second' WHERE pnr = :p").param("p", b.pnr()).update();
        jdbc.sql("UPDATE reservation.allocation SET hold_expires_at = now() - interval '1 second' WHERE hold_expires_at IS NOT NULL").update();
        holds.sweep(100);
        pay(b);   // the gateway was slow; the customer did pay
        assertThat(status(b.pnr())).isEqualTo("CONFIRMED");
        assertThat(events("BookingConfirmed", b.pnr())).isEqualTo(1);
        assertInventoryInvariants();
    }

    @Test
    void a_replayed_capture_with_a_different_amount_never_refunds_a_confirmed_booking() {
        // INC-003: a dead-lettered event replayed with a hand-patched amount; or any corrupted duplicate.
        Booked b = book("Asha Rao");
        pay(b);
        assertThat(status(b.pnr())).isEqualTo("CONFIRMED");
        for (String amount : List.of("1.00", b.fare().subtract(BigDecimal.ONE).toPlainString(), "99999.00")) {
            payments.onCaptured(UUID.randomUUID(), new PaymentCaptured(b.paymentId(), b.pnr(), new BigDecimal(amount), "INR"));
        }
        assertThat(status(b.pnr())).isEqualTo("CONFIRMED");
        assertThat(events("RefundRequested", b.pnr())).as("seat AND refund would be a money loss").isZero();
    }

    @Test
    void a_duplicate_capture_after_cancellation_does_not_refund_twice() {
        Booked b = book("Asha Rao");
        pay(b);
        cancellations.cancel(b.user(), "cancel-" + b.pnr(), b.pnr(), List.of(1));
        assertThat(events("RefundRequested", b.pnr())).isEqualTo(1);   // the cancellation refund
        pay(b);                                                        // the capture event, delivered again
        assertThat(events("RefundRequested", b.pnr())).as("no second, full refund").isEqualTo(1);
    }

    @Test
    void a_first_capture_for_the_wrong_amount_is_still_refunded() {
        // The guard must not swallow the legitimate case (Phase 11 test kept as the control).
        Booked b = book("Asha Rao");
        payments.onCaptured(UUID.randomUUID(), new PaymentCaptured(b.paymentId(), b.pnr(), b.fare().add(BigDecimal.TEN), "INR"));
        assertThat(events("RefundRequested", b.pnr())).isEqualTo(1);
    }
}
