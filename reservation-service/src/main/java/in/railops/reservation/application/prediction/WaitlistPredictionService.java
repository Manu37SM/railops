package in.railops.reservation.application.prediction;

import tools.jackson.databind.ObjectMapper;
import in.railops.reservation.adapter.out.persistence.BookingRepository;
import in.railops.reservation.adapter.out.persistence.PredictionRepository;
import in.railops.reservation.adapter.out.persistence.PredictionRepository.PoolState;
import in.railops.reservation.application.TransactionRunner;
import in.railops.reservation.domain.error.DomainException;
import in.railops.reservation.domain.error.ErrorCode;
import in.railops.reservation.domain.policy.PnrGenerator;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Advisory waitlist-confirmation estimates. Nothing in allocation, promotion or charting reads this (enforced by
 * PredictionIsolationTest): a prediction may be wrong, the seat ledger may not.
 *
 * <p>Failure policy: a prediction is decoration. Any failure here yields "no prediction", never a failed booking
 * page, and is counted ({@code prediction.wl.errors}).
 */
public class WaitlistPredictionService {

    private static final Logger log = LoggerFactory.getLogger(WaitlistPredictionService.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    public enum Reason { NOT_WAITLISTED, NOT_COVERED, CHART_PREPARED, UNAVAILABLE }

    /**
     * {@code probability} is null when there is no estimate; {@code reason} then says why. The disclaimer is part of
     * the payload so no client can show the number without it.
     */
    public record Prediction(Double probability, String band, String modelVersion, Integer currentWlPosition,
                             Reason reason, String disclaimer) {
    }

    private final WaitlistModel model;
    private final PredictionRepository repo;
    private final BookingRepository bookings;
    private final TransactionRunner tx;
    private final ObjectMapper json;
    private final MeterRegistry meters;
    private final Clock clock;
    private final String disclaimer;

    public WaitlistPredictionService(WaitlistModel model, PredictionRepository repo, BookingRepository bookings,
                                     TransactionRunner tx, ObjectMapper json, MeterRegistry meters, Clock clock,
                                     String disclaimer) {
        this.model = model;
        this.repo = repo;
        this.bookings = bookings;
        this.tx = tx;
        this.json = json;
        this.meters = meters;
        this.clock = clock;
        this.disclaimer = disclaimer;
    }

    /** For the owner of a booking. Same 404 as GET /bookings/{pnr} for unknown and foreign PNRs. */
    public Prediction forBooking(UUID userId, String pnr) {
        if (!PnrGenerator.isValid(pnr)) {
            throw new DomainException(ErrorCode.RES_BOOKING_NOT_FOUND, "booking not found");
        }
        Instant now = clock.instant();
        // One read transaction: BookingRepository is MANDATORY-propagation (the INC-001b trap), and the queue position
        // and pool counts should come from the same snapshot.
        Scored s;
        try {
            s = tx.inNewTransaction(() -> score(userId, pnr, now));
        } catch (DomainException e) {
            throw e;                                   // 404: unknown or foreign PNR
        } catch (RuntimeException e) {
            // Caught OUTSIDE the transaction: swallowing a SQL error inside it would try to commit an aborted tx.
            meters.counter("prediction.wl.errors").increment();
            log.warn("WL prediction failed for PNR lookup: {}", e.toString());
            return none(Reason.UNAVAILABLE, null);
        }
        if (s.input() != null) {
            logQuietly(now, s.runId(), s.input().runDate(), s.bookingId(), s.input().classCode(),
                    s.input().quotaCode(), s.x(), s.p());
            meters.counter("prediction.wl.served", "source", "booking").increment();
        }
        return s.prediction();
    }

    private record Scored(Prediction prediction, WaitlistModel.Input input, long runId, long bookingId, double[] x,
                          double p) {
        static Scored none(Prediction p) {
            return new Scored(p, null, 0, 0, null, 0);
        }
    }

    private Scored score(UUID userId, String pnr, Instant now) {
        var b = bookings.findByPnr(pnr).filter(r -> r.userId().equals(userId))
                .orElseThrow(() -> new DomainException(ErrorCode.RES_BOOKING_NOT_FOUND, "booking not found"));
        {
            var queue = repo.partyQueue(b.runDate(), b.id());
            if (queue.isEmpty()) {
                return Scored.none(none(Reason.NOT_WAITLISTED, null));
            }
            Optional<PoolState> pool = repo.poolState(b.trainRunId(), b.classCode(), b.quotaCode(), now);
            if (pool.isEmpty()) {
                return Scored.none(none(Reason.UNAVAILABLE, null));
            }
            PoolState s = pool.get();
            int position = queue.get().lastPosition();
            if (!"SCHEDULED".equals(s.runStatus())) {
                return Scored.none(none(Reason.CHART_PREPARED, position));
            }
            var in = new WaitlistModel.Input(s.trainNo(), s.runDate(), b.classCode(), b.quotaCode(),
                    daysBefore(s.runDate(), now), queue.get().waitingPassengers(), s.capacity(), position,
                    s.bookingsLast7(), s.userCancels());
            if (!model.covers(in)) {
                return Scored.none(none(Reason.NOT_COVERED, position));
            }
            double[] x = model.features(in);
            double p = model.probability(x);
            return new Scored(new Prediction(round(p), model.band(p), model.version(), position, null, disclaimer),
                    in, b.trainRunId(), b.id(), x, p);
        }
    }

    /**
     * For availability: "if I book one berth now, I get WL n -- how likely is that to clear?" Public, unlogged (no
     * booking to attach an outcome to, and availability is hot-path/cached).
     */
    public Prediction forNextWaitlist(long runId, String cls, String quota) {
        try {
            Instant now = clock.instant();
            var pool = repo.poolState(runId, cls, quota, now);
            if (pool.isEmpty() || !"SCHEDULED".equals(pool.get().runStatus())) {
                return none(pool.isEmpty() ? Reason.UNAVAILABLE : Reason.CHART_PREPARED, null);
            }
            PoolState s = pool.get();
            int next = s.waiting() + 1;
            var in = new WaitlistModel.Input(s.trainNo(), s.runDate(), cls, quota, daysBefore(s.runDate(), now), 1,
                    s.capacity(), next, s.bookingsLast7(), s.userCancels());
            if (!model.covers(in)) {
                return none(Reason.NOT_COVERED, next);
            }
            double p = model.probability(model.features(in));
            meters.counter("prediction.wl.served", "source", "availability").increment();
            return new Prediction(round(p), model.band(p), model.version(), next, null, disclaimer);
        } catch (RuntimeException e) {
            meters.counter("prediction.wl.errors").increment();
            log.warn("WL availability prediction failed for run {}: {}", runId, e.toString());
            return none(Reason.UNAVAILABLE, null);
        }
    }

    public WaitlistModel model() {
        return model;
    }

    /** Days from now to 00:00 IST on the run date: the reference point the training simulation used. */
    static double daysBefore(LocalDate runDate, Instant now) {
        Duration d = Duration.between(now, runDate.atStartOfDay(IST).toInstant());
        return Math.max(0.0, d.toMillis() / 86_400_000.0);
    }

    private void logQuietly(Instant now, long runId, LocalDate runDate, long bookingId, String cls, String quota,
                            double[] x, double p) {
        try {
            Map<String, Double> f = new LinkedHashMap<>();
            for (int i = 0; i < x.length; i++) {
                f.put(WaitlistModel.SUPPORTED_FEATURES.get(i), x[i]);
            }
            String fj = json.writeValueAsString(f);
            tx.inNewTransaction(() -> repo.log(model.version(), now, runId, runDate, bookingId, cls, quota, fj, p));
        } catch (RuntimeException e) {
            // The passenger still gets the estimate; we lose one monitoring sample.
            meters.counter("prediction.wl.log_failures").increment();
        }
    }

    private Prediction none(Reason reason, Integer position) {
        return new Prediction(null, null, model.version(), position, reason, disclaimer);
    }

    private static double round(double p) {
        return Math.round(p * 100.0) / 100.0;   // two decimals: more precision than this is false precision
    }
}
