package in.railops.reservation.it;

import in.railops.reservation.adapter.in.scheduler.HoldSweeper;
import in.railops.reservation.adapter.out.http.PaymentClient;
import in.railops.reservation.application.BookingCommand;
import in.railops.reservation.application.BookingService;
import in.railops.reservation.domain.model.PassengerSpec;
import in.railops.reservation.domain.model.Quota;
import in.railops.reservation.domain.model.TravelClass;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * The SCHEDULED sweeper against the real database (INC-001b: it threw on every tick because of a transaction
 * propagation rule that only exists at runtime; the unit test of its decision table could not see that).
 */
class HoldSweeperIT extends AbstractReservationIT {

    @Autowired HoldSweeper sweeper;
    @Autowired BookingService bookings;
    @MockitoBean PaymentClient payments;

    String hold(boolean withPayment) {
        String pnr = bookings.create(UUID.randomUUID(), UUID.randomUUID().toString(), new BookingCommand(1, 0, 4, TravelClass.THIRD_AC,
                Quota.GN, List.of(new PassengerSpec(1, "Sweep Tester", 40, 'M', null, true)), false, "9820012345", null)).pnr();
        UUID pay = withPayment ? UUID.randomUUID() : null;
        jdbc.sql("UPDATE reservation.booking SET payment_id = :p, hold_expires_at = now() - interval '5 seconds' WHERE pnr = :pnr")
                .param("p", pay).param("pnr", pnr).update();
        return pnr;
    }

    String status(String pnr) {
        return jdbc.sql("SELECT status FROM reservation.booking WHERE pnr = :p").param("p", pnr).query(String.class).single();
    }

    UUID paymentOf(String pnr) {
        return jdbc.sql("SELECT payment_id FROM reservation.booking WHERE pnr = :p").param("p", pnr).query(UUID.class).single();
    }

    @Test
    void unpaid_holds_expire_and_a_captured_payment_is_confirmed_not_expired() {
        String unpaid = hold(false);
        String paid = hold(true);
        String fare = jdbc.sql("SELECT total_fare::text FROM reservation.booking WHERE pnr = :p").param("p", paid).query(String.class).single();
        when(payments.statuses(any())).thenReturn(Map.of(paymentOf(paid),
                new PaymentClient.PaymentStatus(paymentOf(paid), paid, "CAPTURED", new java.math.BigDecimal(fare), "INR")));

        sweeper.sweep();      // the real scheduled entry point, bulkhead and all

        assertThat(status(unpaid)).as("control: expiry still works").isEqualTo("EXPIRED");
        assertThat(status(paid)).as("paid during an event-path outage keeps the berth").isEqualTo("CONFIRMED");
        assertInventoryInvariants();
        assertOutboxHonoursContracts();
    }

    @Test
    void payment_service_unreachable_means_wait_not_expire_but_unpaid_holds_still_go() {
        String unpaid = hold(false);
        String paid = hold(true);
        when(payments.statuses(any())).thenThrow(new IllegalStateException("connect timed out"));

        sweeper.sweep();

        assertThat(status(unpaid)).isEqualTo("EXPIRED");
        assertThat(status(paid)).isEqualTo("PENDING_PAYMENT");
    }

    @Test
    void the_booking_path_cleanup_never_touches_holds_with_a_payment_in_flight() {
        String paid = hold(true);
        jdbc.sql("UPDATE reservation.segment_inventory SET cnf_capacity = cnf_used, rac_capacity = rac_used WHERE quota_code='GN'").update();
        jdbc.sql("UPDATE reservation.waitlist_counter SET wl_cap = next_seq - 1 WHERE quota_code='GN'").update();
        try {
            bookings.create(UUID.randomUUID(), UUID.randomUUID().toString(), new BookingCommand(1, 0, 4, TravelClass.THIRD_AC,
                    Quota.GN, List.of(new PassengerSpec(1, "Rival Customer", 30, 'F', null, true)), false, "9820012345", null));
        } catch (RuntimeException expected) {
            // regret: the pool is full
        }
        assertThat(status(paid)).isEqualTo("PENDING_PAYMENT");
    }
}
