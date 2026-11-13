package in.railops.reservation.it;

import in.railops.reservation.application.*;
import in.railops.reservation.application.prediction.PredictionMonitor;
import in.railops.reservation.application.prediction.WaitlistPredictionService;
import in.railops.reservation.application.prediction.WaitlistPredictionService.Reason;
import in.railops.reservation.contracts.PaymentEvents.PaymentCaptured;
import in.railops.reservation.domain.error.DomainException;
import in.railops.reservation.domain.model.PassengerSpec;
import in.railops.reservation.domain.model.Quota;
import in.railops.reservation.domain.model.TravelClass;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Serve -> log -> chart -> resolve -> calibrate, on the real schema (seed: 3A/GN = 3 CNF + 2 RAC, WL cap 3). */
class WaitlistPredictionIT extends AbstractReservationIT {

    @Autowired BookingService bookings;
    @Autowired PaymentConfirmationService payments;
    @Autowired CancellationService cancellations;
    @Autowired PromotionService promotions;
    @Autowired ChartService charts;
    @Autowired WaitlistPredictionService predictions;
    @Autowired PredictionMonitor monitor;

    record Booked(UUID user, String pnr) {
    }

    @BeforeEach
    void clearLog() {
        jdbc.sql("TRUNCATE reservation.wl_prediction").update();
        // The seed's run date may be far out; the model only needs it to be in the future and SCHEDULED.
    }

    Booked bookAndPay(String name) {
        UUID user = UUID.randomUUID();
        BookingView v = bookings.create(user, UUID.randomUUID().toString(), new BookingCommand(1, 0, 4,
                TravelClass.THIRD_AC, Quota.GN, List.of(new PassengerSpec(1, name, 40, 'F', null, true)), false,
                "9820012345", null));
        UUID paymentId = UUID.randomUUID();
        jdbc.sql("UPDATE reservation.booking SET payment_id = :p WHERE pnr = :pnr").param("p", paymentId)
                .param("pnr", v.pnr()).update();
        payments.onCaptured(UUID.randomUUID(), new PaymentCaptured(paymentId, v.pnr(), v.totalFare(), "INR"));
        return new Booked(user, v.pnr());
    }

    long logged() {
        return jdbc.sql("SELECT count(*) FROM reservation.wl_prediction").query(Long.class).single();
    }

    @Test
    void predictions_are_served_logged_once_per_bucket_and_resolved_after_chart() {
        Booked c1 = bookAndPay("Asha Rao");
        bookAndPay("Ravi Iyer");
        bookAndPay("Meena Das");
        bookAndPay("Kiran Shah");
        bookAndPay("Vikram Sen");
        Booked w1 = bookAndPay("Neha Pillai");
        Booked w2 = bookAndPay("Arjun Nair");

        var p1 = predictions.forBooking(w1.user(), w1.pnr());
        var p2 = predictions.forBooking(w2.user(), w2.pnr());
        assertThat(p1.probability()).isNotNull().isBetween(0.0, 1.0);
        assertThat(p1.currentWlPosition()).isEqualTo(1);
        assertThat(p2.currentWlPosition()).isEqualTo(2);
        assertThat(p2.probability()).as("further back never scores higher").isLessThanOrEqualTo(p1.probability());
        assertThat(p1.disclaimer()).contains("not a guarantee");
        assertThat(logged()).isEqualTo(2);

        predictions.forBooking(w1.user(), w1.pnr());          // refresh within the same 6 h bucket
        assertThat(logged()).as("refreshing does not flood the log").isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM reservation.wl_prediction WHERE features::text ~* '(name|phone|email)'")
                .query(Long.class).single()).as("no PII in logged features").isZero();

        // Not waitlisted, foreign PNR, uncovered quota, public next-WL estimate
        assertThat(predictions.forBooking(c1.user(), c1.pnr()).reason()).isEqualTo(Reason.NOT_WAITLISTED);
        assertThatThrownBy(() -> predictions.forBooking(UUID.randomUUID(), w1.pnr()))
                .isInstanceOf(DomainException.class).hasMessageContaining("not found");
        assertThat(predictions.forNextWaitlist(1, "3A", "TQ").reason()).isEqualTo(Reason.NOT_COVERED);
        var next = predictions.forNextWaitlist(1, "3A", "GN");
        assertThat(next.currentWlPosition()).isEqualTo(3);
        assertThat(next.probability()).isLessThanOrEqualTo(p2.probability());

        // A cancellation lets w1 move up into RAC; the chart then expires w2.
        cancellations.cancel(c1.user(), "cancel-" + c1.pnr(), c1.pnr(), List.of(1));
        promotions.promoteAll(1, "3A", "GN");
        charts.prepare(1, 1, "it");
        assertThat(predictions.forBooking(w2.user(), w2.pnr()).reason())
                .as("after the chart the outcome is known: no estimate").isIn(Reason.CHART_PREPARED, Reason.NOT_WAITLISTED);

        var cal = monitor.runOnce();
        var outcomes = jdbc.sql("""
                SELECT b.pnr, p.outcome FROM reservation.wl_prediction p
                JOIN reservation.booking b ON b.run_date = p.run_date AND b.id = p.booking_id ORDER BY b.pnr""")
                .query((rs, n) -> rs.getString(1) + "=" + rs.getString(2)).list();
        assertThat(outcomes).containsExactlyInAnyOrder(w1.pnr() + "=CONFIRMED", w2.pnr() + "=NOT_CONFIRMED");
        assertThat(cal.n()).isEqualTo(2);
        assertThat(cal.observedRate()).isEqualTo(0.5);
        double expectedBrier = (Math.pow(p1.probability() - 1, 2) + Math.pow(p2.probability(), 2)) / 2;
        assertThat(cal.brier()).as("Brier from the stored (unrounded) probabilities")
                .isCloseTo(expectedBrier, org.assertj.core.api.Assertions.within(0.01));

        assertThat(monitor.runOnce().n()).as("resolution is idempotent").isEqualTo(2);
        assertInventoryInvariants();
    }

    @Test
    void a_cancelled_waitlisted_passenger_has_no_outcome_to_score() {
        for (String n : List.of("A One", "B Two", "C Three", "D Four", "E Five")) {
            bookAndPay(n);
        }
        Booked w = bookAndPay("F Six");
        assertThat(predictions.forBooking(w.user(), w.pnr()).probability()).isNotNull();
        cancellations.cancel(w.user(), "cancel-" + w.pnr(), w.pnr(), List.of(1));
        charts.prepare(1, 1, "it");
        monitor.runOnce();
        assertThat(jdbc.sql("SELECT outcome FROM reservation.wl_prediction").query(String.class).single())
                .isEqualTo("VOID");
        assertThat(monitor.runOnce().n()).isZero();
    }
}
