package uk.gov.hmcts.cp.courtregister.adapter.systemdocgenerator;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.courtregister.adapter.http.RetryPolicy;
import uk.gov.hmcts.cp.courtregister.application.DocumentRenderer;
import uk.gov.hmcts.cp.courtregister.domain.BatchFailureReason;
import uk.gov.hmcts.cp.courtregister.domain.CallerIdentity;
import uk.gov.hmcts.cp.courtregister.domain.DocumentStatus;
import uk.gov.hmcts.cp.courtregister.domain.FailureClassification;
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
 * that correlates an outcome event back to rows. The five are the request's own, passed through as
 * {@link RenderRequest} carries them: the vendored schema is
 * {@code additionalProperties: false}, so a sixth field this adapter added of its own would be a 400
 * rather than a field systemdocgenerator ignored.
 *
 * <p><strong>202 and nothing else is success.</strong> A 2xx that is not 202 means something other
 * than the command endpoint answered, which is a different investigation from a refusal and is never
 * treated as a render that will happen; it is RENDER_REQUEST_REJECTED and the batch fails.
 *
 * <p>The query is {@code GET {SDG}/systemdocgenerator-query-api/query/api/rest/systemdocgenerator/
 * document/{payloadFileId}} with {@code Accept: application/vnd.systemdocgenerator.query.document
 * +json}, and it is the reconciler's alone. Its four optional fields are mapped as they arrive, with
 * no judgement about what the combination means: that is the reconciler's, which knows the grace
 * period. A payload systemdocgenerator has no record of is an empty answer rather than a failure -
 * having nothing to say is not the same as a render that failed - and a query that could not be
 * answered at all is neither, so it is raised with its classification and leaves the batch
 * GENERATING.
 *
 * <p><strong>One attempt per call, classified and handed back.</strong> The taxonomy is the shared
 * one - 408, 429 and every 5xx are worth asking again, any other 4xx is a refusal - but the waiting
 * and the counting are not this class's, because the bound on them is the run deadline and this
 * class is not told what is left of it: {@code DocumentRenderer.requestRender} takes a request and a
 * caller and nothing else. {@code application/RegisterGenerationService} holds the deadline and
 * therefore holds the loop, and a client that retried underneath it would spend a budget it cannot
 * see.
 *
 * <p>Nothing systemdocgenerator wrote is carried out of this class in a log line. The failure that
 * leaves it names a bounded {@link BatchFailureReason} and a status, and the {@code reason} the
 * query answers with is returned to the reconciler for {@code sdg_reason} and written no higher than
 * DEBUG: it is another system's free text about a document whose every defendant is a child
 * (constitution Principle VII).
 */
public class SystemDocGeneratorClient implements DocumentRenderer {

    /** The command's path under the systemdocgenerator context, exactly as its RAML declares it. */
    public static final String COMMAND_PATH =
            "/systemdocgenerator-command-api/command/api/rest/systemdocgenerator/generate-document";

    /** The command's vendor media type; the framework routes on it, so it is not a formality. */
    public static final String GENERATE_DOCUMENT_MEDIA_TYPE =
            "application/vnd.systemdocgenerator.generate-document+json";

    /** The query's path, with the payload id the render was requested for. */
    public static final String QUERY_PATH =
            "/systemdocgenerator-query-api/query/api/rest/systemdocgenerator/document/"
                    + "{payloadFileId}";

    /** The query's vendor media type, sent as {@code Accept}. */
    public static final String DOCUMENT_MEDIA_TYPE =
            "application/vnd.systemdocgenerator.query.document+json";

    /**
     * The CPP identity header. Its value is never logged - it is either a secret or a user
     * identifier, and neither belongs in a log index.
     */
    public static final String IDENTITY_HEADER = "CJSCPPUID";

    /** The one status the contract calls success. */
    public static final int ACCEPTED = 202;

    private static final Logger LOG = LoggerFactory.getLogger(SystemDocGeneratorClient.class);

    /** The rendered document's file-service id, where the render finished. */
    private static final String DOCUMENT_FILE_SERVICE_ID = "documentFileServiceId";

    /** When it finished. */
    private static final String GENERATED_TIME = "generatedTime";

    /** When it failed instead. */
    private static final String FAILED_TIME = "failedTime";

