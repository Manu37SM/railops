package in.railops.reservation;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Background jobs (outbox relay, sweepers, schedulers) run unless {@code railops.scheduling.enabled=false}.
 * Integration tests turn them off and invoke each job explicitly, so a test never races a timer.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "railops.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
