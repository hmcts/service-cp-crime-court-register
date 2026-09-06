package uk.gov.hmcts.cp.courtregister.application;

import java.time.Instant;
import java.util.UUID;
import uk.gov.hmcts.cp.courtregister.domain.CompletedBy;

/**
 * Where a rendering outcome is applied, whoever learned it.
 *
 * <p>One port with two drivers: the {@code public.event} listener, which is how outcomes normally
 * arrive, and the grace-period reconciler, which fetches the ones that did not. Both apply the
 * outcome through here rather than each writing the store itself, so there is exactly one place that
 * decides what an outcome does to a batch and its rows - and exactly one place a duplicate has to be
 * absorbed, which it will be: a durable subscription redelivers, and a reconciler can ask about a
 * batch whose event is already in flight.
 *
 * <p>Nothing here names JMS, a message or an envelope. The listener parses those and calls this with
 * the four facts an outcome is: which batch, which payload, what happened, and when.
 *
 * <p><strong>And who learned it.</strong> Each caller names itself - the listener EVENT, the
 * reconciler RECONCILER - because the store writes {@code completed_by} in the same statement that
 * moves the batch, and a compare-and-set leaves no second moment to write it in. It is a parameter
 * rather than something the implementation infers from which class called it, so the one code path
 * both drivers share can stay one code path.
 */
public interface DocumentOutcomeSink {

    /**
     * Applies a document that was generated.
     *
     * <p>Scoped to the batch it names and never widened to the court centre - the sink is where
     * defect P3 would be reintroduced if anything here reached for the batch's key instead of its
     * identity.
     *
     * @param correlationId  the batch identity systemdocgenerator was given as
     *                       {@code sourceCorrelationId}
     * @param payloadFileId  the payload the document was rendered from
     * @param documentFileId the rendered document's file-service id
     * @param generatedAt    when it was generated
     * @param completedBy    the caller naming itself: EVENT from the listener, RECONCILER from the
     *                       grace-period reconciler
     */
    void documentAvailable(UUID correlationId, UUID payloadFileId, UUID documentFileId,
            Instant generatedAt, CompletedBy completedBy);

    /**
     * Applies a generation that failed.
     *
     * @param correlationId the batch identity systemdocgenerator was given as
     *                      {@code sourceCorrelationId}
     * @param payloadFileId the payload the render was requested for
     * @param reason        systemdocgenerator's own words, kept for support and never logged at
     *                      INFO; the batch's own reason is the bounded GENERATION_FAILED
     * @param failedAt      when the generation failed
     * @param completedBy   the caller naming itself: EVENT from the listener, RECONCILER from the
     *                      grace-period reconciler
     */
    void generationFailed(UUID correlationId, UUID payloadFileId, String reason, Instant failedAt,
            CompletedBy completedBy);
}
