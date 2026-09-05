package uk.gov.hmcts.cp.courtregister.config;

import java.time.Duration;
import uk.gov.hmcts.cp.courtregister.domain.BatchStatus;
import uk.gov.hmcts.cp.courtregister.domain.FlagDecision;
import uk.gov.hmcts.cp.courtregister.domain.NotificationStatus;

/**
 * The instrument surface of the downstream half, declared in one place.
 *
 * <p>The counterpart of {@link ProcessingMetrics}, under the same two rules. Names and label sets
 * are fixed so the tests assert on them and the alert rules written later have a stable surface to
 * fire on; labels are low-cardinality enumerations only, so a batch id, a court centre id or a
 * recipient's address is never a series - that is both a cardinality explosion and, on a register
 * whose every defendant is a youth, a privacy breach.
 *
 * <p>Two of these answer questions nothing else in the flow can. A skipped run is counted by the
 * reason it was skipped, because "the flag is off" and "the flag could not be read" look identical
 * from outside and are not the same night; and a reconciled completion is counted separately from an
 * ordinary one, because a run whose outcomes all arrive by reconciliation is a broker to look at
 * rather than a renderer.
 *
 * <p><strong>Seam.</strong> Every method below is completed by T017, whose green run is
 * {@code GenerationMetricsTest} (T010) - which is also where the instrument names and tags are
 * pinned.
 */
public class GenerationMetrics {

    /** The task that replaces every refusal in this class with an instrument. */
    private static final String PENDING_TASK =
            "T017 implements GenerationMetrics; GenerationMetricsTest (T010) guards it";

    /**
     * Counts a batch that reached a terminal state.
     *
     * @param outcome the state it ended in
     */
    public void batchCompleted(final BatchStatus outcome) {
        throw new UnsupportedOperationException(PENDING_TASK);
    }

    /**
     * Counts a render request by what systemdocgenerator answered.
     *
     * @param responseCode the status line, which is a bounded dimension and the only one
     */
    public void generationRequested(final int responseCode) {
        throw new UnsupportedOperationException(PENDING_TASK);
    }

    /**
     * Records how long a batch took from render request to outcome.
     *
     * @param latency request to outcome, however the outcome arrived
     */
    public void generationLatency(final Duration latency) {
        throw new UnsupportedOperationException(PENDING_TASK);
    }

    /**
     * Counts an outcome the grace-period reconciler had to fetch rather than receive.
     */
    public void reconciled() {
        throw new UnsupportedOperationException(PENDING_TASK);
    }

    /**
     * Counts a run that read the flag and did not generate.
     *
     * @param decision what the flag said, whose bounded code is the reason label
     */
    public void runSkipped(final FlagDecision decision) {
        throw new UnsupportedOperationException(PENDING_TASK);
    }

    /**
     * Counts one recipient's e-mail by how it was settled.
     *
     * @param status       ACCEPTED or FAILED
     * @param responseCode the status notificationnotify answered with, or {@code null} where nothing
     *                     answered at all
     */
    public void notificationSettled(final NotificationStatus status, final Integer responseCode) {
        throw new UnsupportedOperationException(PENDING_TASK);
    }
}
