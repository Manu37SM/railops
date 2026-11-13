package in.railops.reservation.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.railops.common.messaging.OutboxWriter;
import in.railops.reservation.adapter.out.persistence.AllocationRepository;
import in.railops.reservation.adapter.out.persistence.AllocationRepository.Released;
import in.railops.reservation.adapter.out.persistence.BookingRepository;
import in.railops.reservation.adapter.out.persistence.BookingRepository.BookingRow;
import in.railops.reservation.adapter.out.persistence.BookingRepository.PassengerRow;
import in.railops.reservation.adapter.out.persistence.CancellationRepository;
import in.railops.reservation.adapter.out.persistence.CancellationRepository.NewCancellation;
import in.railops.reservation.adapter.out.persistence.InventoryRepository;
import in.railops.reservation.adapter.out.persistence.RunRepository;
import in.railops.reservation.adapter.out.persistence.WaitlistRepository;
import in.railops.reservation.contracts.ReservationEvents;
import in.railops.reservation.contracts.ReservationEvents.InventoryChanged;
import in.railops.reservation.contracts.ReservationEvents.RefundRequested;
import in.railops.reservation.domain.error.DomainException;
import in.railops.reservation.domain.error.ErrorCode;
import in.railops.reservation.domain.model.BookingStatus;
import in.railops.reservation.domain.model.PassengerStatus;
import in.railops.reservation.domain.model.Quota;
import in.railops.reservation.domain.model.SegmentRange;
import in.railops.reservation.domain.policy.PnrGenerator;
import in.railops.reservation.domain.policy.RefundPolicy;
import in.railops.reservation.domain.policy.RefundPolicy.PassengerRefund;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Full and partial cancellation (RES-7).
 *
 * <p>Lock order (Phase 4 §6): booking row → counters (seg order) → passengers → allocations / waitlist.
 * A quote and an execution share one code path, so the refund the user is shown is exactly the refund
 * that is executed (given the same clock minute).
 */
@Service
public class CancellationService {

    public record CancellationView(UUID cancellationId, String pnr, String bookingStatus, List<Integer> cancelled,
                                   BigDecimal totalPaid, BigDecimal totalCharge, BigDecimal refundAmount,
                                   List<PassengerRefund> breakdown) {
    }

    private record Request(String pnr, List<Integer> seqs) {
    }

    private static final Set<PassengerStatus> ACTIVE = EnumSet.of(PassengerStatus.CNF, PassengerStatus.RAC,
            PassengerStatus.WL, PassengerStatus.NOSB);

    private final BookingRepository bookings;
    private final RunRepository runs;
    private final InventoryRepository inventory;
    private final AllocationRepository allocations;
    private final WaitlistRepository waitlist;
    private final CancellationRepository cancellations;
    private final HoldReleaser holdReleaser;
    private final IdempotencyGuard idempotency;
    private final OutboxWriter outbox;
    private final TransactionRunner tx;
    private final RefundPolicy refundPolicy;
    private final ObjectMapper mapper;

    public CancellationService(BookingRepository bookings, RunRepository runs, InventoryRepository inventory,
                               AllocationRepository allocations, WaitlistRepository waitlist,
                               CancellationRepository cancellations, HoldReleaser holdReleaser,
                               IdempotencyGuard idempotency, OutboxWriter outbox, TransactionRunner tx,
                               RefundPolicy refundPolicy, ObjectMapper mapper) {
        this.bookings = bookings;
        this.runs = runs;
        this.inventory = inventory;
        this.allocations = allocations;
        this.waitlist = waitlist;
        this.cancellations = cancellations;
        this.holdReleaser = holdReleaser;
        this.idempotency = idempotency;
        this.outbox = outbox;
        this.tx = tx;
        this.refundPolicy = refundPolicy;
        this.mapper = mapper;
    }

    /** Read-only preview of what a cancellation would refund right now. */
    public CancellationView quote(UUID userId, String pnr, List<Integer> seqs) {
        return tx.inNewTransaction(() -> {
            BookingRow b = owned(userId, pnr, false);
            List<PassengerRow> pax = bookings.passengers(b.runDate(), b.id());
            List<PassengerRow> selected = select(pax, seqs);
            List<PassengerRefund> refunds = computeRefunds(b, pax, selected);
            return view(null, b, b.status().name(), selected, refunds);
        });
    }

