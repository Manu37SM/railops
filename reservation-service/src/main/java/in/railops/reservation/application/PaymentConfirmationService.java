package in.railops.reservation.application;

import in.railops.common.messaging.OutboxWriter;
import in.railops.common.messaging.ProcessedEvents;
import in.railops.reservation.adapter.out.persistence.AllocationRepository;
import in.railops.reservation.adapter.out.persistence.BookingRepository;
import in.railops.reservation.adapter.out.persistence.BookingRepository.BookingRow;
import in.railops.reservation.adapter.out.persistence.BookingRepository.PassengerRow;
import in.railops.reservation.adapter.out.persistence.WaitlistRepository;
import in.railops.reservation.application.AllocationEngine.Assignment;
import in.railops.reservation.application.AllocationEngine.Ctx;
import in.railops.reservation.application.AllocationEngine.Free;
import in.railops.reservation.contracts.PaymentEvents.PaymentCaptured;
import in.railops.reservation.contracts.PaymentEvents.PaymentFailed;
import in.railops.reservation.contracts.ReservationEvents;
import in.railops.reservation.contracts.ReservationEvents.BookingConfirmed;
import in.railops.reservation.contracts.ReservationEvents.InventoryChanged;
import in.railops.reservation.contracts.ReservationEvents.RefundRequested;
import in.railops.reservation.domain.error.DomainException;
import in.railops.reservation.domain.model.BookingStatus;
import in.railops.reservation.domain.model.PassengerSpec;
import in.railops.reservation.domain.model.PassengerStatus;
import in.railops.reservation.domain.model.Quota;
import in.railops.reservation.domain.model.SegmentRange;
import in.railops.reservation.domain.model.TravelClass;
import in.railops.reservation.domain.policy.AllocationPlanner;
import in.railops.reservation.domain.policy.AllocationPlanner.Plan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The saga step that turns a captured payment into a confirmed booking (Phase 4 §5).
 * Runs once per event (processed_event dedupe) and never confirms on an amount or currency mismatch.
 */
@Service
public class PaymentConfirmationService {

    private static final Logger log = LoggerFactory.getLogger(PaymentConfirmationService.class);
    static final String CONSUMER = "reservation.payment-confirmation";

    /** Thrown inside the re-allocation savepoint when the result is worse than what the passenger saw. */
    private static final class ReallocationWorse extends RuntimeException {
        ReallocationWorse() {
            super(null, null, false, false);
        }
    }

    private final BookingRepository bookings;
    private final AllocationRepository allocations;
    private final WaitlistRepository waitlist;
    private final AllocationEngine engine;
    private final HoldReleaser holdReleaser;
    private final ProcessedEvents processed;
    private final OutboxWriter outbox;
    private final TransactionRunner tx;
    private final AllocationPlanner planner = new AllocationPlanner();

    public PaymentConfirmationService(BookingRepository bookings, AllocationRepository allocations,
                                      WaitlistRepository waitlist, AllocationEngine engine, HoldReleaser holdReleaser,
                                      ProcessedEvents processed, OutboxWriter outbox, TransactionRunner tx) {
        this.bookings = bookings;
        this.allocations = allocations;
        this.waitlist = waitlist;
        this.engine = engine;
        this.holdReleaser = holdReleaser;
        this.processed = processed;
        this.outbox = outbox;
        this.tx = tx;
    }

