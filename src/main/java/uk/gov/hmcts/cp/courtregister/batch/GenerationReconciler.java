package uk.gov.hmcts.cp.courtregister.batch;

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
 * batch that is retried indefinitely is a night's registers nobody is told are missing.
 *
 * <p>The count is the broker's health seen from here: a run whose outcomes all arrive by
 * reconciliation is a subscription to investigate, and the {@code reconciled} metric and the run
 * report are where that shows.
 *
 * <p><strong>Seam only.</strong> The reconciler lands with T048; until then this throws, so that
 * {@code GenerationReconcilerTest} records a failing assertion rather than a compile error.
 */
public class GenerationReconciler {

    /**
     * Asks about every batch whose grace period has passed and applies what comes back.
     *
     * @return how many outcomes had to be fetched rather than received, for the run report and the
     *     {@code reconciled} counter
     */
    public int reconcile() {
        throw new UnsupportedOperationException("T048");
    }
}
