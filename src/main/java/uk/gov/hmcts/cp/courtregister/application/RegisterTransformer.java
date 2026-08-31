package uk.gov.hmcts.cp.courtregister.application;

import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.courtregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.courtregister.domain.TransformationFailedException;

/**
 * How a hearing payload becomes a court register.
 *
 * <p>The third of the four ports the core owns, and the one that carries almost all of the fix work:
 * behind it are the fragment build, the subscription matching and the twelve-mapper aggregation,
 * chained. The pipeline knows only that a payload goes in and one of two answers comes out.
 *
 * <p>The port is pure by contract — no I/O of its own beyond the reference-data read the matching
 * stage owns, no clock, no randomness (constitution Principle V) — which is what lets the whole of
 * the ported transformation be tested against fixtures with no broker, no cache and no progression.
 */
public interface RegisterTransformer {

    /**
     * Transforms one hearing into a register, or into the reason there is none.
     *
     * @param command        the validated request
     * @param hearingPayload the hearing payload, exactly as the producer sent it
     * @return the assembled register, or the bounded reason there is nothing to send
     * @throws TransformationFailedException if the payload cannot be transformed at all — always
     *     non-transient
     */
    TransformationResult transform(DistributionCommand command, JsonNode hearingPayload)
            throws TransformationFailedException;
}
