package in.railops.reservation.adapter.in.scheduler;

import in.railops.reservation.adapter.out.http.PaymentClient;
import in.railops.reservation.adapter.out.http.PaymentClient.PaymentStatus;
import in.railops.reservation.adapter.out.persistence.BookingRepository;
import in.railops.reservation.adapter.out.persistence.BookingRepository.BookingKey;
import in.railops.reservation.adapter.out.persistence.BookingRepository.ExpiredHold;
import in.railops.reservation.application.HoldReleaser;
import in.railops.reservation.application.PaymentConfirmationService;
import in.railops.reservation.contracts.PaymentEvents.PaymentCaptured;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/**
 * Expires unpaid holds, but never on the assumption that "no PaymentCaptured event yet" means "not paid".
 *
 * <p>INC-001 (Phase 13 drill): during a Kafka partition, PaymentCaptured events sat in payment-service's outbox; this
 * job expired the paid customers' holds after hold-minutes, and on a contended train their berths were resold
 * before the events arrived. One paid customer ended with a refund instead of a seat.
 *
 * <p>Now, for an expired hold with a payment attached, payment-service is asked directly (HTTP, one batch call;
 * independent of Kafka):
 * <ul>
 *   <li>CAPTURED: confirm through the same idempotent path as the event (the event, when it arrives, is a no-op);</li>
 *   <li>CREATED/PENDING: the customer may be mid-checkout (3-D Secure): wait {@code pending-grace};</li>
 *   <li>FAILED/EXPIRED/unknown id: expire;</li>
 *   <li>payment-service unreachable: do NOT expire blind; wait up to {@code unknown-grace}, then expire (a capture
 *       that still arrives later takes the late-payment path: re-allocate or refund).</li>
 * </ul>
 */
@Component
public class HoldSweeper {

    private static final Logger log = LoggerFactory.getLogger(HoldSweeper.class);

    private final HoldReleaser releaser;
    private final BookingRepository bookings;
    private final PaymentClient payments;
    private final PaymentConfirmationService confirmations;
    private final JobBulkhead bulkhead;
    private final Duration pendingGrace;
    private final Duration unknownGrace;
    private final Counter expired;
    private final Counter rescued;
    private final Counter deferred;
    private final Counter statusCheckFailed;

    public HoldSweeper(HoldReleaser releaser, BookingRepository bookings, PaymentClient payments,
                       PaymentConfirmationService confirmations, JobBulkhead bulkhead, MeterRegistry meters,
                       @Value("${railops.booking.pending-grace:PT2M}") Duration pendingGrace,
                       @Value("${railops.booking.unknown-grace:PT15M}") Duration unknownGrace) {
        this.releaser = releaser;
        this.bookings = bookings;
        this.payments = payments;
        this.confirmations = confirmations;
        this.bulkhead = bulkhead;
        this.pendingGrace = pendingGrace;
        this.unknownGrace = unknownGrace;
        this.expired = Counter.builder("booking.hold.expired").register(meters);
        this.rescued = Counter.builder("booking.hold.rescued")
                .description("expired holds confirmed via the payment status check (event path was behind)").register(meters);
        this.deferred = Counter.builder("booking.hold.expiry_deferred")
                .description("expired holds kept because payment is pending or its status is unknown").register(meters);
        this.statusCheckFailed = Counter.builder("booking.hold.status_check_failed").register(meters);
    }

    @Scheduled(fixedDelayString = "${railops.booking.sweep-ms:1000}")
    public void sweep() {
        bulkhead.run(() -> sweepOnce(Instant.now()));
    }

    /** Package-visible for tests: one pass at a given clock. */
    int sweepOnce(Instant now) {
        List<ExpiredHold> holds = bookings.expiredHolds(200);
        if (holds.isEmpty()) {
            return 0;
        }
        List<BookingKey> toExpire = new ArrayList<>();
        List<ExpiredHold> paid = new ArrayList<>();
        for (ExpiredHold h : holds) {
            if (h.paymentId() == null) {
                toExpire.add(h.key());
            } else {
                paid.add(h);
            }
        }
        Map<UUID, PaymentStatus> status = null;
        if (!paid.isEmpty()) {
            try {
                status = payments.statuses(paid.stream().map(ExpiredHold::paymentId).toList());
            } catch (RuntimeException e) {
                statusCheckFailed.increment();
                log.warn("payment status check failed for {} expired holds; not expiring them blind: {}", paid.size(), e.toString());
            }
        }
        for (ExpiredHold h : paid) {
            PaymentStatus s = status == null ? null : status.get(h.paymentId());
            Decision d = decide(h, s, status != null, now);
            switch (d) {
                case CONFIRM -> {
                    try {
                        // Deterministic id: repeated sweeps dedupe; the real Kafka event later is a no-op because
                        // the booking is already CONFIRMED with this payment id.
                        UUID eventId = UUID.nameUUIDFromBytes(("sweeper-capture:" + h.paymentId()).getBytes(StandardCharsets.UTF_8));
                        confirmations.onCaptured(eventId, new PaymentCaptured(s.paymentId(), s.pnr(), s.capturedAmount(), s.currency()));
                        rescued.increment();
                        log.info("hold {} rescued: payment {} already CAPTURED (event path behind)", h.pnr(), h.paymentId());
                    } catch (RuntimeException e) {
                        log.error("could not confirm {} from status check; will retry", h.pnr(), e);
                    }
                }
                case WAIT -> deferred.increment();
                case EXPIRE -> toExpire.add(h.key());
            }
        }
        int n = toExpire.isEmpty() ? 0 : releaser.expire(toExpire);
        expired.increment(n);
        return n;
    }

    enum Decision { CONFIRM, WAIT, EXPIRE }

    Decision decide(ExpiredHold h, PaymentStatus s, boolean statusKnown, Instant now) {
        if (!statusKnown) {
            return now.isBefore(h.holdExpiresAt().plus(unknownGrace)) ? Decision.WAIT : Decision.EXPIRE;
        }
        if (s == null) {
            return Decision.EXPIRE;                            // intent never reached payment-service
        }
        return switch (s.status()) {
            case "CAPTURED" -> Decision.CONFIRM;
            case "CREATED", "PENDING" -> now.isBefore(h.holdExpiresAt().plus(pendingGrace)) ? Decision.WAIT : Decision.EXPIRE;
            default -> Decision.EXPIRE;                        // FAILED, EXPIRED
        };
    }

    /** Caps concurrent background transactions per instance. */
    @Component
    public static class JobBulkhead {

        private final Semaphore permits;

        public JobBulkhead(@Value("${railops.jobs.max-connections:5}") int max) {
            this.permits = new Semaphore(max);
        }

        public void run(Runnable job) {
            if (!permits.tryAcquire()) {
                return; // saturated: skip this tick rather than queue behind user traffic
            }
            try {
                job.run();
            } finally {
                permits.release();
            }
        }
    }
}
