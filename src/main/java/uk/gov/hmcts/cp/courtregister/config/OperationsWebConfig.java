package uk.gov.hmcts.cp.courtregister.config;

import java.time.Clock;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import uk.gov.hmcts.cp.courtregister.api.OperationsActionFilter;
import uk.gov.hmcts.cp.courtregister.api.OperationsErrorAttributes;
import uk.gov.hmcts.cp.courtregister.application.BatchListingService;
import uk.gov.hmcts.cp.courtregister.application.ExceptionReportService;
import uk.gov.hmcts.cp.courtregister.application.ExceptionReportSink;
import uk.gov.hmcts.cp.courtregister.application.FeatureFlagReader;
import uk.gov.hmcts.cp.courtregister.application.OnDemandExceptionReportService;
import uk.gov.hmcts.cp.courtregister.application.OperationsSupersessionService;
import uk.gov.hmcts.cp.courtregister.application.RegisterStore;
import uk.gov.hmcts.cp.courtregister.persistence.RegisterBatchRepository;
import uk.gov.hmcts.cp.courtregister.persistence.RegisterNotificationRepository;

/**
 * What the operations API needs registered on the servlet container.
 *
 * <p>Two beans, and each is here because something about it has to be stated rather than
 * annotated. The filter's order is the first; the error attributes' replacement of Boot's own bean
 * is the second - a {@code @Component} would do it, but then the reason it exists would live
 * nowhere near the filter whose refusals it renders.
 *
 * <p>The filter's order is the whole reason it is registered here rather than
 * annotated into existence: {@link OperationsActionFilter} must run <strong>ahead of</strong>
 * {@code cp-auth-rules-filter} (which the library places at {@code HIGHEST_PRECEDENCE + 30}) and
 * of {@code cp-audit-filter-springboot} (fixed at {@code +50}), because both of them read the
 * action name this one derives (research R11).
 *
 * <p><strong>The switch is on the beans, not on the class.</strong>
 * {@code courtregister.operations.enabled} is deployment shape and not a cutover lever (FR-044):
 * it decides whether this service answers the operator's seven paths at all, and a pod that does
 * not answer them has no action to name - so the filter and the listings it would call are
 * conditional. The error attributes are <em>not</em>, because a pod with the surface switched off
 * still answers a 404 to whatever an operator tried, and Boot's own body for one echoes the path
 * they typed (FR-025, FR-027). The default is <strong>on</strong>, here as on
 * {@link OperationsProperties}, so a deployment that says nothing gets the filter.
 *
 * <p>Switching it off must never cost the pod its start-up, which is why the three controllers
 * carry the same condition: a controller left scanned over a listing that is no longer contributed
 * is an {@code UnsatisfiedDependencyException} at refresh, and a switch that crashes the pod is not
 * a switch.
 */
@Configuration(proxyBeanMethods = false)
public class OperationsWebConfig {

    /** The prefix of the one switch that decides whether this service answers /operations/**. */
    private static final String OPERATIONS = "courtregister.operations";

    /** Its name under that prefix. */
    private static final String ENABLED = "enabled";

    /** And the value that switches it on, which is also what an absent setting means. */
    private static final String ON = "true";