    /** systemdocgenerator's own words about a failure; support reads them, no log index does. */
    private static final String REASON = "reason";

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
        // Resolved once, exactly as the three clients 001 built resolve it: the run's user where the
        // message named one, and the configured identity otherwise.
        final String identity = caller.orSystem(systemUserId);
        final byte[] body = objectMapper.writeValueAsBytes(new GenerateDocument(
                request.templateIdentifier(),
                request.conversionFormat(),
                request.payloadFileId(),
                request.batchId(),
                request.originatingSource()));
        try {
            restClient.post()
                    .uri(COMMAND_PATH)
                    .headers(headers -> {
                        headers.setContentType(
                                MediaType.parseMediaType(GENERATE_DOCUMENT_MEDIA_TYPE));
                        headers.set(IDENTITY_HEADER, identity);
                    })
                    .body(body)
                    .exchange((sent, answer) -> classify(answer, request.batchId()));
        } catch (ResourceAccessException unreachable) {
            // Connect failure, read timeout, connection dropped: the request may or may not have
            // reached systemdocgenerator. Unknown is not refused, so it is handed back for the run
            // to ask again inside its deadline, and it carries no status - an invented one would
            // say an attempt was answered when nothing answered.
            //
            // The exception travels with the line rather than only its type. What the run acts on is
            // the classification; what a human acts on is what it *was*, and the bounded reason code
            // cannot carry that. It is safe to keep: a transport exception is raised instead of a
            // response, so it names the endpoint and the socket error and never a register.
            LOG.warn("The generate-document request reached no verdict, so whether the render was "
                    + "asked for is unknown. batchId={} cause={}", request.batchId(),
                    unreachable.getClass().getName());
            throw new GenerationFailedException(
                    FailureClassification.TRANSIENT, BatchFailureReason.RENDER_REQUEST_FAILED);
        }
    }

    @Override
    public Optional<DocumentStatus> query(final UUID payloadFileId, final CallerIdentity caller)
            throws GenerationFailedException {
        final String identity = caller.orSystem(systemUserId);
        final Optional<DocumentStatus> status;
        try {
            status = restClient.get()
                    .uri(QUERY_PATH, payloadFileId)
                    .headers(headers -> {
                        headers.set(HttpHeaders.ACCEPT, DOCUMENT_MEDIA_TYPE);
                        headers.set(IDENTITY_HEADER, identity);
                    })
                    .exchange((sent, answer) -> answerAbout(answer, payloadFileId));
        } catch (ResourceAccessException unreachable) {
            // A question that could not be asked is not an answer about the render, so the batch
            // stays GENERATING and the reconciler decides what to do about a renderer that will not
            // answer.
            LOG.warn("The systemdocgenerator document query reached no verdict, so nothing is known "
                    + "about the render. payloadFileId={} cause={}", payloadFileId,
                    unreachable.getClass().getName());
            throw new GenerationFailedException(
                    FailureClassification.TRANSIENT, BatchFailureReason.RENDER_REQUEST_FAILED);
        }
        return status;
    }

    /**
     * What systemdocgenerator's answer to the command means.
     *
     * <p>Nothing it wrote is read: the body of a refusal describes a document whose every defendant
     * is a child, and every decision here travels into a log line and a batch row.
     */
    private static int classify(final ClientHttpResponse response, final UUID batchId)
            throws IOException {
        final HttpStatusCode statusCode = response.getStatusCode();
        final int status = statusCode.value();
        if (status != ACCEPTED) {
            if (statusCode.is2xxSuccessful()) {
                // The contract declares one success. A 200 or a 204 means something other than the
                // command endpoint answered - a proxy, or a route that no longer reaches it - and
                // calling it success would move the batch to GENERATING for a render nothing was
                // asked for, to wait out its grace period for an event that cannot come.
                LOG.error("systemdocgenerator answered a success this contract does not define, so "
                        + "no render can be assumed. batchId={} status={}", batchId, status);
                throw rejected(status);
            }
            if (RetryPolicy.retryable(status)) {
                // 408, 429 and every server error, from the one policy all this service's clients
                // hold (C3). The asking again is the run's, bounded by the deadline this client is
                // not told about.
                LOG.warn("systemdocgenerator could not take the render request, so the run may ask "
                        + "again inside its deadline. batchId={} status={}", batchId, status);
                throw new GenerationFailedException(FailureClassification.TRANSIENT,
                        BatchFailureReason.RENDER_REQUEST_FAILED, status);
            }
            // Any other 4xx: the request was understood and declined, and the same request will be
            // declined again.
            LOG.error("systemdocgenerator refused the render request, and asking again cannot "
                    + "change that. batchId={} status={}", batchId, status);
            throw rejected(status);
        }
        return status;
    }

    /**
     * What systemdocgenerator's answer to the query means.
     *
     * <p>Three answers, and only the first is about a render: the document's row as it stands, a
     * {@code 404} saying there is no such row, and a status saying the question could not be
     * answered. The last two are deliberately not merged - a payload systemdocgenerator never
     * received is a fact the reconciler can act on, and a 503 is nothing at all.
     */
    private Optional<DocumentStatus> answerAbout(
            final ClientHttpResponse response, final UUID payloadFileId) throws IOException {
        final HttpStatusCode statusCode = response.getStatusCode();
        final int status = statusCode.value();
        final Optional<DocumentStatus> answer;
        if (status == HttpStatus.OK.value()) {
            answer = Optional.of(documentStatus(response, payloadFileId));
        } else if (status == HttpStatus.NOT_FOUND.value()) {
            LOG.warn("systemdocgenerator has no record of the payload, so it has nothing to say "
                    + "about the render. payloadFileId={}", payloadFileId);
            answer = Optional.empty();
        } else if (RetryPolicy.retryable(status)) {
            // The same taxonomy the command is held to, because it is the same object: a query is
            // not worth asking again on statuses a command would be abandoned on.
            LOG.warn("systemdocgenerator could not answer the document query, so the batch is left "
                    + "as it stands. payloadFileId={} status={}", payloadFileId, status);
            throw new GenerationFailedException(FailureClassification.TRANSIENT,
                    BatchFailureReason.RENDER_REQUEST_FAILED, status);
        } else {
            LOG.error("systemdocgenerator refused the document query, so the batch is left as it "
                    + "stands. payloadFileId={} status={}", payloadFileId, status);
            throw rejected(status);
        }
        return answer;
    }

    /**
     * The four optional fields, mapped as they arrive.
     *
     * <p>No verdict is formed from them here. A generated time with an id is a document and a failed
     * time with a reason is a refusal, but a row with neither is only a row with no verdict yet, and
     * whether that has gone on too long depends on the grace period, which the reconciler holds.
     *
     * <p>A body that could not be read is not an answer either: a gateway's error page served with a
     * 200 is the everyday case, and reading it as a render with no verdict would leave the batch
     * waiting on a renderer that is not there. It is transient, and what could not be read is
     * reported by type - a parser quotes the token it choked on, and this line reaches the log
     * index.
     */
    private DocumentStatus documentStatus(
            final ClientHttpResponse response, final UUID payloadFileId) throws IOException {
        final DocumentStatus status;
        try {
            final JsonNode answer = objectMapper.readTree(response.getBody());
            status = new DocumentStatus(
                    identifier(answer, DOCUMENT_FILE_SERVICE_ID),
                    time(answer, GENERATED_TIME),
                    time(answer, FAILED_TIME),
                    text(answer, REASON));
        } catch (JacksonException | DateTimeParseException | IllegalArgumentException unreadable) {
            LOG.warn("systemdocgenerator answered the document query with something this service "
                    + "cannot read, so nothing is known about the render. payloadFileId={} type={}",
                    payloadFileId, unreadable.getClass().getName());
            throw new GenerationFailedException(FailureClassification.TRANSIENT,
                    BatchFailureReason.RENDER_REQUEST_FAILED, HttpStatus.OK.value());
        }
        // DEBUG and no higher, and only ever here: `reason` is systemdocgenerator's own free text
        // about a document whose every defendant is a child. It reaches support through
        // `sdg_reason`, which is a column and not a log index (constitution Principle VII).
        LOG.debug("systemdocgenerator answered the document query. payloadFileId={} answer={}",
                payloadFileId, status);
        return status;
    }

    /** A refusal no further attempt can change, carrying the status that made it one. */
    private static GenerationFailedException rejected(final int status) {
        return new GenerationFailedException(FailureClassification.NON_TRANSIENT,
                BatchFailureReason.RENDER_REQUEST_REJECTED, status);
    }

    /** One optional id, absent where systemdocgenerator has not reached that part of the row. */
    private static UUID identifier(final JsonNode answer, final String field) {
        final JsonNode value = answer.get(field);
        return value == null || value.isNull() ? null : UUID.fromString(value.stringValue());
    }

    /** One optional instant, in the ISO form the query schema declares. */
    private static Instant time(final JsonNode answer, final String field) {
        final JsonNode value = answer.get(field);
        return value == null || value.isNull() ? null : Instant.parse(value.stringValue());
    }

    /** One optional string, kept exactly as it arrived because support reads it. */
    private static String text(final JsonNode answer, final String field) {
        final JsonNode value = answer.get(field);
        return value == null || value.isNull() ? null : value.stringValue();
    }

    /**
     * The command body: the five fields the contract declares, and no sixth.
     *
     * @param templateIdentifier  the systemdocgenerator template, {@code OEE_Layout5}, unchanged
     * @param conversionFormat    the output format, {@code pdf}
     * @param payloadFileServiceId the file-service id this service minted and inserted the payload
     *                            under
     * @param sourceCorrelationId the batch id, and the only thing that correlates the outcome event
     *                            back to rows
     * @param originatingSource   this service's own name, which keeps progression's still-deployed
     *                            listener out of these documents
     */
    private record GenerateDocument(
            String templateIdentifier,
            String conversionFormat,
            UUID payloadFileServiceId,
            UUID sourceCorrelationId,
            String originatingSource) {
    }
}
