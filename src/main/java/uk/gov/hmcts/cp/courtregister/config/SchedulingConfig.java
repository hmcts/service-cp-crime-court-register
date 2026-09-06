package uk.gov.hmcts.cp.courtregister.config;

import java.time.Clock;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import uk.gov.hmcts.cp.courtregister.application.RegisterGenerationService;
import uk.gov.hmcts.cp.courtregister.application.RegisterStore;
import uk.gov.hmcts.cp.courtregister.batch.BatchAssembler;
import uk.gov.hmcts.cp.courtregister.batch.FeatureFlagGate;
import uk.gov.hmcts.cp.courtregister.batch.GenerationReconciler;
import uk.gov.hmcts.cp.courtregister.batch.RegisterGenerationJob;

/**
 * What makes the nightly job one run rather than one run per replica, and what fires it.
 *
 * <p>ShedLock over the service's own Postgres, against the {@code shedlock} table V2 creates. The
 * service deploys with a single replica today, so this is insurance rather than a fix: relying on
 * {@code replicas: 1} is a deployment fact and not a code guarantee, and the cost of being wrong is
 * two documents and two e-mails for every court centre in the country. The lock is taken on the
 * database's own clock rather than on the pods', because two JVMs a few seconds apart is exactly the
 * skew a lock is supposed to survive.
 *
 * <p>The zone the schedule is read in is validated by {@link GenerationProperties#validate()},
 * because 18:00 is a wall-clock requirement that has to hold in BST and in GMT alike. The legacy
 * fires in the scheduling JVM's default zone, its Quartz trigger having been built without one, and
 * that ambiguity is not inherited: an override needs
 * {@code courtregister.generation.zone-override-acknowledged}, and startup refuses without it.
 *
 * <p><strong>The run has an executor of its own.</strong> A scheduler this configuration declares is
 * the only one on the context, so the run cannot land on a thread anything else is using - a
 * listener's above all, since a delivery being recorded and a night being generated are the two
 * things this service must be able to do at once. One thread, because the run is sequential by
 * design and a pool would only make it look otherwise.
 *
 * <p><strong>Only where the downstream half is deployed.</strong> The whole of this is conditional
 * on {@code courtregister.generation.enabled}, so an intake-only pod holds no lock, keeps no
 * scheduler and fires nothing; the job itself is contributed only where the collaborators it asks in
 * order are on the context, for the reason {@link PublicEventsConfig} gives about the listener - a
 * schedule with nothing to run is a fire alarm nobody wired to anything.
 */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
@ConditionalOnProperty(prefix = "courtregister.generation", name = "enabled", havingValue = "true")
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = RegisterGenerationJob.LOCK_AT_MOST_FOR)
public class SchedulingConfig {

    /** The table V2 creates for the lock, named here because the provider will not guess it. */
    private static final String SHEDLOCK_TABLE = "shedlock";

    /** The one thread the run has, and the prefix its name is read by in a thread dump. */
    private static final String RUN_THREAD_PREFIX = "register-generation-";

    private static final Logger LOG = LoggerFactory.getLogger(SchedulingConfig.class);

    /**
     * The lock the job holds while it runs.
     *
     * @param dataSource the processed log's own datasource, which is where {@code shedlock} is
     * @return the JDBC lock provider, taking the lock on the database's clock
     */
    @Bean
    public LockProvider lockProvider(final DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .withTableName(SHEDLOCK_TABLE)
                .usingDbTime()
                .build());
    }

    /**
     * The executor the run happens on, and the only scheduler this service has.
     *
     * @return a single-threaded scheduler named for the run it carries
     */
    @Bean
    public TaskScheduler registerGenerationScheduler() {
        final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix(RUN_THREAD_PREFIX);
        // A run that is still requesting when the pod is asked to stop finishes the batch it is on:
        // the file-service write and the POST that follows it are the pair that must not be halved.
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        return scheduler;
    }

    /**
     * The nightly run, over the collaborators it asks in order.
     *
     * <p>Declared here rather than annotated as a component, and tolerant of a context that holds
     * only some of the downstream half: the generating adapters are contributed by their own
     * configurations and a deployment that is missing one has a run that could not ask for a render
     * anyway. Where that is so the schedule is left empty and the reason is said out loud, which is
     * the shape {@link PublicEventsConfig} uses for the same situation on the listener.
     *
     * @param gates       the one lever's gate, asked first by every run
     * @param stores      the register store, for the records a run may batch
     * @param assemblers  the grouping into one batch per court centre and register date
     * @param services    the requesting leg, asked once per batch
     * @param reconcilers the grace-period safety net under the public-event topic
     * @param metrics     the downstream half's instruments
     * @param properties  the settings the run works to
     * @param clock       this pod's reading of now
     * @param runProgress what the run tells readiness while it is in progress
     * @return the job, or {@code null} where this context could not generate anything
     */
    @Bean
    public RegisterGenerationJob registerGenerationJob(
            final ObjectProvider<FeatureFlagGate> gates,
            final ObjectProvider<RegisterStore> stores,
            final ObjectProvider<BatchAssembler> assemblers,
            final ObjectProvider<RegisterGenerationService> services,
            final ObjectProvider<GenerationReconciler> reconcilers,
            final GenerationMetrics metrics,
            final GenerationProperties properties,
            final Clock clock,
            final RunProgress runProgress) {

        final FeatureFlagGate gate = gates.getIfAvailable();
        final RegisterStore store = stores.getIfAvailable();
        final BatchAssembler assembler = assemblers.getIfAvailable();
        final RegisterGenerationService service = services.getIfAvailable();
        final GenerationReconciler reconciler = reconcilers.getIfAvailable();

        final boolean complete = gate != null && store != null && assembler != null
                && service != null && reconciler != null;
        if (!complete) {
            LOG.warn("The downstream half is enabled but incomplete on this context, so no "
                    + "generation run is scheduled: a job that could not read the flag, assemble a "
                    + "batch or ask for a render would report a quiet night rather than a missing "
                    + "one. gate={} store={} assembler={} service={} reconciler={}", gate != null,
                    store != null, assembler != null, service != null, reconciler != null);
        }
        return complete
                ? new RegisterGenerationJob(gate, store, assembler, service, reconciler, metrics,
                        properties, clock, runProgress)
                : null;
    }
}
