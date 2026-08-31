package uk.gov.hmcts.cp.courtregister.application;

import java.time.Clock;
import java.time.Duration;
import uk.gov.hmcts.cp.courtregister.config.ProcessingMetrics;
import uk.gov.hmcts.cp.courtregister.domain.DeliveryIdentity;
import uk.gov.hmcts.cp.courtregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.courtregister.domain.GuardDecision;

/**
 * The use case: one validated request, run to a recorded outcome and a settlement decision.
 *
 * <p><strong>A compile-safe seam, not the pipeline.</strong> T022 implements the orchestration this
 * class exists to hold — admit through the guard, fetch the payload, transform, submit, record —
 * and widens the constructor to the remaining ports (the transformer and the submission client) as
 * it does so. What is fixed here is the shape the transport adapter depends on: a request and the
 * delivery it arrived on go in, and exactly one {@link GuardDecision} comes out, so the listener has
 * something to settle on every path (constitution Principle VI).
 *
 * <p>The collaborators it already holds are the ones the settlement suites drive it through: the
 * guard, whose durable write must return before a delivery may be acknowledged, and the payload
 * port, which is where a held run is held.
 */
public class DistributionPipeline {

    private final IdempotencyGuard guard;
    private final HearingPayloadSource payloadSource;
    private final ProcessingMetrics metrics;
    private final Clock clock;
    private final Duration processingDeadline;

    /**
     * Creates the pipeline over the ports it runs against.
     *
     * @param guard              the {@code (source, requestId)} processed-log guard
     * @param payloadSource      where the hearing payload comes from
     * @param metrics            the instrument surface every outcome is counted on
     * @param clock              elapsed-time source for the run's own deadline; no claim decision is
     *                           made from it, so it cannot introduce multi-node skew
     * @param processingDeadline the enforced bound on a run, strictly shorter than the claim lease
     */
    public DistributionPipeline(
            final IdempotencyGuard guard,
            final HearingPayloadSource payloadSource,
            final ProcessingMetrics metrics,
            final Clock clock,
            final Duration processingDeadline) {
        this.guard = guard;
        this.payloadSource = payloadSource;
        this.metrics = metrics;
        this.clock = clock;
        this.processingDeadline = processingDeadline;
    }

    /**
     * Runs one request and reports what should happen to the delivery that carried it.
     *
     * @param command  the validated request
     * @param delivery who is running it, and whether the queue will deliver it again
     * @return the settlement the outcome calls for
     */
    public GuardDecision process(
            final DistributionCommand command, final DeliveryIdentity delivery) {
        throw new UnsupportedOperationException(
                "every request runs to a recorded outcome and one settlement decision");
    }
}
