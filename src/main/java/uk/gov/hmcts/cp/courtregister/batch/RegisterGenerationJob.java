package uk.gov.hmcts.cp.courtregister.batch;

import java.time.Clock;
import uk.gov.hmcts.cp.courtregister.application.RegisterGenerationService;
import uk.gov.hmcts.cp.courtregister.application.RegisterStore;
import uk.gov.hmcts.cp.courtregister.config.GenerationMetrics;
import uk.gov.hmcts.cp.courtregister.config.GenerationProperties;
import uk.gov.hmcts.cp.courtregister.domain.RunReport;

/**
 * The nightly run: flag, assemble, request, report.
 *
 * <p>The order is the whole of it. {@link FeatureFlagGate} is asked first and its answer ends the
 * run where it is not ON, because a run that assembled before it read the flag would have stamped
 * rows into batches the flag says this service may not generate (constitution Cutover Rule). Then
 * one batch at a time, sequentially, each through
 * {@link uk.gov.hmcts.cp.courtregister.application.RegisterGenerationService}; the run deadline
 * bounds the requesting and nothing else, because completion arrives on the public-event topic long
 * after the run has ended.
 *
 * <p>Every run produces a {@link RunReport}, the skipped ones included: a report that only appeared
 * when work happened would make "the flag is off" and "the job did not fire" the same silence, and
 * before cutover the first of those is every night.
 *
 * <p><strong>The schedule and the lock arrive with T050</strong>, and deliberately not before: the
 * {@code @Scheduled(cron = "0 0 18 * * MON-FRI", zone = "Europe/London")} and
 * {@code @SchedulerLock} that this class carries are themselves what
 * {@code RegisterGenerationJobTest.job_is_scheduled_in_europe_london} asserts, so a seam that
 * already wore them would be a pin that passed before anything wired it. Nothing here is annotated
 * yet, and no context creates it.
 *
 * <p><strong>Seam only.</strong> The job lands with T050; until then this throws, so that
 * {@code RegisterGenerationJobTest} records a failing assertion rather than a compile error. The
 * collaborators are already the constructor's, because a run's whole content is the order it asks
 * them in and a test that could not stand between them would have nothing to pin; none is read
 * until T050 reads them all, which is what the suppression below is and how long it lasts - the
 * eight fields are unused for exactly as long as {@link #run()} refuses, and the implementation
 * that reads them is the change that removes it.
 */
@SuppressWarnings("PMD.UnusedPrivateField")
public class RegisterGenerationJob {

    private final FeatureFlagGate gate;

    private final RegisterStore store;

    private final BatchAssembler assembler;

    private final RegisterGenerationService service;

    private final GenerationReconciler reconciler;

    private final GenerationMetrics metrics;

    private final GenerationProperties properties;

    private final Clock clock;

    /**
     * Creates the run over the collaborators it asks in order.
     *
     * @param gate       the one lever, asked first and before anything is read or assembled
     * @param store      the register store, for the records this run may batch
     * @param assembler  the grouping into one batch per court centre and register date
     * @param service    the requesting leg, asked once per batch and sequentially
     * @param reconciler the grace-period safety net under the public-event topic
     * @param metrics    the instrument surface a nightly flow is read by between runs
     * @param properties the settings the run works to, the run deadline above all
     * @param clock      the run's own clock, which the deadline and the report's duration are
     *                   measured on
     */
    public RegisterGenerationJob(final FeatureFlagGate gate, final RegisterStore store,
            final BatchAssembler assembler, final RegisterGenerationService service,
            final GenerationReconciler reconciler, final GenerationMetrics metrics,
            final GenerationProperties properties, final Clock clock) {
        this.gate = gate;
        this.store = store;
        this.assembler = assembler;
        this.service = service;
        this.reconciler = reconciler;
        this.metrics = metrics;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Runs one generation, from the flag read to the report.
     *
     * @return what the run did, including a run the flag stopped
     */
    public RunReport run() {
        throw new UnsupportedOperationException("T050");
    }
}