    public void onCaptured(UUID eventId, PaymentCaptured e) {
        tx.inNewTransaction(() -> {
            if (!processed.markProcessed(CONSUMER, eventId)) {
                return;
            }
            BookingRow b = bookings.lockByPnr(e.pnr()).orElse(null);
            if (b == null) {
                log.error("PaymentCaptured for unknown PNR {} (payment {}); refunding", e.pnr(), e.paymentId());
                refund(e.pnr(), e.paymentId(), e.amount(), "UNKNOWN_PNR");
                return;
            }
            if (bookings.captureApplied(b, e.paymentId())) {
                // INC-003: this exact payment was already consumed (confirmed, re-seated or refunded). A second copy
                // of its capture event, whatever amount it claims, can never mean new money: never refund on it.
                if (e.amount().compareTo(b.totalFare()) != 0) {
                    log.error("capture event for already-applied payment {} on PNR {} claims {} (fare {}): ignored, "
                            + "investigate the producer", e.paymentId(), b.pnr(), e.amount(), b.totalFare());
                } else {
                    log.info("duplicate capture for PNR {} (payment {}): no-op", b.pnr(), e.paymentId());
                }
                return;
            }
            if (b.paymentId() != null && !b.paymentId().equals(e.paymentId())) {
                log.error("PNR {} captured by payment {} but booking is bound to {}; refunding the stray payment",
                        b.pnr(), e.paymentId(), b.paymentId());
                refund(b.pnr(), e.paymentId(), e.amount(), "SUPERSEDED_PAYMENT");
                return;
            }
            if (!"INR".equals(e.currency()) || e.amount().compareTo(b.totalFare()) != 0) {
                log.error("PNR {} amount mismatch: captured {} {} vs fare {}", b.pnr(), e.amount(), e.currency(),
                        b.totalFare());
                failAndRefund(b, e, "AMOUNT_MISMATCH");
                return;
            }
            switch (b.status()) {
                case PENDING_PAYMENT -> {
                    if (b.holdValid()) {
                        confirm(b, e.paymentId());
                    } else {
                        // Hold lapsed but the sweeper hasn't reached it: expire here, then late path.
                        holdReleaser.release(b, "HOLD_EXPIRED", BookingStatus.EXPIRED, "EXPIRED");
                        reallocate(bookings.lockByPnr(b.pnr()).orElseThrow(), e);
                    }
                }
                case EXPIRED -> reallocate(b, e);
                case CONFIRMED, PARTIALLY_CANCELLED ->
                        log.warn("duplicate capture for confirmed PNR {} (payment {}); no-op", b.pnr(), e.paymentId());
                default -> {
                    log.warn("capture for PNR {} in status {}; refunding", b.pnr(), b.status());
                    bookings.markCaptureApplied(b, e.paymentId());
                    refund(b.pnr(), e.paymentId(), e.amount(), "BOOKING_" + b.status());
                }
            }
        });
    }

    /**
     * Payment failures don't end the booking: the passenger may retry within the hold. Expiry cleans
     * up if they don't. Recorded only for dedupe and observability.
     */
    public void onFailed(UUID eventId, PaymentFailed e) {
        tx.inNewTransaction(() -> {
            if (processed.markProcessed(CONSUMER, eventId)) {
                log.info("payment {} failed for PNR {}: {}", e.paymentId(), e.pnr(), e.reason());
            }
        });
    }

    private void confirm(BookingRow b, UUID paymentId) {
        List<Long> ids = bookings.passengers(b.runDate(), b.id()).stream().map(PassengerRow::id).toList();
        bookings.attachPayment(b, paymentId);
        bookings.markCaptureApplied(b, paymentId);
        allocations.confirmHeld(b.runDate(), ids);
        waitlist.activatePending(b.runDate(), ids);
        bookings.updateStatus(b, BookingStatus.CONFIRMED);
        outbox.append(ReservationEvents.TOPIC_BOOKING, "Booking", b.pnr(), "BookingConfirmed",
                new BookingConfirmed(b.pnr(), b.trainRunId(), paymentId, false), Map.of());
    }

