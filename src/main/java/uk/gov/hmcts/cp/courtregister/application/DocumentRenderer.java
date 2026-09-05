package uk.gov.hmcts.cp.courtregister.application;

import java.util.Optional;
import java.util.UUID;
import uk.gov.hmcts.cp.courtregister.domain.CallerIdentity;
import uk.gov.hmcts.cp.courtregister.domain.DocumentStatus;
import uk.gov.hmcts.cp.courtregister.domain.GenerationFailedException;
import uk.gov.hmcts.cp.courtregister.domain.RenderRequest;

/**
 * Who turns a stored payload into a PDF.
 *
 * <p>systemdocgenerator, behind a port that names neither HTTP nor a status code. Two methods
 * because there are two conversations and they are not the same one: asking for a document is a
 * command, and asking what became of one is a query the reconciler makes and nothing else does.
 *
 * <p><strong>Success for the command is {@code 202 Accepted} and nothing else.</strong> The same
 * rule 001 applies to progression, for the same reason: a 2xx that is not 202 means something other
 * than the command endpoint answered, which is a different investigation from a refusal and is never
 * treated as a render that will happen.
 *
 * <p>The request is a command and not a promise. The document arrives later, on the public-event
 * topic, and the batch waits in GENERATING until it does; the query below is the safety net for when
 * it does not.
 */
public interface DocumentRenderer {

    /**
     * Asks systemdocgenerator to render one batch.
     *
     * @param request the payload id, the batch it correlates to, and the template
     * @param caller  who the call is made as
     * @throws GenerationFailedException if the request was not accepted with a 202, carrying whether
     *     another attempt inside the run deadline could change that
     */
    void requestRender(RenderRequest request, CallerIdentity caller)
            throws GenerationFailedException;

    /**
     * Asks systemdocgenerator what became of a payload it was given.
     *
     * <p>The reconciler's method, and only the reconciler's: this is the flow's one synchronous
     * coupling to the renderer, and it is spent on batches whose grace period has passed rather than
     * on every batch of every run.
     *
     * @param payloadFileId the payload the render was requested for
     * @param caller        who the call is made as
     * @return what systemdocgenerator says, or empty where it has nothing to say yet
     * @throws GenerationFailedException if the query itself could not be answered; a query that
     *     fails is not a generation that failed, and the batch stays GENERATING
     */
    Optional<DocumentStatus> query(UUID payloadFileId, CallerIdentity caller)
            throws GenerationFailedException;
}
