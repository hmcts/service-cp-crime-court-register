package uk.gov.hmcts.cp.courtregister.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
 * <p>Names and label sets are fixed here so the tests assert on them and the alert rules written
 * later have a stable surface to fire on. Labels are low-cardinality enumerations only: a request
 * id, hearing id, message id, court-centre id or exception message must never be a label value —
 * that is both a cardinality explosion and, on a register whose every defendant is a youth, a
 * privacy breach. Correlation identifiers live in the structured logs instead.
 *
 * <p>Two of the instruments answer questions this flow asks and the informant service did not. The
 * completions counter separates the five ways a run can end well, because a high
 * completed-but-not-submitted rate is normal here — two of the four no-op reasons are the commonest
 * results the service has — and a single undifferentiated success is the legacy defect C33. The
 * anomaly counter records a register that was produced with a part skipped (fixes C19, C20 and C27),
 * which is deliberately not a failure: {@code TRANSFORMATION_FAILED} is reserved for a
 * transformation that cannot produce a document at all.
 *
 * <p>Dead-letter <em>depth</em> is deliberately absent: it is read from Azure Monitor's native queue
 * metric. This service counts the dead-letters it performs, which is a different question.
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

    private static final int UP = 1;
    private static final int DOWN = 0;

    private final MeterRegistry registry;

    /**
     * Gauge state. Held here rather than read from a collaborator so the gauges exist from
     * construction: a dashboard must be able to read them from a pod that has not yet seen a
     * message, and a gauge that only appears after the first incident is not an alerting surface.
     */
    private final AtomicInteger intakeSuspendedState = new AtomicInteger(DOWN);

    /**
     * How the Service Bus gauge answers, at the moment it is asked.
     *
     * <p>A supplier rather than a remembered number, because the state it reports is partly a
     * function of time: an error goes stale, and a consumer that has never been answered stops being
     * given the benefit of the doubt. A value written at the last state change would be whatever it
     * was when something last happened, which for exactly those two transitions is the wrong answer
     * for as long as nothing happens. Up until something says otherwise, which is the honest
     * starting position for a healthy pod.
     */
    private final AtomicReference<BooleanSupplier> serviceBusState =
            new AtomicReference<>(() -> true);

    /**
     * Registers the service's gauges against the given registry.
     *
     * @param registry the registry every instrument is registered against
     */
    public ProcessingMetrics(final MeterRegistry registry) {
        this.registry = registry;

        Gauge.builder(INTAKE_SUSPENDED, intakeSuspendedState, AtomicInteger::get)
                .description("1 while intake is suspended, 0 while it is running")
                .register(registry);
        Gauge.builder(SERVICEBUS_UP, serviceBusState,
                        state -> state.get().getAsBoolean() ? UP : DOWN)
                .description("1 while the Service Bus health component is up, 0 while it is down")
                .register(registry);
    }

    /**
     * A request reached a terminal outcome.
     *
     * @param outcome how the request finished
     */
    public void requestSettled(final RequestOutcome outcome) {
        counter(PROCESSED, OUTCOME_TAG, outcome.label()).increment();
    }

    /**
     * A request completed, for one of the five reasons a run can end well.
     *
     * <p>Counted separately from {@link #requestSettled}, which answers "did it finish"; this
     * answers "did a register go out, and if not, which of the four business skips ended it".
     *
     * @param reason why the request completed
     */
    public void completed(final CompletionReason reason) {
        counter(COMPLETIONS, REASON_TAG, reason.value()).increment();
    }

    /**
     * A pipeline run failed — every failed run, including a transient one that ends in RETRYING, not
     * only terminal exhaustion. A request retrying quietly forever is exactly what this service
     * exists to make visible.
     *
     * @param classification whether the failure is worth retrying
     */
    public void pipelineFailed(final FailureClassification classification) {
        counter(PROCESSING_FAILURES, CLASSIFICATION_TAG, classification.label()).increment();
    }

    /**
     * A guarded, non-fatal transformation anomaly was met and skipped — once per occurrence, so the
     * counter matches the bounded count written to {@code processed_output.anomaly_summary}.
     *
     * @param anomaly which part of the register could not be built
     */
    public void transformationAnomaly(final TransformationAnomaly anomaly) {
        counter(TRANSFORMATION_ANOMALIES, REASON_TAG, anomaly.value()).increment();
    }

    /**
     * Intake moved into SUSPENDED.
     */
    public void intakeSuspended() {
        intakeSuspendedState.set(UP);
        counter(INTAKE_SUSPENSIONS).increment();
    }

    /**
     * Intake moved back into RUNNING. Deliberately not counted: the counter records incidents, and
     * recovering from one is not a second incident.
     */
    public void intakeResumed() {
        intakeSuspendedState.set(DOWN);
    }

    /**
     * A delivery was parked on the dead-letter queue.
     *
     * @param reason why it was parked
     */
    public void deadLettered(final DeadLetterReason reason) {
        counter(DEAD_LETTERED, REASON_TAG, reason.label()).increment();
    }

    /**
     * A settlement call itself failed.
     *
     * @param operation which settlement call failed
     */
    public void settlementFailed(final SettlementOperation operation) {
        counter(SETTLEMENT_FAILURES, OPERATION_TAG, operation.label()).increment();
    }

    /**
     * The delivery lock was lost before settlement.
     */
    public void lockLost() {
        counter(LOCK_LOSS).increment();
    }

    /**
     * An outcome write was rejected by the owner-and-token predicate.
     */
    public void staleRunnerRejected() {
        counter(STALE_RUNNER_REJECTIONS).increment();
    }

    /**
     * Mirrors the Service Bus health component.
     *
     * @param up whether the broker is reachable
     */
    public void serviceBusUp(final boolean up) {
        serviceBusState.set(() -> up);
    }

    /**
     * Points the Service Bus gauge at the component that knows the answer.
     *
     * <p>So that a scrape and a health check read the same live state rather than the same
     * remembered one, whichever of them happens first and whether or not the other ever happens at
     * all. A Prometheus scrape does not call the health endpoint on its way past.
     *
     * @param liveState answers, on demand, whether the broker is reachable
     */
    public void bindServiceBusUp(final BooleanSupplier liveState) {
        serviceBusState.set(liveState);
    }

    private Counter counter(final String name) {
        return Counter.builder(name).register(registry);
    }

    private Counter counter(final String name, final String tag, final String value) {
        return Counter.builder(name).tag(tag, value).register(registry);
    }
}