    /**
     * Late payment: try to re-seat the same passengers, CONFIRMED directly. Accepted only if every
     * passenger ends up at least as well placed as they were shown at checkout; anything worse is a
     * product the passenger didn't agree to, so the whole booking is refunded instead.
     *
     * <p>The attempt runs behind a savepoint so a worse outcome is undone without losing the dedupe row.
     */
    private void reallocate(BookingRow b, PaymentCaptured e) {
        List<PassengerRow> pax = bookings.passengers(b.runDate(), b.id());
        try {
            tx.inSavepoint(() -> {
                Ctx ctx = new Ctx(b.trainRunId(), b.runDate(), TravelClass.fromCode(b.classCode()),
                        Quota.valueOf(b.quotaCode()), new SegmentRange(b.fromSeq(), b.toSeq()));
                List<PassengerSpec> specs = pax.stream().map(p -> new PassengerSpec(p.seqNo(), p.name(), p.age(),
                        p.gender(), p.berthPref(), p.berthRequired())).toList();
                Map<Integer, Long> pidBySeq = new HashMap<>();
                Map<Integer, PassengerStatus> original = new HashMap<>();
                for (PassengerRow p : pax) {
                    pidBySeq.put(p.seqNo(), p.id());
                    original.put(p.seqNo(), statusFromText(p.bookingStatusText()));
                }

                Free free = engine.lockAndMeasure(ctx);
                Plan plan = planner.plan(specs, free.cnf(), free.racSlots(), false);
                List<Assignment> assigned = engine.execute(ctx, plan, free, pidBySeq, false, "WAITING");
                for (Assignment a : assigned) {
                    if (!a.status().atLeastAsGoodAs(original.get(a.spec().seqNo()))) {
                        throw new ReallocationWorse();
                    }
                }
                for (Assignment a : assigned) {
                    bookings.updatePassengerStatus(b.runDate(), a.passengerId(), a.status(), a.statusText());
                }
                bookings.attachPayment(b, e.paymentId());
                bookings.markCaptureApplied(b, e.paymentId());
                bookings.updateStatus(b, BookingStatus.CONFIRMED);
                outbox.append(ReservationEvents.TOPIC_BOOKING, "Booking", b.pnr(), "BookingConfirmed",
                        new BookingConfirmed(b.pnr(), b.trainRunId(), e.paymentId(), true), Map.of());
                outbox.append(ReservationEvents.TOPIC_INVENTORY, "TrainRun", Long.toString(b.trainRunId()),
                        "InventoryChanged", new InventoryChanged(b.trainRunId(), b.classCode(), b.quotaCode(),
                                b.fromSeq(), b.toSeq(), "BOOKED"), Map.of());
                return null;
            });
        } catch (ReallocationWorse | DomainException ex) {
            log.warn("late payment for PNR {} could not be re-seated as booked ({}); refunding",
                    b.pnr(), ex.getClass().getSimpleName());
            bookings.attachPayment(b, e.paymentId());
            bookings.markCaptureApplied(b, e.paymentId());
            bookings.updateStatus(b, BookingStatus.FAILED_REFUNDING);
            refund(b.pnr(), e.paymentId(), e.amount(), "LATE_PAYMENT_NO_INVENTORY");
        }
    }

    private void failAndRefund(BookingRow b, PaymentCaptured e, String reason) {
        if (b.status() == BookingStatus.PENDING_PAYMENT) {
            holdReleaser.release(b, "CANCELLED", BookingStatus.FAILED_REFUNDING, "PAYMENT_REJECTED");
        } else if (b.status().canMoveTo(BookingStatus.FAILED_REFUNDING)) {
            bookings.updateStatus(b, BookingStatus.FAILED_REFUNDING);
        }
        bookings.markCaptureApplied(b, e.paymentId());
        refund(b.pnr(), e.paymentId(), e.amount(), reason);
    }

    private void refund(String pnr, UUID paymentId, BigDecimal amount, String reason) {
        outbox.append(ReservationEvents.TOPIC_BOOKING, "Booking", pnr, "RefundRequested",
                new RefundRequested(pnr, paymentId, amount, reason, "capture-refund:" + paymentId), Map.of());
    }

    static PassengerStatus statusFromText(String text) {
        int slash = text.indexOf('/');
        return PassengerStatus.valueOf(slash < 0 ? text : text.substring(0, slash));
    }
}
