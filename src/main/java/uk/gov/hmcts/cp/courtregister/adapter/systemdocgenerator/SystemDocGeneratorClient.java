package uk.gov.hmcts.cp.courtregister.adapter.systemdocgenerator;

import java.util.Optional;
import java.util.UUID;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.courtregister.application.DocumentRenderer;
import uk.gov.hmcts.cp.courtregister.domain.CallerIdentity;
import uk.gov.hmcts.cp.courtregister.domain.DocumentStatus;
import uk.gov.hmcts.cp.courtregister.domain.GenerationFailedException;
import uk.gov.hmcts.cp.courtregister.domain.RenderRequest;

/**
 * The renderer port, wired to systemdocgenerator's command and query APIs.
 *
 * <p>Two conversations over one {@code RestClient}, both carrying the caller identity as
 * {@code CJSCPPUID} and both using the shared {@code adapter/http/RetryPolicy}, so this client
 * cannot hold a different opinion about what is worth asking again than the two clients 001 already
 * built (defect fix C3).
 *
 * <p>The command is {@code POST {SDG}/systemdocgenerator-command-api/command/api/rest/
 * systemdocgenerator/generate-document} with media type
 * {@code application/vnd.systemdocgenerator.generate-document+json} and a body of exactly five
 * fields: {@code templateIdentifier} OEE_Layout5, {@code conversionFormat} pdf, the
 * {@code payloadFileServiceId} this service minted, {@code sourceCorrelationId} the batch id, and
 * {@code originatingSource} CourtRegisterService. The last of those is what keeps progression's
 * still-deployed listener out of this service's documents, and the one before it is the only thing
 * that correlates an outcome event back to rows.
 *
 * <p><strong>202 and nothing else is success.</strong> A 2xx that is not 202 means something other
 * than the command endpoint answered, which is a different investigation from a refusal and is never
 * treated as a render that will happen; it is RENDER_REQUEST_REJECTED and the batch fails.
 *
 * <p>The query is {@code GET {SDG}/systemdocgenerator-query-api/query/api/rest/systemdocgenerator/
 * document/{payloadFileId}} with {@code Accept: application/vnd.systemdocgenerator.query.document
 * +json}, and it is the reconciler's alone. Its four optional fields are mapped as they arrive, with
 * no judgement about what the combination means: that is the reconciler's, which knows the grace
 * period.
 *
 * <p><strong>One attempt per call, classified and handed back.</strong> The taxonomy is the shared
 * one - 408, 429 and every 5xx are worth asking again, any other 4xx is a refusal - but the waiting
 * and the counting are not this class's, because the bound on them is the run deadline and this
 * class is not told what is left of it: {@code DocumentRenderer.requestRender} takes a request and a
 * caller and nothing else. {@code application/RegisterGenerationService} holds the deadline and
 * therefore holds the loop, and a client that retried underneath it would spend a budget it cannot
 * see.
 *
 * <p><strong>Seam only.</strong> The client lands with T045; until then both methods throw, so that
 * {@code SystemDocGeneratorClientTest} records a failing assertion rather than a compile error. The
 * constructor is the seam that suite needed: it states what the adapter is built from, so the tests
 * can build one the way {@code LiveGenerationConfig} eventually will.
 */
// PMD.UnusedPrivateField: the three below are what the adapter is built from, and both methods that
// would read them throw until T045 lands. Holding them now is what lets the suite build this client
// the way a deployment does; the suppression goes when the two conversations do.
@SuppressWarnings("PMD.UnusedPrivateField")
public class SystemDocGeneratorClient implements DocumentRenderer {

    /** The client, carrying the systemdocgenerator base URL and its timeouts. */
    private final RestClient restClient;

    /** The {@code CJSCPPUID} identity for a run naming no user; a secret, never logged. */
    private final String systemUserId;

    /** The shared mapper, so a body is written and an answer read exactly as any other JSON is. */
    private final ObjectMapper objectMapper;

    /**
     * Builds the client over an already-configured HTTP client.
     *
     * @param restClient   the client, carrying the systemdocgenerator base URL and its timeouts
     * @param systemUserId the {@code CJSCPPUID} identity for a run naming no user; a secret, never
     *                     logged
     * @param objectMapper the shared mapper, so the command body and the query answer are written
     *                     and read exactly as every other JSON in this service is
     */
    public SystemDocGeneratorClient(final RestClient restClient, final String systemUserId,
            final ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.systemUserId = systemUserId;
        this.objectMapper = objectMapper;
    }

    @Override
    public void requestRender(final RenderRequest request, final CallerIdentity caller)
            throws GenerationFailedException {
        throw new UnsupportedOperationException("T045");
    }

    @Override
    public Optional<DocumentStatus> query(final UUID payloadFileId, final CallerIdentity caller)
            throws GenerationFailedException {
        throw new UnsupportedOperationException("T045");
    }
}
