package in.railops.reservation.it;

import in.railops.reservation.adapter.out.http.PaymentClient;
import in.railops.reservation.application.BookingCommand;
import in.railops.reservation.application.BookingService;
import in.railops.reservation.application.SeatMapService;
import in.railops.reservation.application.SeatMapService.State;
import in.railops.reservation.domain.model.PassengerSpec;
import in.railops.reservation.domain.model.Quota;
import in.railops.reservation.domain.model.TravelClass;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 6: the live seat map follows RailOps' own allocations, per journey segment, and never names anyone. */
class SeatMapIT extends AbstractReservationIT {

    @Autowired BookingService bookings;
    @Autowired SeatMapService seatMaps;
    @MockitoBean PaymentClient payments;   // same overrides as HoldSweeperIT: shares its Spring context

    record Taken(String coach, int berth) {
    }

    String hold(int from, int to) {
        return bookings.create(UUID.randomUUID(), UUID.randomUUID().toString(), new BookingCommand(1, from, to, TravelClass.THIRD_AC,
                Quota.GN, List.of(new PassengerSpec(1, "Demo Passenger", 30, 'F', null, true)), false, "9820012345", null)).pnr();
    }

    Taken berthOf(String pnr) {
        return jdbc.sql("""
                        SELECT c.coach_label, a.berth_no FROM reservation.allocation a
                        JOIN reservation.passenger p ON p.id = a.passenger_id
                        JOIN reservation.booking b ON b.id = p.booking_id
                        JOIN reservation.run_coach c ON c.id = a.run_coach_id
                        WHERE b.pnr = :pnr""")
                .param("pnr", pnr).query((rs, n) -> new Taken(rs.getString(1), rs.getInt(2))).single();
    }

    State stateOf(SeatMapService.SeatMap map, Taken t) {
        return map.coaches().stream().filter(c -> c.label().equals(t.coach())).findFirst().orElseThrow()
                .berths().stream().filter(b -> b.no() == t.berth()).findFirst().orElseThrow().state();
    }

    @Test
    void a_held_berth_shows_taken_on_overlapping_journeys_and_free_on_others() {
        Taken t = berthOf(hold(0, 2));
        assertThat(stateOf(seatMaps.map(1, "3A", 0, 2), t)).isEqualTo(State.TAKEN);
        assertThat(stateOf(seatMaps.map(1, "3A", 1, 3), t)).as("overlaps segment 1").isEqualTo(State.TAKEN);
        assertThat(stateOf(seatMaps.map(1, "3A", 2, 4), t)).as("boards where the other alights").isEqualTo(State.FREE);
    }

    @Test
    void every_berth_of_the_class_is_listed_with_its_type_and_a_free_count() {
        var map = seatMaps.map(1, "3A", 0, 4);
        assertThat(map.coaches()).isNotEmpty();
        var coach = map.coaches().getFirst();
        assertThat(coach.berths()).extracting(SeatMapService.Berth::type).contains("LB", "MB", "UB", "SL", "SU");
        assertThat(coach.free()).isEqualTo((int) coach.berths().stream().filter(b -> b.state() == State.FREE).count());
    }

    @Test
    void the_response_type_has_no_field_that_could_name_a_passenger() {
        assertThat(java.util.Arrays.stream(SeatMapService.Berth.class.getRecordComponents()).map(c -> c.getName()))
                .containsExactly("no", "type", "state");
    }
}