    /**
     * Registers the action filter first in the chain, for every request.
     *
     * <p>Mapped over everything rather than over {@code /operations/*}: the filter passes an
     * unrecognised path through untouched, and a mapping would be a second place for the list of
     * this service's paths to live and to drift from the one inside the filter.
     *
     * @return the registration, ordered ahead of both estate filters
     */
    @Bean
    @ConditionalOnProperty(prefix = OPERATIONS, name = ENABLED,
            havingValue = ON, matchIfMissing = true)
    public FilterRegistrationBean<OperationsActionFilter> operationsActionFilter() {
        final FilterRegistrationBean<OperationsActionFilter> registration =
                new FilterRegistrationBean<>(new OperationsActionFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /**
     * Replaces the body Boot writes for an error it rendered itself.
     *
     * <p>The authorisation filter refuses through {@code sendError}, which forwards to
     * {@code /error}: the 401 and the 403 are written there and not by any advice of ours. Boot's
     * default body echoes the request path, which on an unmapped path is a string the caller typed
     * (FR-025, FR-027).
     *
     * @return the bounded error body: a status, a title and a bounded reason, and nothing else
     */
    @Bean
    public OperationsErrorAttributes operationsErrorAttributes() {
        return new OperationsErrorAttributes();
    }

    /**
     * The two listings, over the three reads they are built from.
     *
     * <p>{@code @Profile("!test")} for the reason the store and its two repositories carry it in
     * {@code ProcessedLogConfig}: that profile deliberately has no database, and a listing over
     * readers that do not exist is a context that will not refresh. The condition is stated here
     * rather than taken from there because this increment does not touch that file.
     *
     * @param batchRepository        the date's batches
     * @param notificationRepository each batch's recipient rows
     * @param registerStore          the register rows behind a batch's count and the
     *                               recorded-while-off listing
     * @return the listings the two read endpoints call
     */
    @Bean
    @Profile("!test")
    @ConditionalOnProperty(prefix = OPERATIONS, name = ENABLED,
            havingValue = ON, matchIfMissing = true)
    public BatchListingService batchListingService(final RegisterBatchRepository batchRepository,
            final RegisterNotificationRepository notificationRepository,
            final RegisterStore registerStore) {
        return new BatchListingService(batchRepository, notificationRepository, registerStore);
    }

    /**
     * The exception report an operator asks for now, over the same reads the 07:00 run takes.
     *
     * <p>Behind the operations switch and the {@code !test} profile for the reason the listings
     * are: the reads it is built from are declared {@code !test}, and the switch that withdraws the
     * operator's paths must withdraw what serves them too.
     *
     * <p><strong>Not behind {@code courtregister.report.enabled}.</strong> That switch decides
     * whether the 07:00 run happens, and an incident does not wait for morning: a pod with the
     * schedule off still holds the reads and the log sink, so it can still answer what is wrong
     * with what it recorded. Nor is it behind {@code courtregister.generation.enabled}, because the
     * report is on no cutover circuit and reads the flag nowhere.
     *
     * <p>The schedule and the e-mail switch are handed in as values rather than as the properties
     * record, exactly as {@link ExceptionReportService}'s limits are: the application layer takes a
     * cron, a zone and a boolean, not the shape of a configuration file.
     *
     * @param reporting the reads and the delivery, shared with the 07:00 run
     * @param sinks     every report sink this context holds
     * @param report    the report's own settings, for the schedule and the e-mail switch
     * @param clock     the one clock the window, the snapshot and the duration are taken from
     * @return the on-demand report
     */
    @Bean
    @Profile("!test")
    @ConditionalOnProperty(prefix = OPERATIONS, name = ENABLED,
            havingValue = ON, matchIfMissing = true)
    public OnDemandExceptionReportService onDemandExceptionReportService(
            final ExceptionReportService reporting, final List<ExceptionReportSink> sinks,
            final ReportProperties report, final Clock clock) {

        return new OnDemandExceptionReportService(reporting, sinks, report.cron(), report.zone(),
                report.email().enabled(), clock);
    }

    /**
     * The rollback, with the flag read and the two bounds it is admitted under.
     *
     * <p>It takes the one lever's reader although its command took none: supersede is the endpoint
     * that gives a period of registers up, and it is admitted only while an uncached read says the
     * legacy is what generates. The reader is contributed wherever this service runs, so this bean
     * needs no condition beyond the operations switch and the {@code !test} profile the store
     * carries.
     *
     * @param registers  the registers, through the store's own port
     * @param flag       the one lever
     * @param operations the operations settings, for how far back a rollback may reach
     * @param clock      the clock both bounds are taken against
     * @return the rollback
     */
    @Bean
    @Profile("!test")
    @ConditionalOnProperty(prefix = OPERATIONS, name = ENABLED,
            havingValue = ON, matchIfMissing = true)
    public OperationsSupersessionService operationsSupersessionService(
            final RegisterStore registers, final FeatureFlagReader flag,
            final OperationsProperties operations, final Clock clock) {

        return new OperationsSupersessionService(registers, flag, operations.supersedeMaxAge(),
                clock);
    }
}
