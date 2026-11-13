package in.railops.reservation.application;

import in.railops.common.messaging.OutboxWriter;
import in.railops.reservation.adapter.out.persistence.AllocationRepository;
import in.railops.reservation.adapter.out.persistence.AllocationRepository.Released;
import in.railops.reservation.adapter.out.persistence.BookingRepository;
import in.railops.reservation.adapter.out.persistence.BookingRepository.BookingKey;
import in.railops.reservation.adapter.out.persistence.BookingRepository.BookingRow;
import in.railops.reservation.adapter.out.persistence.BookingRepository.PassengerRow;
import in.railops.reservation.adapter.out.persistence.InventoryRepository;
import in.railops.reservation.adapter.out.persistence.WaitlistRepository;
import in.railops.reservation.contracts.ReservationEvents;
import in.railops.reservation.contracts.ReservationEvents.BookingExpired;
import in.railops.reservation.contracts.ReservationEvents.InventoryChanged;
import in.railops.reservation.domain.model.BookingStatus;
import in.railops.reservation.domain.model.PassengerStatus;
import in.railops.reservation.domain.model.SegmentRange;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Releases inventory held by a booking. Lock order: booking row (caller) → counters (seg order)
 * → passengers → allocations / waitlist entries.
 */
@Component
public class HoldReleaser {

    private static final Logger log = LoggerFactory.getLogger(HoldReleaser.class);

    private final BookingRepository bookings;
    private final InventoryRepository inventory;
    private final AllocationRepository allocations;
    private final WaitlistRepository waitlist;
    private final OutboxWriter outbox;
    private final TransactionRunner tx;

    public HoldReleaser(BookingRepository bookings, InventoryRepository inventory, AllocationRepository allocations,
                        WaitlistRepository waitlist, OutboxWriter outbox, TransactionRunner tx) {
        this.bookings = bookings;
        this.inventory = inventory;
        this.allocations = allocations;
        this.waitlist = waitlist;
        this.outbox = outbox;
        this.tx = tx;
    }

    /** Unconditional expiry of every expired hold (tests, tools). The scheduler uses {@link #expire} via HoldSweeper. */
    public int sweep(int limit) {
        return expireAll(bookings.expiredPendingKeys(limit));
    }

    /** Expires exactly these holds (each re-checked under its row lock). */
    public int expire(List<BookingKey> keys) {
        return expireAll(keys);
    }

    /** Booking-path cleanup for one pool; runs before the booking transaction, never inside it. */
    public int releaseExpiredInPool(long runId, String cls, String quota) {
        return expireAll(bookings.expiredPendingKeysInPool(runId, cls, quota, 50));
    }

    private int expireAll(List<BookingKey> keys) {
        int expired = 0;
        for (BookingKey k : keys) {
            try {
                Boolean done = tx.inNewTransaction(() -> bookings.lockIfExpiredPending(k.runDate(), k.id())
                        .map(b -> {
                            release(b, "HOLD_EXPIRED", BookingStatus.EXPIRED, "EXPIRED");
                            outbox.append(ReservationEvents.TOPIC_BOOKING, "Booking", b.pnr(), "BookingExpired",
                                    new BookingExpired(b.pnr(), b.trainRunId()), Map.of());
                            return true;
                        })
                        .orElse(false));
                if (Boolean.TRUE.equals(done)) {
                    expired++;
                }
            } catch (RuntimeException e) {
                log.error("failed to expire booking {}/{}; will retry next sweep", k.runDate(), k.id(), e);
            }
        }
        return expired;
    }

    /**
     * Frees every allocation and open waitlist entry of a booking and moves it to {@code next}.
     * Caller holds the booking row lock.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(BookingRow b, String reason, BookingStatus next, String passengerText) {
        SegmentRange range = new SegmentRange(b.fromSeq(), b.toSeq());
        inventory.lockRange(b.trainRunId(), b.classCode(), b.quotaCode(), range);

        List<PassengerRow> pax = bookings.passengers(b.runDate(), b.id());
        List<Long> ids = pax.stream().map(PassengerRow::id).toList();
        List<Released> released = allocations.releaseForPassengers(b.runDate(), ids, reason);
        int full = (int) released.stream().filter(r -> !r.slot().isRac()).count();
        int rac = released.size() - full;
        inventory.adjust(b.trainRunId(), b.classCode(), b.quotaCode(), range, -full, -rac);
        waitlist.cancelOpen(b.runDate(), ids);

        bookings.updateStatus(b, next);
        for (PassengerRow p : pax) {
            bookings.updatePassengerStatus(b.runDate(), p.id(), PassengerStatus.CAN, passengerText);
        }
        if (full + rac > 0) {
            outbox.append(ReservationEvents.TOPIC_INVENTORY, "TrainRun", Long.toString(b.trainRunId()),
                    "InventoryChanged", new InventoryChanged(b.trainRunId(), b.classCode(), b.quotaCode(),
                            b.fromSeq(), b.toSeq(), reason), Map.of());
        }
    }
}
