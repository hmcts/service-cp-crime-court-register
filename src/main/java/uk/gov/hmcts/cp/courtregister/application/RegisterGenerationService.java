package uk.gov.hmcts.cp.courtregister.application;

import uk.gov.hmcts.cp.courtregister.domain.Deadline;
import uk.gov.hmcts.cp.courtregister.domain.RegisterBatch;

/**
 * One batch, from the payload to the render request.
 *
 * <p>The order is the same discipline the intake half keeps: the payload file id is minted and
 * persisted on the batch row <em>before</em> the file-service insert, and the insert happens before
 * the render request, so nothing downstream is ever asked about an identifier this service has not
 * already written down. An id minted and not recorded is a document that comes back attributable to
 * nothing.
 *
 * <p>Each step has one failure and one bounded reason: a payload that could not be stored fails the
 * batch PAYLOAD_STORE_UNAVAILABLE and leaves its rows RECORDED for the next run to re-assemble, a
 * request systemdocgenerator answered with anything but 202 fails it RENDER_REQUEST_REJECTED, and a
 * transient failure is retried inside the run deadline and then fails it RENDER_REQUEST_FAILED.
 * Nothing is inferred from silence: a 202 moves the batch to GENERATING, which says a render was
 * asked for and not that a document exists.
 *
 * <p>The service returns when the request has been accepted or has failed. It never waits for the
 * document, which arrives on the public-event topic and is applied through
 * {@link DocumentOutcomeSink}, so a slow render cannot delay the next batch of the run.
 *
 * <p><strong>Seam only.</strong> The service lands with T049; until then this throws, so that
 * {@code RegisterGenerationServiceTest} records a failing assertion rather than a compile error.
 */
public class RegisterGenerationService {

    /**
     * Stores one batch's payload and asks for it to be rendered.
     *
     * @param batch    the assembled batch, already durable at PENDING
     * @param deadline the run's requesting bound; an attempt whose own worst case does not fit
     *                 inside what is left is not started
     * @return what the batch ended the requesting leg as, for the run report
     */
    public BatchOutcome request(final RegisterBatch batch, final Deadline deadline) {
        throw new UnsupportedOperationException("T049");
    }
}
