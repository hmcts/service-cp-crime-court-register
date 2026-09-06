package uk.gov.hmcts.cp.courtregister.application;

import java.time.Clock;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.courtregister.adapter.http.RetryPause;
import uk.gov.hmcts.cp.courtregister.adapter.http.RetryPolicy;
import uk.gov.hmcts.cp.courtregister.config.GenerationMetrics;
import uk.gov.hmcts.cp.courtregister.domain.Deadline;
import uk.gov.hmcts.cp.courtregister.domain.RegisterBatch;
import uk.gov.hmcts.cp.courtregister.pipeline.PdfPayloadMapper;

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
 * <p><strong>The retry loop is here rather than in the renderer</strong>, which is the opposite of
 * where 001 put it: {@link DocumentRenderer} names no deadline, because a run's budget is the run's
 * knowledge and not systemdocgenerator's, so the client classifies one attempt through the shared
 * {@link RetryPolicy} and this decides whether there is room for another. The taxonomy is still
 * stated once (defect fix C3); only the loop moved to the object that holds the budget.
 *
 * <p><strong>Seam only.</strong> The service lands with T049; until then {@link #request} throws,
 * so that {@code RegisterGenerationServiceTest} records a failing assertion rather than a compile
 * error. The collaborators are held from now because the suite has to build the service to say
 * anything about the order it uses them in.
 */
// PMD.UnusedPrivateField: the collaborators are the seam's whole point - the suite constructs the
// service with them and T049 is what starts reading them. The suppression goes with the throw.
@SuppressWarnings("PMD.UnusedPrivateField")
public class RegisterGenerationService {

    private final RegisterStore store;
    private final PdfPayloadMapper payloadMapper;
    private final PayloadFileStore payloadFileStore;
    private final DocumentRenderer renderer;
    private final ObjectMapper objectMapper;
    private final RetryPolicy retryPolicy;
    private final RetryPause pause;
    private final GenerationMetrics metrics;
    private final Clock clock;

    /**
     * Creates the service over the one store, the one payload leg and the one renderer.
     *
     * @param registerStore     where the batch and its registers are read and written
     * @param mapper            progression's payload generator, ported
     * @param fileStore         the framework file service the payload is written to
     * @param documentRenderer  systemdocgenerator, behind the port that names no status code
     * @param json              the shared mapper, which decides what the payload's bytes are and
     *                          therefore what the metadata's {@code fileSize} is
     * @param policy            the shared retry policy: attempts, back-off, and what one attempt
     *                          can cost against the run's budget
     * @param retryPause        how a wait between attempts is taken
     * @param generationMetrics the downstream half's instruments
     * @param runClock          this pod's reading of now, compared only with this run's own deadline
     */
    public RegisterGenerationService(final RegisterStore registerStore,
            final PdfPayloadMapper mapper, final PayloadFileStore fileStore,
            final DocumentRenderer documentRenderer, final ObjectMapper json,
            final RetryPolicy policy, final RetryPause retryPause,
            final GenerationMetrics generationMetrics, final Clock runClock) {
        this.store = registerStore;
        this.payloadMapper = mapper;
        this.payloadFileStore = fileStore;
        this.renderer = documentRenderer;
        this.objectMapper = json;
        this.retryPolicy = policy;
        this.pause = retryPause;
        this.metrics = generationMetrics;
        this.clock = runClock;
    }

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
