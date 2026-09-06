package uk.gov.hmcts.cp.courtregister.batch;

import java.time.Clock;
import java.time.Duration;
import uk.gov.hmcts.cp.courtregister.application.DocumentOutcomeSink;
import uk.gov.hmcts.cp.courtregister.application.DocumentRenderer;
import uk.gov.hmcts.cp.courtregister.application.RegisterStore;
import uk.gov.hmcts.cp.courtregister.config.GenerationMetrics;
import uk.gov.hmcts.cp.courtregister.persistence.RegisterBatchRepository;

/**
 * The safety net under the public-event topic.
 *
 * <p>A batch whose {@code document-available} or {@code generation-failed} never arrived would
 * otherwise sit in GENERATING for ever: the broker is the only thing that was going to say what
 * happened, and a subscription that missed the event says nothing a second time. So a batch still
 * GENERATING past {@code courtregister.generation.grace-period} is asked about exactly once, through
 * systemdocgenerator's query API, and the answer is applied through the same
 * {@link uk.gov.hmcts.cp.courtregister.application.DocumentOutcomeSink} the listener uses, naming
 * RECONCILER rather than EVENT so the row records which mechanism learned it.
 *
 * <p>A batch the query has nothing to say about is failed GENERATION_TIMED_OUT rather than asked
 * again. Two systems have now been given the chance to report an outcome and neither has one, and a
 * batch that is retried indefinitely is a night's registers nobody is told are missing. That ending
 * goes through {@link RegisterStore#markFailed} rather than through the sink, because it is this
 * service's own verdict about a silence and not an answer anybody gave - and it still names
 * RECONCILER, which is what {@code BatchFailureReason.isGeneratorAttributed()} requires of it.
 *
 * <p>The count is the broker's health seen from here: a run whose outcomes all arrive by
 * reconciliation is a subscription to investigate, and the {@code reconciled} metric and the run
 * report are where that shows.
 *
 * <p><strong>Collaborators, and why these.</strong> The overdue read is
 * {@link RegisterBatchRepository#generatingSince}, which is a single-table read the store has no
 * part in; the question is the renderer's query method, which exists for this class and nothing
 * else; the answer goes to the sink, so that an outcome fetched here and an outcome received on the
 * topic take one code path; the silence goes to the store; and the grace period arrives as the one
 * {@link Duration} it is rather than as the whole of {@link
 * uk.gov.hmcts.cp.courtregister.config.GenerationProperties}, because a class that took the record
 * would state a dependency on ten settings it never reads. The clock is injected because the whole
 * of the rule is "how long ago was that", and a rule about elapsed time that reads the wall clock
 * cannot be asserted on either side of its own boundary.
 *
 * <p><strong>Seam only.</strong> The reconciler lands with T048; until then this throws, so that
 * {@code GenerationReconcilerTest} records a failing assertion rather than a compile error. The
 * fields are held and not yet read for the same reason, which is what the suppression below is
 * about; T048 removes it by reading them.
 */
// PMD.UnusedPrivateField: the collaborators of a seam whose one method throws are held before they
// are read, and the alternative - a constructor that discards its arguments - would leave the test
// unable to state which collaborator the reconciler is supposed to ask. Removed by T048.
@SuppressWarnings("PMD.UnusedPrivateField")
public class GenerationReconciler {

    private final RegisterBatchRepository batches;
    private final DocumentRenderer renderer;
    private final DocumentOutcomeSink sink;
    private final RegisterStore store;
    private final GenerationMetrics metrics;
    private final Duration gracePeriod;
    private final Clock clock;

    /**
     * Creates the reconciler over the batches it reads and the three collaborators it applies
     * through.
     *
     * @param batches     the {@code register_batch} table, read for the batches whose outcome is
     *                    overdue
     * @param renderer    systemdocgenerator, asked what became of a payload it was given
     * @param sink        where an answer is applied, the same one the public-event listener uses
     * @param store       where a silence is applied, as this service's own GENERATION_TIMED_OUT
     *                    verdict
     * @param metrics     where a completion this run had to fetch rather than receive is counted
     * @param gracePeriod how long a batch may stay GENERATING before it is asked about
     * @param clock       the clock the grace period is measured back from
     */
    public GenerationReconciler(final RegisterBatchRepository batches,
            final DocumentRenderer renderer, final DocumentOutcomeSink sink,
            final RegisterStore store, final GenerationMetrics metrics, final Duration gracePeriod,
            final Clock clock) {
        this.batches = batches;
        this.renderer = renderer;
        this.sink = sink;
        this.store = store;
        this.metrics = metrics;
        this.gracePeriod = gracePeriod;
        this.clock = clock;
    }

    /**
     * Asks about every batch whose grace period has passed and applies what comes back.
     *
     * @return how many batches this run completed rather than the topic: the answers it fetched and
     *     the silences it timed out, which is the same set of batches
     *     {@code register_batch.completed_by} names RECONCILER, for the run report and the
     *     {@code reconciled} counter
     */
    public int reconcile() {
        throw new UnsupportedOperationException("T048");
    }
}
