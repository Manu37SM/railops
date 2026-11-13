package in.railops.reservation.application.prediction;

import in.railops.reservation.adapter.out.persistence.PredictionRepository;
import in.railops.reservation.adapter.out.persistence.PredictionRepository.Calibration;
import in.railops.reservation.application.TransactionRunner;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Closes the loop: once a run is charted, attach outcomes to the predictions shown for it, then publish rolling
 * calibration over the last 30 days.
 *
 * <p>The alert metric is Brier SKILL against climatology (always predicting the observed base rate): skill below 0
 * means the model is worse than a constant, whatever the absolute Brier. That threshold needs no tuning per season,
 * unlike an absolute Brier bound copied from the (synthetic) model card.
 */
public class PredictionMonitor {

    private static final Logger log = LoggerFactory.getLogger(PredictionMonitor.class);

    private final PredictionRepository repo;
    private final TransactionRunner tx;
    private final WaitlistModel model;
    private final Clock clock;
    private final AtomicReference<Calibration> last = new AtomicReference<>(new Calibration(0, 0, 0, 0));

    public PredictionMonitor(PredictionRepository repo, TransactionRunner tx, WaitlistModel model,
                             MeterRegistry meters, Clock clock) {
        this.repo = repo;
        this.tx = tx;
        this.model = model;
        this.clock = clock;
        String v = model.version();
        Gauge.builder("prediction.wl.resolved.count", last, r -> r.get().n()).tag("model", v).register(meters);
        Gauge.builder("prediction.wl.brier", last, r -> r.get().n() == 0 ? Double.NaN : r.get().brier())
                .tag("model", v).register(meters);
        Gauge.builder("prediction.wl.brier_skill", last, r -> skill(r.get())).tag("model", v).register(meters);
        Gauge.builder("prediction.wl.calibration_gap", last,
                r -> r.get().n() == 0 ? Double.NaN : r.get().meanPredicted() - r.get().observedRate())
                .tag("model", v).register(meters);
    }

    @Scheduled(fixedDelayString = "${railops.prediction.wl.monitor-interval:PT10M}", initialDelay = 60_000)
    public void run() {
        runOnce();
    }

    public Calibration runOnce() {
        int resolved;
        int total = 0;
        do {                                           // bounded batches: short transactions, SKIP LOCKED for peers
            resolved = tx.inNewTransaction(() -> repo.resolveCharted(clock.instant(), 500));
            total += resolved;
        } while (resolved == 500 && total < 50_000);
        Calibration c = tx.inNewTransaction(() -> repo.calibration(model.version(),
                clock.instant().minus(Duration.ofDays(30))));
        last.set(c);
        if (total > 0) {
            log.info("resolved {} WL predictions; 30d n={} brier={} skill={}", total, c.n(),
                    String.format("%.4f", c.brier()), String.format("%.3f", skill(c)));
        }
        return c;
    }

    /** 1 - Brier / Brier(climatology). NaN without outcomes, or when every outcome is identical. */
    static double skill(Calibration c) {
        if (c.n() == 0) {
            return Double.NaN;
        }
        double clim = c.observedRate() * (1 - c.observedRate());
        return clim == 0 ? Double.NaN : 1 - c.brier() / clim;
    }
}
