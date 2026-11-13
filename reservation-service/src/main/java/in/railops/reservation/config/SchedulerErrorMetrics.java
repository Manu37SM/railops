package in.railops.reservation.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.task.ThreadPoolTaskSchedulerCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A scheduled job that throws is only LOGGED by Spring and runs again next tick: failing every second, forever,
 * with every health check green. INC-001b: the hold sweeper threw on every run for the whole drill and nothing
 * noticed. Every failure now increments {@code scheduled.task.failures}: alert on rate > 0 for 2 minutes.
 */
@Configuration
public class SchedulerErrorMetrics {

    private static final Logger log = LoggerFactory.getLogger(SchedulerErrorMetrics.class);

    @Bean
    ThreadPoolTaskSchedulerCustomizer schedulerErrorsToMetrics(MeterRegistry meters) {
        Counter failures = Counter.builder("scheduled.task.failures")
                .description("uncaught exceptions in @Scheduled jobs").register(meters);
        return scheduler -> scheduler.setErrorHandler(t -> {
            failures.increment();
            log.error("scheduled task failed", t);
        });
    }
}
