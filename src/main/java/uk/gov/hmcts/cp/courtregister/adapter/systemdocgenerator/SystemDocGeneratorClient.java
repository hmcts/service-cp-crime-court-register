package uk.gov.hmcts.cp.courtregister.adapter.systemdocgenerator;

import java.util.Optional;
import java.util.UUID;
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
 * <p><strong>Seam only.</strong> The client lands with T045; until then both methods throw, so that
 * {@code SystemDocGeneratorClientTest} records a failing assertion rather than a compile error.
 */
public class SystemDocGeneratorClient implements DocumentRenderer {

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
