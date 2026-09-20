package uk.gov.hmcts.cp.courtregister.config;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import uk.gov.hmcts.cp.courtregister.application.RegisterGenerationService;
import uk.gov.hmcts.cp.courtregister.application.RegisterStore;
import uk.gov.hmcts.cp.courtregister.batch.BatchAssembler;
import uk.gov.hmcts.cp.courtregister.batch.FeatureFlagGate;
import uk.gov.hmcts.cp.courtregister.batch.GenerationReconciler;
import uk.gov.hmcts.cp.courtregister.batch.RegisterGenerationJob;
import uk.gov.hmcts.cp.courtregister.batch.StaleBatchReleaser;

/**
 * The nightly run's own executor, and the run itself.
 *
 * <p>What makes it one run rather than one run per replica is ShedLock, and what makes
 * {@code @Scheduled} mean anything at all is {@code @EnableScheduling} - both of which now live in
 * {@link SchedulingInfrastructureConfig}, because a pod that generates nothing still has a gauge
 * refresh to run and a report to write. What is left here is what is genuinely the downstream
 * half's: the thread the 18:00 run happens on, and the job that happens on it. The service deploys
 * with a single replica today, so the lock is insurance rather than a fix: relying on
 * {@code replicas: 1} is a deployment fact and not a code guarantee, and the cost of being wrong is
 * two documents and two e-mails for every court centre in the country.
 *
 * <p>The zone the schedule is read in is validated by {@link GenerationProperties#validate()},
 * because 18:00 is a wall-clock requirement that has to hold in BST and in GMT alike. The legacy
 * fires in the scheduling JVM's default zone, its Quartz trigger having been built without one, and
 * that ambiguity is not inherited: an override needs
 * {@code courtregister.generation.zone-override-acknowledged}, and startup refuses without it.
 *
 * <p><strong>The run has an executor of its own.</strong> There are three schedulers on a fully
 * enabled context now - this one, the report's and the intake sweep's - and each scheduled method
 * names the one it belongs on, so the run still cannot land on a thread anything else is using. One
 * thread, because the run is sequential by design and a pool would only make it look otherwise. The
 * grace-period reconciler shares this scheduler and always has; what changed is that it says so.
 *
 * <p><strong>Only where the downstream half is deployed.</strong> The whole of this is conditional
 * on {@code courtregister.generation.enabled}, so an intake-only pod holds no lock, keeps no
 * scheduler and fires nothing; the job itself is contributed only where the collaborators it asks in
 * order are on the context, for the reason {@link PublicEventsConfig} gives about the listener - a
 * schedule with nothing to run is a fire alarm nobody wired to anything.
 *
 * <p><strong>And not on a JVM started to run one operations command.</strong> The lock makes the
 * 18:00 run one run, so a CLI process holding a scheduler would be a second replica of it, and a
 * command running long enough to reach 18:00 London would generate the night twice. The condition
 * stays on the whole configuration rather than on the job alone: a scheduler with nothing on it is
 * a half-absence to reason about instead of a plain one. The claim that a command schedules
 * <em>nothing</em> now rests on {@link SchedulingInfrastructureConfig}, which owns the annotation
 * and carries the same condition ({@link CliModeConfig}).
 */
@Configuration(proxyBeanMethods = false)
@Profile("!test")
@ConditionalOnProperty(prefix = "courtregister.generation", name = "enabled", havingValue = "true")
@Conditional(CliModeConfig.NotCliMode.class)
public class SchedulingConfig {

    /**
     * The bean name of the scheduler the two generation surfaces run on.
     *
     * <p>The name of the bean {@link #registerGenerationScheduler()} already declares, published so
     * that {@code @Scheduled(scheduler = ...)} on {@code RegisterGenerationJob.run} and
     * {@code GenerationReconciler.reconcileScheduled} names a constant rather than a string spelled
     * twice. No bean is added, renamed or moved by it: the two surfaces go on sharing one scheduler
     * exactly as they do today, and only the routing stops being implicit.
     */
    public static final String GENERATION_SCHEDULER = "registerGenerationScheduler";

    /** The one thread the run has, and the prefix its name is read by in a thread dump. */
    private static final String RUN_THREAD_PREFIX = "register-generation-";

    private static final Logger LOG = LoggerFactory.getLogger(SchedulingConfig.class);

    /**
     * The executor the run and the grace-period reconciler happen on.
     *
     * <p>Named by {@link #GENERATION_SCHEDULER}, which both of their {@code @Scheduled} methods
     * carry: with three schedulers on the context, a method that named none would be routed to
     * whichever one Spring resolved for the context as a whole.
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
        // A seam, not the wiring: the pass is built here from the store and the two durations the
        // run already holds, so that the job can call it before the bean that will own it exists
        // (T020 promotes this to a bean of its own and drops the reconciler from the check).
        final StaleBatchReleaser releaser = complete
                ? new StaleBatchReleaser(store, metrics, properties.staleAfter(),
                        properties.lockAtMostFor(), clock)
                : null;
        if (!complete) {
            LOG.warn("The downstream half is enabled but incomplete on this context, so no "
                    + "generation run is scheduled: a job that could not read the flag, assemble a "
                    + "batch or ask for a render would report a quiet night rather than a missing "
                    + "one. gate={} store={} assembler={} service={} reconciler={}", gate != null,
                    store != null, assembler != null, service != null, reconciler != null);
        }
        return complete
                ? new RegisterGenerationJob(gate, store, assembler, service, releaser, metrics,
                        properties, clock, runProgress)
                : null;
    }
}
