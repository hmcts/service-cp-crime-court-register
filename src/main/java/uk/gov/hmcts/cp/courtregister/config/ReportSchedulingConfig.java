package uk.gov.hmcts.cp.courtregister.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * The morning report's own schedule, on a thread nothing else in this service uses.
 *
 * <p>Seam for T045: the single-thread {@code TaskScheduler} the 07:00 run happens on and the job
 * that happens on it.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
public class ReportSchedulingConfig {

    /**
     * The bean name of the scheduler the report runs on, published so
     * {@code @Scheduled(scheduler = ...)} names a constant rather than a string spelled twice.
     */
    public static final String REPORT_SCHEDULER = "exceptionReportScheduler";
}
