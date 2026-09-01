package uk.gov.hmcts.cp.courtregister.pipeline;

import java.util.function.Consumer;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.courtregister.adapter.progression.OutboundContractValidator;
import uk.gov.hmcts.cp.courtregister.application.RegisterTransformer;
import uk.gov.hmcts.cp.courtregister.application.TransformationResult;
import uk.gov.hmcts.cp.courtregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.courtregister.domain.TransformationAnomaly;

/**
 * The whole transformation, in the order the legacy orchestrator runs it.
 *
 * <p>Build the fragment, address it against the subscriptions the core has already read, assemble
 * the document, and hold it to the contract progression published before anybody tries to send it.
 * Four stages, each of which can legitimately end the run without a register, and each of which says
 * which of the three {@link uk.gov.hmcts.cp.courtregister.domain.NoRegisterReason}s it was.
 *
 * <p><strong>A seam.</strong> The behaviour lands in T056, against the assertions T055a writes.
 */
public final class RegisterTransformationChain implements RegisterTransformer {

    /**
     * Creates the chain over its four stages.
     *
     * @param registerBuilder     the fragment stage
     * @param subscriptionMatcher the addressing stage
     * @param contractValidator   the stage that refuses a document progression would
     * @param anomalyRecorder     where every guarded skip beneath the chain is counted
     */
    public RegisterTransformationChain(
            final RegisterBuilder registerBuilder,
            final SubscriptionMatcher subscriptionMatcher,
            final OutboundContractValidator contractValidator,
            final Consumer<TransformationAnomaly> anomalyRecorder) {
        // The seam holds nothing yet; T056's body is what these become fields for.
    }

    @Override
    public TransformationResult transform(
            final DistributionCommand command,
            final JsonNode hearingPayload,
            final JsonNode subscriptions) {
        throw new UnsupportedOperationException(
                "RegisterTransformationChain.transform is implemented by T056");
    }
}
