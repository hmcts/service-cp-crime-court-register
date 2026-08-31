package uk.gov.hmcts.cp.courtregister.application;

import uk.gov.hmcts.cp.courtregister.config.ProcessingMetrics;
import uk.gov.hmcts.cp.courtregister.domain.CompletionReason;
import uk.gov.hmcts.cp.courtregister.domain.DeliveryIdentity;
import uk.gov.hmcts.cp.courtregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.courtregister.domain.GuardDecision;
import uk.gov.hmcts.cp.courtregister.domain.ReasonCode;
import uk.gov.hmcts.cp.courtregister.domain.RunClaim;
import uk.gov.hmcts.cp.courtregister.persistence.ProcessedRequestRepository;

/**
 * The processed log's state machine: what a delivery may do, and what a run may record.
 *
 * <p>The guard decides; the listener settles and the pipeline runs. Every path returns one of four
 * decisions, so there is no way to fall off the end of a branch and leave a delivery neither run nor
 * settled — which is the silent loss this service exists to cure (constitution Principle VI).
 *
 * <p>Three properties are worth reading the code with in mind:
 *
 * <ul>
 *   <li><strong>The affected-row count is the decision.</strong> Each conditional statement is asked
 *       once and its answer is final. After a claim acquisition that affected nothing the delivery
 *       is handed back immediately — never re-read in a loop. Broker redelivery is the retry
 *       mechanism, and it already carries back-off and a delivery budget.</li>
 *   <li><strong>Claim liveness is the database's decision, not this class's.</strong> The record's
 *       expiry is read only so a log line can mention it; whether the claim may be taken is settled
 *       inside the conditional update, comparing the stored expiry against the database's own
 *       {@code now()}. Nothing here compares a JVM clock reading against a stored timestamp.</li>
 *   <li><strong>A superseded runner writes nothing.</strong> Outcome writes are predicated on the
 *       owner and the token that acquired the claim, so a runner whose claim was reclaimed while it
 *       worked affects no rows; it discards its result rather than overwriting the new owner's.</li>
 * </ul>
 */
public class IdempotencyGuard {

    private final ProcessedRequestRepository repository;
    private final ProcessingMetrics metrics;

    /**
     * Creates the guard over the processed-request log.
     *
     * @param repository the processed log's statements
     * @param metrics    the instruments a discarded outcome is counted on
     */
    public IdempotencyGuard(
            final ProcessedRequestRepository repository,
            final ProcessingMetrics metrics) {
        this.repository = repository;
        this.metrics = metrics;
    }

    /**
     * Decides what this delivery may do with the request it carries.
     *
     * @param command  the validated request
     * @param delivery the broker identity and runner identity this delivery arrived under
     * @return what the delivery may do next
     */
    public GuardDecision admit(final DistributionCommand command, final DeliveryIdentity delivery) {
        throw new UnsupportedOperationException("T018 implements the guard's state machine");
    }

    /**
     * Records a run that succeeded, under one of the five reasons a court-register run ends well.
     *
     * @param claim  the claim the run was made under
     * @param reason which of the five ways the run ended
     * @return the settlement the delivery is handed
     */
    public GuardDecision recordCompletion(final RunClaim claim, final CompletionReason reason) {
        throw new UnsupportedOperationException("T018 implements the guard's state machine");
    }

    /**
     * Records a run that failed transiently, with deliveries of this message remaining.
     *
     * @param claim  the claim the run was made under
     * @param reason the bounded reason the run failed for
     * @return the settlement the delivery is handed
     */
    public GuardDecision recordTransientFailure(final RunClaim claim, final ReasonCode reason) {
        throw new UnsupportedOperationException("T018 implements the guard's state machine");
    }

    /**
     * Records a run that failed in a way no redelivery can change, parking it at once.
     *
     * @param claim  the claim the run was made under
     * @param reason the bounded reason the run failed for
     * @return the settlement the delivery is handed
     */
    public GuardDecision recordNonTransientFailure(final RunClaim claim, final ReasonCode reason) {
        throw new UnsupportedOperationException("T018 implements the guard's state machine");
    }

    /**
     * Records a run that failed on the final permitted delivery, parking the request with the
     * identity of the delivery that exhausted it.
     *
     * @param claim  the claim the run was made under; its identity is what parks the row
     * @param reason the bounded reason the run failed for
     * @return the settlement the delivery is handed
     */
    public GuardDecision recordExhaustion(final RunClaim claim, final ReasonCode reason) {
        throw new UnsupportedOperationException("T018 implements the guard's state machine");
    }
}
