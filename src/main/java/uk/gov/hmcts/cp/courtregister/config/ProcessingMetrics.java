package uk.gov.hmcts.cp.courtregister.config;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Component;
import uk.gov.hmcts.cp.courtregister.domain.CompletionReason;
import uk.gov.hmcts.cp.courtregister.domain.DeadLetterReason;
import uk.gov.hmcts.cp.courtregister.domain.FailureClassification;
import uk.gov.hmcts.cp.courtregister.domain.RequestOutcome;
import uk.gov.hmcts.cp.courtregister.domain.SettlementOperation;
import uk.gov.hmcts.cp.courtregister.domain.TransformationAnomaly;

/**
 * The service's whole instrument surface, declared in one place.
 *
 * <p>The seam only at this point: the instruments themselves land with the implementation task this
 * signature guards.
 */
@Component
public class ProcessingMetrics {

    public static final String PROCESSED = "courtregister_processed_total";
    public static final String COMPLETIONS = "courtregister_completions_total";
    public static final String PROCESSING_FAILURES = "courtregister_processing_failures_total";
    public static final String TRANSFORMATION_ANOMALIES =
            "courtregister_transformation_anomalies_total";
    public static final String INTAKE_SUSPENSIONS = "courtregister_intake_suspensions_total";
    public static final String DEAD_LETTERED = "courtregister_deadlettered_total";
    public static final String SETTLEMENT_FAILURES = "courtregister_settlement_failures_total";
    public static final String LOCK_LOSS = "courtregister_lock_loss_total";
    public static final String STALE_RUNNER_REJECTIONS =
            "courtregister_stale_runner_rejections_total";
    public static final String INTAKE_SUSPENDED = "courtregister_intake_suspended";
    public static final String SERVICEBUS_UP = "courtregister_servicebus_up";

    public static final String OUTCOME_TAG = "outcome";
    public static final String CLASSIFICATION_TAG = "classification";
    public static final String REASON_TAG = "reason";
    public static final String OPERATION_TAG = "operation";

    private final MeterRegistry registry;

    /**
     * Creates the instrument surface over the given registry.
     *
     * @param registry the registry every instrument is registered against
     */
    public ProcessingMetrics(final MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * A request reached a terminal outcome.
     *
     * @param outcome how the request finished
     */
    public void requestSettled(final RequestOutcome outcome) {
        throw new UnsupportedOperationException("ProcessingMetrics is not implemented yet");
    }

    /**
     * A request completed, for one of the five reasons a run can end well.
     *
     * @param reason why the request completed
     */
    public void completed(final CompletionReason reason) {
        throw new UnsupportedOperationException("ProcessingMetrics is not implemented yet");
    }

    /**
     * A pipeline run failed.
     *
     * @param classification whether the failure is worth retrying
     */
    public void pipelineFailed(final FailureClassification classification) {
        throw new UnsupportedOperationException("ProcessingMetrics is not implemented yet");
    }

    /**
     * A guarded, non-fatal transformation anomaly was met and skipped.
     *
     * @param anomaly which part of the register could not be built
     */
    public void transformationAnomaly(final TransformationAnomaly anomaly) {
        throw new UnsupportedOperationException("ProcessingMetrics is not implemented yet");
    }

    /**
     * Intake moved into SUSPENDED.
     */
    public void intakeSuspended() {
        throw new UnsupportedOperationException("ProcessingMetrics is not implemented yet");
    }

    /**
     * Intake moved back into RUNNING.
     */
    public void intakeResumed() {
        throw new UnsupportedOperationException("ProcessingMetrics is not implemented yet");
    }

    /**
     * A delivery was parked on the dead-letter queue.
     *
     * @param reason why it was parked
     */
    public void deadLettered(final DeadLetterReason reason) {
        throw new UnsupportedOperationException("ProcessingMetrics is not implemented yet");
    }

    /**
     * A settlement call itself failed.
     *
     * @param operation which settlement call failed
     */
    public void settlementFailed(final SettlementOperation operation) {
        throw new UnsupportedOperationException("ProcessingMetrics is not implemented yet");
    }

    /**
     * The delivery lock was lost before settlement.
     */
    public void lockLost() {
        throw new UnsupportedOperationException("ProcessingMetrics is not implemented yet");
    }

    /**
     * An outcome write was rejected by the owner-and-token predicate.
     */
    public void staleRunnerRejected() {
        throw new UnsupportedOperationException("ProcessingMetrics is not implemented yet");
    }

    /**
     * Mirrors the Service Bus health component.
     *
     * @param up whether the broker is reachable
     */
    public void serviceBusUp(final boolean up) {
        throw new UnsupportedOperationException("ProcessingMetrics is not implemented yet");
    }

    /**
     * Points the Service Bus gauge at the component that knows the answer.
     *
     * @param liveState answers, on demand, whether the broker is reachable
     */
    public void bindServiceBusUp(final BooleanSupplier liveState) {
        throw new UnsupportedOperationException("ProcessingMetrics is not implemented yet");
    }
}
