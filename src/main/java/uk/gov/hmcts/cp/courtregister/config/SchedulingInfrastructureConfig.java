package uk.gov.hmcts.cp.courtregister.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * What makes {@code @Scheduled} mean anything on this JVM, and the lock the locked ones take.
 *
 * <p>Seam for the split T043 finishes: the annotation, the lock configuration and the one
 * {@code LockProvider} move here out of {@link SchedulingConfig}, so a pod that generates nothing
 * still processes a schedule.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
public class SchedulingInfrastructureConfig {
}
