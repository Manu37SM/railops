package in.railops.reservation.adapter.in.scheduler;

import in.railops.reservation.adapter.in.scheduler.HoldSweeper.JobBulkhead;
import in.railops.reservation.application.ChartService;
import in.railops.reservation.domain.error.DomainException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Prepares due charts and resumes crashed ones. Safe on several instances: ChartService T1 locks the run
 * row, and a second instance arriving later sees the chart already IN_PROGRESS/PREPARED. Two instances
 * working the same IN_PROGRESS chart concurrently would still converge (every step is idempotent), they'd
 * just duplicate effort.
 */
@Component
public class ChartScheduler {

    private static final Logger log = LoggerFactory.getLogger(ChartScheduler.class);

    private final ChartService charts;
    private final JobBulkhead bulkhead;

    public ChartScheduler(ChartService charts, JobBulkhead bulkhead) {
        this.charts = charts;
        this.bulkhead = bulkhead;
    }

    @Scheduled(fixedDelayString = "${railops.chart.poll-ms:30000}")
    public void run() {
        bulkhead.run(() -> {
            for (long[] due : charts.dueCharts()) {
                try {
                    charts.prepare(due[0], (int) due[1], "system:chart-scheduler");
                } catch (DomainException e) {
                    log.warn("chart {} for run {} skipped: {}", due[1], due[0], e.getMessage());
                } catch (RuntimeException e) {
                    log.error("chart {} for run {} failed; will resume", due[1], due[0], e);
                }
            }
        });
    }
}
