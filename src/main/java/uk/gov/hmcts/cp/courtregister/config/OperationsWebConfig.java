package uk.gov.hmcts.cp.courtregister.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import uk.gov.hmcts.cp.courtregister.api.OperationsActionFilter;
import uk.gov.hmcts.cp.courtregister.api.OperationsErrorAttributes;
import uk.gov.hmcts.cp.courtregister.application.BatchListingService;
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
 * <p>Registered only where the endpoints are served. {@code courtregister.operations.enabled} is
 * deployment shape and not a cutover lever (FR-044): it decides whether this service answers the
 * operator's seven paths at all, and a pod that does not answer them has no action to name. The
 * default is <strong>on</strong>, here as on {@link OperationsProperties}, so a deployment that
 * says nothing gets the filter.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "courtregister.operations", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class OperationsWebConfig {

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
    public BatchListingService batchListingService(final RegisterBatchRepository batchRepository,
            final RegisterNotificationRepository notificationRepository,
            final RegisterStore registerStore) {
        return new BatchListingService(batchRepository, notificationRepository, registerStore);
    }
}
