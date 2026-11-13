package in.railops.reservation.it;

import in.railops.reservation.application.BookingCommand;
import in.railops.reservation.application.BookingService;
import in.railops.reservation.application.BookingView;
import in.railops.reservation.domain.error.DomainException;
import in.railops.reservation.domain.model.PassengerSpec;
import in.railops.reservation.domain.model.Quota;
import in.railops.reservation.domain.model.TravelClass;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The core promise: under concurrency, never two passengers on one berth half, and the counters agree with reality.
 * Seeded GN pool: 3 CNF berths, 2 RAC slots, WL cap 3.
 */
class BookingConcurrencyIT extends AbstractReservationIT {

    @Autowired
    BookingService bookings;

    static BookingCommand gn(String name) {
        return new BookingCommand(1, 0, 4, TravelClass.THIRD_AC, Quota.GN,
                List.of(new PassengerSpec(1, name, 34, 'F', null, true)), false, "9820012345", null);
    }

    @RepeatedTest(3)
    void thirty_concurrent_bookers_on_a_five_seat_pool() throws Exception {
        int n = 30;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CyclicBarrier start = new CyclicBarrier(n);
        List<Future<Object>> results = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String name = "Passenger " + (char) ('A' + i % 26) + (char) ('a' + i / 26);
            results.add(pool.submit(() -> {
                start.await();
                try {
                    return bookings.create(UUID.randomUUID(), UUID.randomUUID().toString(), gn(name));
                } catch (DomainException e) {
                    return e.code().name();
                }
            }));
        }
        Map<String, Integer> outcome = new TreeMap<>();
        for (Future<Object> f : results) {
            Object r = f.get(60, TimeUnit.SECONDS);
            String k = r instanceof BookingView v ? v.passengers().getFirst().currentStatus().replaceAll("[ /0-9].*", "") : (String) r;
            outcome.merge(k, 1, Integer::sum);
        }
        pool.shutdown();

        assertThat(outcome).containsEntry("CNF", 3).containsEntry("RAC", 2).containsEntry("WL", 3);
        assertThat(outcome.values().stream().mapToInt(Integer::intValue).sum()).isEqualTo(n);
        assertThat(outcome.keySet()).as("everyone else is told 'regret', nobody gets a 500")
                .containsOnly("CNF", "RAC", "WL", "RES_REGRET");
        assertInventoryInvariants();
        assertOutboxHonoursContracts();
    }

    @Test
    void same_idempotency_key_raced_from_ten_threads_creates_one_booking() throws Exception {
        UUID user = UUID.randomUUID();
        String key = "retry-" + UUID.randomUUID();
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CyclicBarrier start = new CyclicBarrier(10);
        Set<String> pnrs = ConcurrentHashMap.newKeySet();
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            fs.add(pool.submit(() -> {
                start.await();
                try {
                    pnrs.add(bookings.create(user, key, gn("Double Clicker")).pnr());
                } catch (DomainException e) {
                    pnrs.add("rejected:" + e.code());   // "in progress" is an acceptable answer to a racing retry
                }
                return null;
            }));
        }
        for (Future<?> f : fs) f.get(60, TimeUnit.SECONDS);
        pool.shutdown();

        assertThat(pnrs.stream().filter(p -> !p.startsWith("rejected")).toList()).hasSize(1);
        assertThat(jdbc.sql("SELECT count(*) FROM reservation.booking").query(Integer.class).single()).isEqualTo(1);
        assertInventoryInvariants();
    }

    @Test
    void same_key_with_a_different_body_is_refused() {
        UUID user = UUID.randomUUID();
        bookings.create(user, "k-1", gn("Sana Qureshi"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> bookings.create(user, "k-1", gn("Someone Else")))
                .isInstanceOf(DomainException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM reservation.booking").query(Integer.class).single()).isEqualTo(1);
    }

    @Test
    void the_same_key_from_another_user_is_a_different_request() {
        // Keys are scoped per user: one user can't replay (or block) another's booking by guessing its key.
        String a = bookings.create(UUID.randomUUID(), "shared-key", gn("Sana Qureshi")).pnr();
        String b = bookings.create(UUID.randomUUID(), "shared-key", gn("Sana Qureshi")).pnr();
        assertThat(a).isNotEqualTo(b);
    }
}
