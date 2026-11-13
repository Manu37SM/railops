package in.railops.identity;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Background jobs (audit anchoring) unless {@code railops.scheduling.enabled=false} (tests call them explicitly). */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "railops.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
