package uk.gov.hmcts.cp.courtregister.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * The intake gauges' own refresh, on a thread of its own, wherever the intake half runs.
 *
 * <p>Seam for T046: the single-thread {@code TaskScheduler} the sweep happens on and the sweep
 * that happens on it.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
public class IntakeSweepConfig {

    /**
     * The bean name of the scheduler the sweep runs on, published for the same reason
     * {@link ReportSchedulingConfig#REPORT_SCHEDULER} is.
     */
    public static final String INTAKE_SWEEP_SCHEDULER = "intakeSweepScheduler";
}
