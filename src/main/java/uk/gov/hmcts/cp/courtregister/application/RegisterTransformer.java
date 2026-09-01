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
 * <p>The port is pure by contract — <strong>no I/O at all</strong>, no clock, no randomness
 * (constitution Principle V) — which is what lets the whole of the ported transformation be tested
 * against fixtures with no broker, no cache and no progression. The reference data the matching
 * stage needs is therefore an <em>argument</em>: the core reads it and hands it in, so no stage
 * behind this port can reach a port of its own.
 */
public interface RegisterTransformer {

    /**
     * Transforms one hearing into a register, or into the reason there is none.
     *
     * @param command        the validated request
     * @param hearingPayload the hearing payload, exactly as the producer sent it
     * @param subscriptions  reference data's now-subscriptions answer for the register's own day,
     *                       already read; an answer carrying none is still an answer
     * @return the assembled register, or the bounded reason there is nothing to send
     * @throws TransformationFailedException if the payload cannot be transformed at all — always
     *     non-transient
     */
    TransformationResult transform(
            DistributionCommand command, JsonNode hearingPayload, JsonNode subscriptions)
            throws TransformationFailedException;
}
