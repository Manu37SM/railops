package in.railops.reservation.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import in.railops.common.messaging.OutboxWriter;
import in.railops.reservation.adapter.out.persistence.BookingRepository;
import in.railops.reservation.adapter.out.persistence.BookingRepository.BookingKey;
import in.railops.reservation.adapter.out.persistence.BookingRepository.BookingRow;
import in.railops.reservation.adapter.out.persistence.BookingRepository.PassengerRow;
import in.railops.reservation.adapter.out.persistence.CancellationRepository;
import in.railops.reservation.adapter.out.persistence.CancellationRepository.NewCancellation;
import in.railops.reservation.adapter.out.persistence.WaitlistRepository;
import in.railops.reservation.contracts.ReservationEvents;
import in.railops.reservation.contracts.ReservationEvents.RefundRequested;
import in.railops.reservation.domain.error.DomainException;
import in.railops.reservation.domain.error.ErrorCode;
import in.railops.reservation.domain.model.BookingStatus;
import in.railops.reservation.domain.model.PassengerStatus;
import in.railops.reservation.domain.policy.RefundPolicy;
import in.railops.reservation.domain.policy.RefundPolicy.PassengerRefund;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Chart preparation (RES-8), as a resumable sequence of short transactions rather than Phase 4's single
 * long one: holding locks on a 1,500-berth train for the whole chart would stall every other write to it.
 *
 * <pre>
 * T1  lock run row; SCHEDULED → CHART_1 (blocks new bookings); chart row IN_PROGRESS     [idempotent]
 * T2* expire unpaid holds on this run                                                    [per booking]
 * T3* final RAC/WL promotion per pool                                                    [per batch]
 * T4* chart-cancel remaining WL: whole booking if all WL, else drop WL passengers        [per booking]
 * T5  chart PREPARED + ChartPrepared event
 * </pre>
 * A crash anywhere leaves chart IN_PROGRESS; the scheduler re-runs from T1, and every step converges
 * (already-handled bookings are no longer PENDING / WAITING, so they're skipped).
 *
 * <p>Mixed PNRs (some CNF/RAC, some WL) follow DROP_WL_PASSENGERS (Phase 4 §7): I'm not certain of the
 * official rule for partly-confirmed e-tickets, so it's a named policy, not an assumption baked in.
 */
@Service
public class ChartService {

    private static final Logger log = LoggerFactory.getLogger(ChartService.class);

    public record ChartResult(long runId, int chartNo, String status, int promoted, int chartCancelledBookings,
                              int droppedWlPassengers, int expiredHolds) {
    }

    private final JdbcClient jdbc;
    private final in.railops.reservation.adapter.out.persistence.InventoryRepository inventory;
    private final BookingRepository bookings;
    private final WaitlistRepository waitlist;
    private final CancellationRepository cancellations;
    private final HoldReleaser holdReleaser;
    private final PromotionService promotions;
    private final OutboxWriter outbox;
    private final TransactionRunner tx;
    private final RefundPolicy refundPolicy;
    private final ObjectMapper mapper;

    public ChartService(JdbcClient jdbc, in.railops.reservation.adapter.out.persistence.InventoryRepository inventory,
                        BookingRepository bookings, WaitlistRepository waitlist,
                        CancellationRepository cancellations, HoldReleaser holdReleaser, PromotionService promotions,
                        OutboxWriter outbox, TransactionRunner tx, RefundPolicy refundPolicy, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.inventory = inventory;
        this.bookings = bookings;
        this.waitlist = waitlist;
        this.cancellations = cancellations;
        this.holdReleaser = holdReleaser;
        this.promotions = promotions;
        this.outbox = outbox;
        this.tx = tx;
        this.refundPolicy = refundPolicy;
        this.mapper = mapper;
    }

    public ChartResult prepare(long runId, int chartNo, String actor) {
        if (chartNo != 1 && chartNo != 2) {
            throw new DomainException(ErrorCode.RES_CHART_NOT_ALLOWED, "chart number must be 1 or 2");
        }
        // T1
        String state = tx.inNewTransaction(() -> begin(runId, chartNo));
        if ("PREPARED".equals(state)) {
            return new ChartResult(runId, chartNo, "ALREADY_PREPARED", 0, 0, 0, 0);
        }
        // T2
        int expired = 0;
        for (BookingKey k : pendingBookings(runId)) {
            Boolean done = tx.inNewTransaction(() -> bookings.lockIfPendingOnRun(k.runDate(), k.id()).map(b -> {
                holdReleaser.release(b, "HOLD_EXPIRED", BookingStatus.EXPIRED, "EXPIRED_AT_CHART");
                return true;
            }).orElse(false));
            expired += Boolean.TRUE.equals(done) ? 1 : 0;
        }
        // T3
        int promoted = 0;
        for (String[] pool : pools(runId)) {
            promoted += promotions.promoteAll(runId, pool[0], pool[1], true);
        }
        // T4 (only chart 1 has WL left to settle; chart 2 is a re-promotion after post-chart cancellations)
        int cancelledBookings = 0;
        int dropped = 0;
        if (chartNo == 1) {
            for (BookingKey k : waitlist.bookingsWithWaiting(runId, 10_000)) {
                int[] r = tx.inNewTransaction(() -> settleWaitlisted(k, actor));
                cancelledBookings += r[0];
                dropped += r[1];
            }
        }
        // T5
        final int p = promoted;
        final int c = cancelledBookings;
        final int d = dropped;
        final int e = expired;
        tx.inNewTransaction(() -> {
            jdbc.sql("UPDATE reservation.chart SET status = 'PREPARED', prepared_at = now() WHERE train_run_id = :r AND chart_no = :n")
                    .param("r", runId).param("n", chartNo).update();
            outbox.append(ReservationEvents.TOPIC_BOOKING, "TrainRun", Long.toString(runId), "ChartPrepared",
                    Map.of("trainRunId", runId, "chartNo", chartNo, "promoted", p, "chartCancelledBookings", c,
                            "droppedWlPassengers", d, "expiredHolds", e, "preparedBy", actor), Map.of());
        });
        log.info("chart {} prepared for run {}: promoted={}, chart-cancelled={}, dropped WL={}, expired holds={}",
                chartNo, runId, p, c, d, e);
        return new ChartResult(runId, chartNo, "PREPARED", p, c, d, e);
    }

    /** T1. Returns the chart row's status after this step. */
    private String begin(long runId, int chartNo) {
        String status = jdbc.sql("SELECT status FROM reservation.train_run WHERE id = :id FOR UPDATE")
                .param("id", runId).query(String.class).optional()
                .orElseThrow(() -> new DomainException(ErrorCode.RES_RUN_NOT_FOUND, "train run not found"));
        String existing = jdbc.sql("SELECT status FROM reservation.chart WHERE train_run_id = :r AND chart_no = :n")
                .param("r", runId).param("n", chartNo).query(String.class).optional().orElse(null);
        if ("PREPARED".equals(existing)) {
            return "PREPARED";
        }
        String required = chartNo == 1 ? "SCHEDULED" : "CHART_1";
        String target = "CHART_" + chartNo;
        if (existing == null) {
            if (!required.equals(status)) {
                throw new DomainException(ErrorCode.RES_CHART_NOT_ALLOWED, "run is " + status + "; chart " + chartNo
                        + " requires " + required);
            }
            if (chartNo == 2) {
                String c1 = jdbc.sql("SELECT status FROM reservation.chart WHERE train_run_id = :r AND chart_no = 1")
                        .param("r", runId).query(String.class).optional().orElse("");
                if (!"PREPARED".equals(c1)) {
                    throw new DomainException(ErrorCode.RES_CHART_NOT_ALLOWED, "chart 1 is not prepared yet");
                }
            }
            inventory.lockAllForRun(runId); // see InventoryRepository#lockAllForRun: no booking may straddle the flip
            jdbc.sql("INSERT INTO reservation.chart (train_run_id, chart_no, status) VALUES (:r, :n, 'IN_PROGRESS')")
                    .param("r", runId).param("n", chartNo).update();
            jdbc.sql("UPDATE reservation.train_run SET status = :s, version = version + 1 WHERE id = :id")
                    .param("s", target).param("id", runId).update();
            // Availability read model: drop the cached run status so search shows BOOKING_CLOSED immediately.
            outbox.append(in.railops.common.messaging.Topics.INVENTORY, "TrainRun", Long.toString(runId), "InventoryChanged",
                    new in.railops.reservation.contracts.ReservationEvents.InventoryChanged(runId, null, null, 0, 0, "RUN_STATUS"),
                    java.util.Map.of());
        }
        return "IN_PROGRESS"; // fresh start, or resuming a crashed run
    }

    /** T4 for one booking: returns {bookingsChartCancelled, wlPassengersDropped}. */
    private int[] settleWaitlisted(BookingKey k, String actor) {
        BookingRow b = bookings.lockById(k.runDate(), k.id()).orElse(null);
        if (b == null || !(b.status() == BookingStatus.CONFIRMED || b.status() == BookingStatus.PARTIALLY_CANCELLED)) {
            return new int[]{0, 0};
        }
        List<PassengerRow> pax = bookings.passengers(b.runDate(), b.id());
        List<PassengerRow> wl = pax.stream().filter(p -> p.status() == PassengerStatus.WL).toList();
        if (wl.isEmpty()) {
            return new int[]{0, 0};
        }
        boolean othersTravel = pax.stream().anyMatch(p -> p.status() == PassengerStatus.CNF || p.status() == PassengerStatus.RAC);
        // Children without berths go with the WL adults if nobody with a berth is travelling.
        List<PassengerRow> leaving = new ArrayList<>(wl);
        if (!othersTravel) {
            pax.stream().filter(p -> p.status() == PassengerStatus.NOSB).forEach(leaving::add);
        }
        long paying = pax.stream().filter(PassengerRow::berthRequired).count();
        BigDecimal perPassenger = b.totalFare().divide(BigDecimal.valueOf(Math.max(1, paying)), 2, RoundingMode.HALF_UP);
        List<PassengerRefund> refunds = new ArrayList<>();
        for (PassengerRow p : leaving) {
            refunds.add(refundPolicy.compute(p.seqNo(), p.status(), p.berthRequired() ? perPassenger : BigDecimal.ZERO,
                    b.classCode(), false, Duration.ofDays(1)));
            bookings.updatePassengerStatus(b.runDate(), p.id(), PassengerStatus.CHART_CAN, "CHART_CAN");
        }
        waitlist.expireAtChart(b.runDate(), leaving.stream().map(PassengerRow::id).toList());
        BookingStatus next = othersTravel ? BookingStatus.PARTIALLY_CANCELLED : BookingStatus.CHART_CANCELLED;
        bookings.updateStatus(b, next);

        BigDecimal paid = refunds.stream().map(PassengerRefund::paid).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal charge = refunds.stream().map(PassengerRefund::charge).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal refund = paid.subtract(charge);
        UUID id = UUID.randomUUID();
        try {
            cancellations.insert(new NewCancellation(id, b.runDate(), b.id(), b.pnr(),
                    leaving.stream().map(PassengerRow::seqNo).toList(), "CHART", paid, charge, refund,
                    mapper.writeValueAsString(refunds), actor));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        if (refund.signum() > 0 && b.paymentId() != null) {
            // Key by PNR+chart, not by the random cancellation id: a resumed chart must not refund twice.
            outbox.append(ReservationEvents.TOPIC_BOOKING, "Booking", b.pnr(), "RefundRequested",
                    new RefundRequested(b.pnr(), b.paymentId(), refund, "CHART_WAITLIST", "chart1:" + b.pnr()), Map.of());
        }
        outbox.append(ReservationEvents.TOPIC_BOOKING, "Booking", b.pnr(), "WaitlistChartCancelled",
                new ReservationEvents.WaitlistChartCancelled(b.pnr(), leaving.stream().map(PassengerRow::seqNo).toList(),
                        refund, next.name()), Map.of());
        return new int[]{othersTravel ? 0 : 1, wl.size()};
    }

    private List<BookingKey> pendingBookings(long runId) {
        return jdbc.sql("SELECT run_date, id FROM reservation.booking WHERE train_run_id = :r AND status = 'PENDING_PAYMENT'")
                .param("r", runId)
                .query((rs, n) -> new BookingKey(rs.getObject(1, java.time.LocalDate.class), rs.getLong(2))).list();
    }

    private List<String[]> pools(long runId) {
        return jdbc.sql("SELECT DISTINCT class_code, quota_code FROM reservation.segment_inventory WHERE train_run_id = :r ORDER BY 1, 2")
                .param("r", runId).query((rs, n) -> new String[]{rs.getString(1), rs.getString(2)}).list();
    }

    /** Runs whose chart is due, or whose chart crashed mid-way (IN_PROGRESS for > 5 min). */
    public List<long[]> dueCharts() {
        return jdbc.sql("""
                        SELECT id, 1 FROM reservation.train_run WHERE status = 'SCHEDULED' AND chart1_due_at <= now()
                        UNION
                        SELECT r.id, 2 FROM reservation.train_run r
                        WHERE r.status = 'CHART_1' AND r.chart2_due_at <= now()
                          AND NOT EXISTS (SELECT 1 FROM reservation.chart c WHERE c.train_run_id = r.id AND c.chart_no = 2)
                        UNION
                        SELECT train_run_id, chart_no FROM reservation.chart
                        WHERE status = 'IN_PROGRESS' AND started_at < now() - interval '5 minutes'
                        LIMIT 50
                        """)
                .query((rs, n) -> new long[]{rs.getLong(1), rs.getLong(2)}).list();
    }
}
