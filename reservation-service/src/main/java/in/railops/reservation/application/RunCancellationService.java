package in.railops.reservation.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.railops.common.messaging.OutboxWriter;
import in.railops.common.messaging.Topics;
import in.railops.reservation.adapter.out.persistence.AllocationRepository;
import in.railops.reservation.adapter.out.persistence.BookingRepository;
import in.railops.reservation.adapter.out.persistence.BookingRepository.BookingRow;
import in.railops.reservation.adapter.out.persistence.BookingRepository.PassengerRow;
import in.railops.reservation.adapter.out.persistence.CancellationRepository;
import in.railops.reservation.adapter.out.persistence.CancellationRepository.NewCancellation;
import in.railops.reservation.adapter.out.persistence.InventoryRepository;
import in.railops.reservation.adapter.out.persistence.WaitlistRepository;
import in.railops.reservation.contracts.ReservationEvents.RefundRequested;
import in.railops.reservation.domain.error.DomainException;
import in.railops.reservation.domain.error.ErrorCode;
import in.railops.reservation.domain.model.BookingStatus;
import in.railops.reservation.domain.model.PassengerStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * OPS-3 run cancellation. Two halves joined by Kafka:
 * <ol>
 *   <li>{@link #cancelRun}: staff action. One transaction locks ALL counters of the run, flips status to
 *       CANCELLED and emits RunCancelled to ops.disruption.v1. After this commit no new booking can succeed.</li>
 *   <li>{@link #process}: consumer of RunCancelled. Creates (or resumes) the bulk job and refunds every
 *       booking in chunks, advancing the cursor in the same transaction as each chunk.</li>
 * </ol>
 * Passengers get a FULL refund with no cancellation charge: the railway cancelled, not the passenger.
 * Each refund's idempotency key is "runcancel:" + PNR, so a crash mid-chunk can't refund anyone twice.
 */
@Service
public class RunCancellationService {

    private static final Logger log = LoggerFactory.getLogger(RunCancellationService.class);
    private static final int CHUNK = 200;
    private static final Set<String> CANCELLABLE_RUN = Set.of("SCHEDULED", "CHART_1", "CHART_2");

    public record RunCancelled(long trainRunId, String reason, String requestedBy) {
    }

    private record Job(long id, LocalDate cursorDate, long cursorId, String reason, String requestedBy) {
    }

    private final JdbcClient jdbc;
    private final InventoryRepository inventory;
    private final BookingRepository bookings;
    private final AllocationRepository allocations;
    private final WaitlistRepository waitlist;
    private final CancellationRepository cancellations;
    private final OutboxWriter outbox;
    private final TransactionRunner tx;
    private final ObjectMapper mapper;

    public RunCancellationService(JdbcClient jdbc, InventoryRepository inventory, BookingRepository bookings,
                                  AllocationRepository allocations, WaitlistRepository waitlist,
                                  CancellationRepository cancellations, OutboxWriter outbox, TransactionRunner tx,
                                  ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.inventory = inventory;
        this.bookings = bookings;
        this.allocations = allocations;
        this.waitlist = waitlist;
        this.cancellations = cancellations;
        this.outbox = outbox;
        this.tx = tx;
        this.mapper = mapper;
    }

    public void cancelRun(long runId, String reason, String actor) {
        tx.inNewTransaction(() -> {
            String status = jdbc.sql("SELECT status FROM reservation.train_run WHERE id = :id FOR UPDATE")
                    .param("id", runId).query(String.class).optional()
                    .orElseThrow(() -> new DomainException(ErrorCode.RES_RUN_NOT_FOUND, "train run not found"));
            if ("CANCELLED".equals(status)) {
                return; // idempotent
            }
            if (!CANCELLABLE_RUN.contains(status)) {
                throw new DomainException(ErrorCode.RES_RUN_NOT_BOOKABLE, "run is " + status + " and can't be cancelled");
            }
            inventory.lockAllForRun(runId);
            jdbc.sql("UPDATE reservation.train_run SET status = 'CANCELLED', version = version + 1 WHERE id = :id")
                    .param("id", runId).update();
            // Availability read model: drop the cached run status so search shows BOOKING_CLOSED immediately.
            outbox.append(in.railops.common.messaging.Topics.INVENTORY, "TrainRun", Long.toString(runId), "InventoryChanged",
                    new in.railops.reservation.contracts.ReservationEvents.InventoryChanged(runId, null, null, 0, 0, "RUN_STATUS"),
                    java.util.Map.of());
            outbox.append(Topics.DISRUPTION, "TrainRun", Long.toString(runId), "RunCancelled",
                    new RunCancelled(runId, reason, actor), Map.of());
            outbox.append(Topics.AUDIT, "AuditEvent", actor, "StaffAction",
                    Map.of("actorId", actor, "action", "CANCEL_RUN", "targetType", "TrainRun",
                            "targetId", Long.toString(runId), "occurredAt", java.time.Instant.now().toString()), Map.of());
        });
    }

    /** Consumer side. Safe to call repeatedly for the same run: resumes from the cursor, finishes once. */
    public int process(RunCancelled e) {
        Job job = tx.inNewTransaction(() -> {
            jdbc.sql("""
                            INSERT INTO reservation.bulk_job (train_run_id, kind, status, reason, requested_by)
                            VALUES (:r, 'RUN_CANCELLATION', 'RUNNING', :reason, :by)
                            ON CONFLICT (train_run_id, kind) DO NOTHING
                            """).param("r", e.trainRunId()).param("reason", e.reason()).param("by", e.requestedBy()).update();
            return jdbc.sql("""
                            SELECT id, cursor_run_date, cursor_id, reason, requested_by, status FROM reservation.bulk_job
                            WHERE train_run_id = :r AND kind = 'RUN_CANCELLATION'
                            """).param("r", e.trainRunId())
                    .query((rs, n) -> "COMPLETED".equals(rs.getString(6)) ? null
                            : new Job(rs.getLong(1), rs.getObject(2, LocalDate.class), rs.getLong(3), rs.getString(4), rs.getString(5)))
                    .optional().orElse(null);
        });
        if (job == null) {
            return 0;
        }
        int total = 0;
        while (true) {
            final Job current = job;
            int[] r = tx.inNewTransaction(() -> chunk(e.trainRunId(), current));
            total += r[0];
            if (r[0] < CHUNK) {
                break;
            }
            job = tx.inNewTransaction(() -> jdbc.sql("SELECT id, cursor_run_date, cursor_id, reason, requested_by FROM reservation.bulk_job WHERE id = :id")
                    .param("id", current.id())
                    .query((rs, n) -> new Job(rs.getLong(1), rs.getObject(2, LocalDate.class), rs.getLong(3), rs.getString(4), rs.getString(5)))
                    .single());
        }
        final long jobId = job.id();
        tx.inNewTransaction(() -> jdbc.sql("UPDATE reservation.bulk_job SET status = 'COMPLETED', completed_at = now() WHERE id = :id")
                .param("id", jobId).update());
        log.info("run {} cancellation: {} bookings processed", e.trainRunId(), total);
        return total;
    }

    /** One chunk: up to CHUNK bookings after the cursor, in (run_date, id) order. Returns {seen, refunds}. */
    private int[] chunk(long runId, Job job) {
        List<BookingRepository.BookingKey> keys = jdbc.sql("""
                        SELECT run_date, id FROM reservation.booking
                        WHERE train_run_id = :r AND (run_date, id) > (COALESCE(CAST(:d AS date), DATE '0001-01-01'), :i)
                        ORDER BY run_date, id LIMIT :n
                        """)
                .param("r", runId).param("d", job.cursorDate()).param("i", job.cursorId()).param("n", CHUNK)
                .query((rs, n) -> new BookingRepository.BookingKey(rs.getObject(1, LocalDate.class), rs.getLong(2)))
                .list();
        int refunds = 0;
        for (BookingRepository.BookingKey k : keys) {
            refunds += cancelOne(k, job) ? 1 : 0;
        }
        if (!keys.isEmpty()) {
            var last = keys.getLast();
            jdbc.sql("""
                            UPDATE reservation.bulk_job SET cursor_run_date = :d, cursor_id = :i,
                                   processed = processed + :n, refunds = refunds + :rf WHERE id = :id
                            """)
                    .param("d", last.runDate()).param("i", last.id()).param("n", keys.size()).param("rf", refunds)
                    .param("id", job.id()).update();
        }
        return new int[]{keys.size(), refunds};
    }

    private boolean cancelOne(BookingRepository.BookingKey k, Job job) {
        BookingRow b = bookings.lockById(k.runDate(), k.id()).orElse(null);
        if (b == null || !b.status().canMoveTo(BookingStatus.RUN_CANCELLED) && b.status() != BookingStatus.PENDING_PAYMENT) {
            return false; // already cancelled/expired/refunding: nothing to do
        }
        List<PassengerRow> pax = bookings.passengers(b.runDate(), b.id());
        List<Long> ids = pax.stream().map(PassengerRow::id).toList();
        // Inventory is moot on a cancelled run, but counters and allocations must stay consistent (reconciliation
        // invariant I2), so release BOTH. Lock order: booking (held) -> counters (seg order) -> allocations.
        var range = new in.railops.reservation.domain.model.SegmentRange(b.fromSeq(), b.toSeq());
        inventory.lockRange(b.trainRunId(), b.classCode(), b.quotaCode(), range);
        var released = allocations.releaseForPassengers(b.runDate(), ids, "RUN_CANCELLED");
        int full = (int) released.stream().filter(r -> !r.slot().isRac()).count();
        inventory.adjust(b.trainRunId(), b.classCode(), b.quotaCode(), range, -full, -(released.size() - full));
        waitlist.cancelOpen(b.runDate(), ids);
        for (PassengerRow p : pax) {
            if (p.status() != PassengerStatus.CAN && p.status() != PassengerStatus.CHART_CAN) {
                bookings.updatePassengerStatus(b.runDate(), p.id(), PassengerStatus.CAN, "RUN_CANCELLED");
            }
        }
        if (b.status() == BookingStatus.PENDING_PAYMENT) {
            bookings.updateStatus(b, BookingStatus.EXPIRED); // nothing was paid
            return false;
        }
        bookings.updateStatus(b, BookingStatus.RUN_CANCELLED);
        // Full refund of every passenger still travelling (earlier user cancellations were already refunded).
        long paying = pax.stream().filter(PassengerRow::berthRequired).count();
        long travelling = pax.stream().filter(p -> p.berthRequired()
                && Set.of(PassengerStatus.CNF, PassengerStatus.RAC, PassengerStatus.WL).contains(p.status())).count();
        BigDecimal per = b.totalFare().divide(BigDecimal.valueOf(Math.max(1, paying)), 2, RoundingMode.HALF_UP);
        BigDecimal refund = per.multiply(BigDecimal.valueOf(travelling));
        try {
            cancellations.insert(new NewCancellation(UUID.randomUUID(), b.runDate(), b.id(), b.pnr(),
                    pax.stream().map(PassengerRow::seqNo).toList(), "RUN_CANCELLED", refund, BigDecimal.ZERO.setScale(2),
                    refund, mapper.writeValueAsString(Map.of("reason", job.reason())), job.requestedBy()));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(ex);
        }
        if (refund.signum() > 0 && b.paymentId() != null) {
            outbox.append(Topics.BOOKING, "Booking", b.pnr(), "RefundRequested",
                    new RefundRequested(b.pnr(), b.paymentId(), refund, "RUN_CANCELLED", "runcancel:" + b.pnr()), Map.of());
        }
        outbox.append(Topics.BOOKING, "Booking", b.pnr(), "BookingRunCancelled",
                new in.railops.reservation.contracts.ReservationEvents.BookingRunCancelled(b.pnr(), b.trainRunId(), refund,
                        job.reason()), Map.of());
        return refund.signum() > 0;
    }
}