    public CancellationView cancel(UUID userId, String idempotencyKey, String pnr, List<Integer> seqs) {
        return tx.inNewTransaction(() -> {
            var replay = idempotency.claimOrReplay(userId, idempotencyKey, "cancel",
                    new Request(pnr, seqs == null ? List.of() : List.copyOf(new TreeSet<>(seqs))), CancellationView.class);
            if (replay.isPresent()) {
                return replay.get();
            }
            BookingRow b = owned(userId, pnr, true);
            List<PassengerRow> pax = bookings.passengers(b.runDate(), b.id());
            List<PassengerRow> selected = select(pax, seqs);
            UUID cancellationId = UUID.randomUUID();
            CancellationView result;

            if (b.status() == BookingStatus.PENDING_PAYMENT) {
                // Abandoned checkout: nothing was paid. Only whole-booking cancellation makes sense.
                if (selected.size() != pax.size()) {
                    throw invalid("an unpaid booking can only be cancelled as a whole");
                }
                holdReleaser.release(b, "CANCELLED", BookingStatus.CANCELLED, "CAN");
                result = view(cancellationId, b, BookingStatus.CANCELLED.name(), selected,
                        selected.stream().map(p -> new PassengerRefund(p.seqNo(), BigDecimal.ZERO.setScale(2),
                                BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2), "UNPAID")).toList());
                record(cancellationId, b, result, "ABANDONED", userId);
            } else {
                List<PassengerRefund> refunds = computeRefunds(b, pax, selected);
                SegmentRange range = new SegmentRange(b.fromSeq(), b.toSeq());
                inventory.lockRange(b.trainRunId(), b.classCode(), b.quotaCode(), range);
                List<Long> ids = selected.stream().map(PassengerRow::id).toList();
                List<Released> released = allocations.releaseForPassengers(b.runDate(), ids, "CANCELLED");
                int full = (int) released.stream().filter(r -> !r.slot().isRac()).count();
                int rac = released.size() - full;
                inventory.adjust(b.trainRunId(), b.classCode(), b.quotaCode(), range, -full, -rac);
                waitlist.cancelOpen(b.runDate(), ids);
                for (PassengerRow p : selected) {
                    bookings.updatePassengerStatus(b.runDate(), p.id(), PassengerStatus.CAN, "CAN");
                }
                boolean all = pax.stream().filter(p -> ACTIVE.contains(p.status())).count() == selected.size();
                BookingStatus next = all ? BookingStatus.CANCELLED : BookingStatus.PARTIALLY_CANCELLED;
                bookings.updateStatus(b, next);
                result = view(cancellationId, b, next.name(), selected, refunds);
                record(cancellationId, b, result, "USER", userId);

                if (full + rac > 0) {
                    outbox.append(ReservationEvents.TOPIC_INVENTORY, "TrainRun", Long.toString(b.trainRunId()),
                            "InventoryChanged", new InventoryChanged(b.trainRunId(), b.classCode(), b.quotaCode(),
                                    b.fromSeq(), b.toSeq(), "CANCELLED"), Map.of());
                }
                if (result.refundAmount().signum() > 0 && b.paymentId() != null) {
                    outbox.append(ReservationEvents.TOPIC_BOOKING, "Booking", b.pnr(), "RefundRequested",
                            new RefundRequested(b.pnr(), b.paymentId(), result.refundAmount(), "USER_CANCELLATION",
                                    "cancel:" + cancellationId), Map.of());
                }
            }
            outbox.append(ReservationEvents.TOPIC_BOOKING, "Booking", b.pnr(), "BookingCancelled",
                    new ReservationEvents.BookingCancelled(b.pnr(), cancellationId, result.cancelled(),
                            result.refundAmount(), result.bookingStatus()), Map.of());
            idempotency.complete(userId, idempotencyKey, 200, result);
            return result;
        });
    }

    // ------------------------------------------------------------------ internals

    private BookingRow owned(UUID userId, String pnr, boolean lock) {
        if (!PnrGenerator.isValid(pnr)) {
            throw new DomainException(ErrorCode.RES_BOOKING_NOT_FOUND, "booking not found");
        }
        BookingRow b = (lock ? bookings.lockByPnr(pnr) : bookings.findByPnr(pnr))
                .filter(row -> row.userId().equals(userId))
                .orElseThrow(() -> new DomainException(ErrorCode.RES_BOOKING_NOT_FOUND, "booking not found"));
        if (!EnumSet.of(BookingStatus.CONFIRMED, BookingStatus.PARTIALLY_CANCELLED, BookingStatus.PENDING_PAYMENT)
                .contains(b.status())) {
            throw new DomainException(ErrorCode.RES_CANCELLATION_CLOSED, "booking is " + b.status());
        }
        String runStatus = runs.find(b.trainRunId()).map(RunRepository.RunView::status).orElse("UNKNOWN");
        if (!Set.of("SCHEDULED", "CHART_1").contains(runStatus)) {
            throw new DomainException(ErrorCode.RES_CANCELLATION_CLOSED,
                    "online cancellation closed: run is " + runStatus);
        }
        if (runs.minutesToDeparture(b.trainRunId(), b.fromSeq()) <= 0) {
            throw new DomainException(ErrorCode.RES_CANCELLATION_CLOSED, "train has left the boarding station");
        }
        return b;
    }

    private List<PassengerRow> select(List<PassengerRow> pax, List<Integer> seqs) {
        List<PassengerRow> active = pax.stream().filter(p -> ACTIVE.contains(p.status())).toList();
        if (active.isEmpty()) {
            throw new DomainException(ErrorCode.RES_CANCELLATION_CLOSED, "no active passengers on this booking");
        }
        List<PassengerRow> selected;
        if (seqs == null || seqs.isEmpty()) {
            selected = active;
        } else {
            Set<Integer> wanted = new TreeSet<>(seqs);
            selected = active.stream().filter(p -> wanted.contains(p.seqNo())).toList();
            if (selected.size() != wanted.size()) {
                throw invalid("passengers " + wanted + " are not all active on this booking");
            }
        }
        // A child travelling without a berth can't be left on the ticket with no adult who has one.
        List<PassengerRow> remaining = active.stream().filter(p -> !selected.contains(p)).toList();
        boolean remainingAdults = remaining.stream().anyMatch(PassengerRow::berthRequired);
        boolean remainingChildren = remaining.stream().anyMatch(p -> !p.berthRequired());
        if (remainingChildren && !remainingAdults) {
            throw invalid("children travelling without a berth must be cancelled with the last passenger who has one");
        }
        return selected;
    }

    private List<PassengerRefund> computeRefunds(BookingRow b, List<PassengerRow> pax, List<PassengerRow> selected) {
        long paying = pax.stream().filter(PassengerRow::berthRequired).count();
        BigDecimal perPassenger = paying == 0 ? BigDecimal.ZERO
                : b.totalFare().divide(BigDecimal.valueOf(paying), 2, RoundingMode.HALF_UP);
        if (b.status() == BookingStatus.PENDING_PAYMENT) {
            perPassenger = BigDecimal.ZERO;
        }
        Duration toDeparture = Duration.ofMinutes(Math.max(0, runs.minutesToDeparture(b.trainRunId(), b.fromSeq())));
        boolean tatkal = Quota.valueOf(b.quotaCode()).isTatkal();
        List<PassengerRefund> out = new ArrayList<>();
        for (PassengerRow p : selected) {
            BigDecimal paid = p.berthRequired() ? perPassenger : BigDecimal.ZERO;
            out.add(refundPolicy.compute(p.seqNo(), p.status(), paid, b.classCode(), tatkal, toDeparture));
        }
        return out;
    }

    private CancellationView view(UUID id, BookingRow b, String status, List<PassengerRow> selected,
                                  List<PassengerRefund> refunds) {
        BigDecimal paid = refunds.stream().map(PassengerRefund::paid).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal charge = refunds.stream().map(PassengerRefund::charge).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal refund = refunds.stream().map(PassengerRefund::refund).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new CancellationView(id, b.pnr(), status, selected.stream().map(PassengerRow::seqNo).toList(),
                paid.setScale(2), charge.setScale(2), refund.setScale(2), refunds);
    }

    private void record(UUID id, BookingRow b, CancellationView v, String kind, UUID userId) {
        try {
            cancellations.insert(new NewCancellation(id, b.runDate(), b.id(), b.pnr(), v.cancelled(), kind,
                    v.totalPaid(), v.totalCharge(), v.refundAmount(), mapper.writeValueAsString(v.breakdown()),
                    userId.toString()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static DomainException invalid(String msg) {
        return new DomainException(ErrorCode.RES_INVALID_CANCELLATION, msg);
    }
}
