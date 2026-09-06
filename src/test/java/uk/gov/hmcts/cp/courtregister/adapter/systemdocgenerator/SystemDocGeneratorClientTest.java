package uk.gov.hmcts.cp.courtregister.adapter.systemdocgenerator;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.courtregister.adapter.http.RetryPause;
import uk.gov.hmcts.cp.courtregister.adapter.http.RetryPolicy;
import uk.gov.hmcts.cp.courtregister.adapter.progression.ProgressionCommandGateway;
import uk.gov.hmcts.cp.courtregister.config.JacksonConfig;
import uk.gov.hmcts.cp.courtregister.domain.BatchFailureReason;
import uk.gov.hmcts.cp.courtregister.domain.CallerIdentity;
import uk.gov.hmcts.cp.courtregister.domain.DocumentStatus;
import uk.gov.hmcts.cp.courtregister.domain.FailureClassification;
import uk.gov.hmcts.cp.courtregister.domain.GenerationFailedException;
import uk.gov.hmcts.cp.courtregister.domain.RenderRequest;
import uk.gov.hmcts.cp.courtregister.domain.SubmissionFailedException;
import uk.gov.hmcts.cp.courtregister.support.AdjustableClock;
import uk.gov.hmcts.cp.courtregister.support.CapturedLog;

/**
 * The two conversations this service has with systemdocgenerator, against a real socket.
 *
 * <p>Both are somebody else's contract and neither is negotiable, so both are asserted on the wire
 * rather than against a mock that would agree with whatever the client did. The vendored schemas in
 * {@code specs/002-consolidate-progression-leg/contracts/systemdocgenerator/} are read by this suite
 * rather than quoted in a comment: the body this service sends is held to the properties the command
 * schema declares, and the answer it reads is held to the properties the query schema declares, so a
 * re-vendoring that changes either shows up here rather than at 18:00.
 *
 * <p><strong>The command's body is five fields and two of them are why it exists.</strong>
 * {@code sourceCorrelationId} is the batch id, and it is the only thing that correlates an outcome
 * event back to a night's rows - a render requested without it is a document nothing can be attached
 * to. {@code originatingSource} is this service's own name, and it is what keeps progression's
 * still-deployed listener out of these documents: progression's event processor branches on
 * {@code COURT_REGISTER.equalsIgnoreCase}, so a request that borrowed its source would have this
 * service's document announced to progression's leg (research §2).
 *
 * <p><strong>202 and nothing else is success</strong>, the same rule 001 holds progression to. A 2xx
 * that is not 202 means something other than the command endpoint answered - a proxy, or a route
 * that no longer reaches it - and treating it as accepted would leave a batch GENERATING for a render
 * nothing was ever asked for, waiting on an event that cannot come.
 *
 * <p><strong>The client asks once.</strong> The taxonomy is shared - that is what
 * {@code retry_taxonomy_matches_the_submission_client} states, by putting the same status in front of
 * this client and the submission gateway and reading both classifications - but the waiting and the
 * counting are not this class's, because the only bound on them is the run deadline and
 * {@code DocumentRenderer.requestRender} is not told what is left of it.
 * {@code RegisterGenerationService} holds the deadline and therefore holds the loop (T039).
 *
 * <p><strong>The query is the reconciler's alone</strong> and this suite maps its four optional
 * fields without deciding what their combination means. A generated time with an id is a document
 * and a failed time with a reason is a refusal, but a batch that has neither is only a batch with no
 * verdict yet, and whether that is a timeout depends on the grace period, which this client does not
 * know (T038). Its {@code reason} is systemdocgenerator's own free text about a document whose every
 * defendant is a child, so it is carried to {@code sdg_reason} and never written above DEBUG
 * (constitution Principle VII).
 *
 * @see <a href="file:../../../../../../../../../specs/002-consolidate-progression-leg/contracts/README.md">the
 *     vendored systemdocgenerator contracts</a>
 */
@DisplayName("systemdocgenerator client")
class SystemDocGeneratorClientTest {

    /** The command's path under the systemdocgenerator context, exactly as its RAML declares it. */
    private static final String COMMAND_PATH =
            "/systemdocgenerator-command-api/command/api/rest/systemdocgenerator/generate-document";

    /** The command's vendor media type; the framework routes on it, so it is not a formality. */
    private static final String COMMAND_MEDIA_TYPE =
            "application/vnd.systemdocgenerator.generate-document+json";

    /** The query's path, one payload id short of complete. */
    private static final String QUERY_PATH_PREFIX =
            "/systemdocgenerator-query-api/query/api/rest/systemdocgenerator/document/";

    /** The query's vendor media type, sent as {@code Accept}. */
    private static final String QUERY_MEDIA_TYPE =
            "application/vnd.systemdocgenerator.query.document+json";

    /** The CPP identity header. Its value is a secret or a user, and neither is ever logged. */
    private static final String IDENTITY_HEADER = "CJSCPPUID";

    /** The template progression renders the register with, unchanged. */
    private static final String TEMPLATE = "OEE_Layout5";

    /** The output format, and one of the three the command schema's enum permits. */
    private static final String FORMAT = "pdf";

    /** This service's own name, and what keeps progression's listener out of these documents. */
    private static final String ORIGINATING_SOURCE = "CourtRegisterService";

    /** The one status the contract calls success. */
    private static final int ACCEPTED = 202;

    /** The payload this service inserted into the file service and minted the id of. */
    private static final UUID PAYLOAD_FILE_ID =
            UUID.fromString("2c6f9b41-7d18-4e5a-9f03-6b8a1d4c7e25");

    /** The batch the render is for, which travels as {@code sourceCorrelationId}. */
    private static final UUID BATCH_ID = UUID.fromString("8a1d5e73-40b2-4c96-8f1e-3d7a9c05b264");

    /** The document systemdocgenerator says it rendered, in the query's answer. */
    private static final UUID DOCUMENT_FILE_ID =
            UUID.fromString("f0b23c8d-5a49-4e71-b6c2-9d10e485a37f");

    /** The identity a run that names no user is made under; a configured secret, never logged. */
    private static final String SYSTEM_USER_ID = "b6c8b0a4-1f2e-4a3b-9c4d-5e6f70819234";

    /** The user the night's run is attributed to, distinct so the two cannot be confused. */
    private static final String SHARING_USER = "0b7a5c2e-4d19-4a6b-8c30-9e1f5d7b2a48";

    /** The run's caller, resolved once and passed through unchanged. */
    private static final CallerIdentity CALLER =
            new CallerIdentity(Optional.of(UUID.fromString(SHARING_USER)));

    /** Stands in for whatever systemdocgenerator writes in a {@code reason}. */
    private static final String SDG_TEXT_MARKER = "SDGREASONMARKERZQX7";

    private static final Instant REQUESTED_TIME = Instant.parse("2026-09-04T17:00:05Z");
    private static final Instant GENERATED_TIME = Instant.parse("2026-09-04T17:00:41Z");
    private static final Instant FAILED_TIME = Instant.parse("2026-09-04T17:00:38Z");

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    /**
     * The waiting the submission client would do, made into nothing.
     *
     * <p>The comparison below is about what one answer <em>means</em>, so neither client is given
     * the chance to spend a second on it.
     */
    private static final RetryPause NO_WAITING = duration -> {};

    /** The shared contract mapper, so the answer is read exactly as every other JSON is. */
    private static final ObjectMapper MAPPER = JacksonConfig.contractObjectMapper();

    /** Where the consumed contracts are vendored, with their provenance. */
    private static final Path VENDORED = Path.of(
            "specs", "002-consolidate-progression-leg", "contracts", "systemdocgenerator");

    /** The request every command case makes, and the one the assembler will make. */
    private static final RenderRequest REQUEST =
            new RenderRequest(PAYLOAD_FILE_ID, BATCH_ID, TEMPLATE, FORMAT, ORIGINATING_SOURCE);

    private WireMockServer sdg;

    @BeforeEach
    void startSystemDocGenerator() {
        sdg = new WireMockServer(wireMockConfig().dynamicPort());
        sdg.start();
    }

    @AfterEach
    void stopSystemDocGenerator() {
        sdg.stop();
    }

    private SystemDocGeneratorClient client() {
        final SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        return new SystemDocGeneratorClient(
                RestClient.builder()
                        .baseUrl(sdg.baseUrl())
                        .requestFactory(requestFactory)
                        .build(),
                SYSTEM_USER_ID,
                MAPPER);
    }

    private static String queryPath(final UUID payloadFileId) {
        return QUERY_PATH_PREFIX + payloadFileId;
    }

    private void commandAnswering(final int status) {
        sdg.stubFor(post(urlEqualTo(COMMAND_PATH)).willReturn(aResponse().withStatus(status)));
    }

    private void queryAnswering(final int status, final String body) {
        sdg.stubFor(get(urlEqualTo(queryPath(PAYLOAD_FILE_ID))).willReturn(aResponse()
                .withStatus(status)
                .withHeader("Content-Type", QUERY_MEDIA_TYPE)
                .withBody(body)));
    }

    /**
     * Asks for the render and states, as an assertion, that the request was accepted.
     *
     * <p>Every case about the wire goes through here rather than calling the client directly, so a
     * client that refuses a request the contract accepts fails on a sentence about the contract
     * rather than on whatever exception it happened to raise.
     */
    private void render(final CallerIdentity caller) {
        assertThatCode(() -> client().requestRender(REQUEST, caller))
                .as("systemdocgenerator answered 202, which is the contract's one success")
                .doesNotThrowAnyException();
    }

    /** The same, for the query: the answer is returned once it is established there was one. */
    private Optional<DocumentStatus> answerAbout(final UUID payloadFileId) {
        final AtomicReference<Optional<DocumentStatus>> answer =
                new AtomicReference<>(Optional.empty());
        assertThatCode(() -> answer.set(client().query(payloadFileId, CALLER)))
                .as("the reconciler's query was answered rather than refused")
                .doesNotThrowAnyException();
        return answer.get();
    }

    /** The body the command actually carried, parsed. */
    private JsonNode sentBody() {
        return MAPPER.readTree(
                sdg.findAll(postRequestedFor(urlEqualTo(COMMAND_PATH))).getFirst().getBodyAsString());
    }

    /** One vendored schema, read from the file this repository holds it in. */
    private static JsonNode vendoredSchema(final String fileName) {
        final Path schema = VENDORED.resolve(fileName);
        try {
            return MAPPER.readTree(Files.readString(schema, StandardCharsets.UTF_8));
        } catch (IOException unreadable) {
            throw new UncheckedIOException(
                    "the vendored systemdocgenerator contract must be committed at " + schema,
                    unreadable);
        }
    }

    private static List<String> declaredProperties(final JsonNode schema) {
        return List.copyOf(schema.get("properties").propertyNames());
    }

    private static List<String> requiredProperties(final JsonNode schema) {
        final List<String> names = new ArrayList<>();
        schema.get("required").forEach(name -> names.add(name.stringValue()));
        return names;
    }

    private static List<String> enumValues(final JsonNode schema, final String property) {
        final List<String> values = new ArrayList<>();
        schema.get("properties").get(property).get("enum").forEach(
                value -> values.add(value.stringValue()));
        return values;
    }

    /** A query answer carrying the four fields the schema requires, plus whatever a case adds. */
    private static String queryAnswerWith(final String optionalFields) {
        return "{\"payloadFileServiceId\":\"" + PAYLOAD_FILE_ID + "\","
                + "\"templateIdentifier\":\"" + TEMPLATE + "\","
                + "\"conversionFormat\":\"" + FORMAT + "\","
                + "\"requestedTime\":\"" + REQUESTED_TIME + "\""
                + optionalFields
                + "}";
    }

    @Nested
    @DisplayName("the command on the wire")
    class Command {

        @Test
        @DisplayName("carries the contract path, media type and identity header")
        void the_command_carries_the_contract_path_media_type_and_identity() {
            commandAnswering(ACCEPTED);

            render(CALLER);

            sdg.verify(postRequestedFor(urlEqualTo(COMMAND_PATH))
                    .withHeader("Content-Type", equalTo(COMMAND_MEDIA_TYPE))
                    .withHeader(IDENTITY_HEADER, equalTo(SHARING_USER)));
        }

        @Test
        @DisplayName("is made as the configured identity when the run names nobody")
        void the_command_is_made_as_the_configured_identity_when_the_run_names_nobody() {
            commandAnswering(ACCEPTED);

            render(CallerIdentity.SYSTEM);

            sdg.verify(postRequestedFor(urlEqualTo(COMMAND_PATH))
                    .withHeader(IDENTITY_HEADER, equalTo(SYSTEM_USER_ID)));
        }

        /**
         * The five fields, verbatim. Four of them are constants of the contract and the fifth pair -
         * the payload id and the batch id - is what makes the request this batch's rather than any
         * other's.
         */
        @Test
        @DisplayName("sends the five fields the contract declares and no sixth")
        void the_body_is_the_five_fields_the_contract_declares() {
            commandAnswering(ACCEPTED);

            render(CALLER);

            final JsonNode body = sentBody();
            assertThat(List.copyOf(body.propertyNames()))
                    .as("a sixth field is a change to somebody else's contract")
                    .containsExactlyInAnyOrder("templateIdentifier", "conversionFormat",
                            "payloadFileServiceId", "sourceCorrelationId", "originatingSource");
            assertThat(body.get("templateIdentifier").stringValue()).isEqualTo(TEMPLATE);
            assertThat(body.get("conversionFormat").stringValue()).isEqualTo(FORMAT);
            assertThat(body.get("payloadFileServiceId").stringValue())
                    .isEqualTo(PAYLOAD_FILE_ID.toString());
        }

        /**
         * The correlation, on its own, because it is the whole of the completion leg. The outcome
         * arrives on a topic the estate publishes to and carries no batch of its own: this id is how
         * a {@code document-available} finds the rows it belongs to.
         */
        @Test
        @DisplayName("correlates the render to the batch by the batch's own id")
        void the_correlation_is_the_batch_id_the_outcome_event_will_carry_back() {
            commandAnswering(ACCEPTED);

            render(CALLER);

            assertThat(sentBody().get("sourceCorrelationId").stringValue())
                    .as("an event whose sourceCorrelationId is not a batch id is an event that "
                            + "reaches no rows")
                    .isEqualTo(BATCH_ID.toString());
        }

        /**
         * progression's leg is still deployed and still subscribed to {@code public.event}; its
         * processor keeps the events whose source is its own. Naming this service is what makes the
         * two subscriptions inert to each other during the cutover.
         */
        @Test
        @DisplayName("names this service as the originating source")
        void the_originating_source_keeps_progressions_listener_out_of_these_documents() {
            commandAnswering(ACCEPTED);

            render(CALLER);

            assertThat(sentBody().get("originatingSource").stringValue())
                    .isEqualTo(ORIGINATING_SOURCE);
        }

        /**
         * The vendored schema is {@code additionalProperties: false}, so a field this service
         * invents is a 400 rather than a field systemdocgenerator ignores. Reading the schema here
         * rather than restating it means a re-vendoring that moves the contract fails this suite.
         */
        @Test
        @DisplayName("sends a body the vendored generate-document schema declares")
        void the_body_is_one_the_vendored_command_schema_declares() {
            commandAnswering(ACCEPTED);

            render(CALLER);

            final JsonNode schema = vendoredSchema("systemdocgenerator.generate-document.json");
            final List<String> sent = List.copyOf(sentBody().propertyNames());
            assertThat(sent)
                    .as("the schema refuses additional properties")
                    .isSubsetOf(declaredProperties(schema));
            assertThat(sent)
                    .as("every property the schema requires")
                    .containsAll(requiredProperties(schema));
            assertThat(enumValues(schema, "conversionFormat"))
                    .as("pdf is one of the three formats the schema permits")
                    .contains(sentBody().get("conversionFormat").stringValue());
        }

        @Test
        @DisplayName("asks once and returns, leaving the document to the event")
        void an_accepted_request_asks_once_and_returns() {
            commandAnswering(ACCEPTED);

            render(CALLER);

            assertThat(sdg.getAllServeEvents())
                    .as("202 says a render was asked for, not that a document exists")
                    .hasSize(1);
        }
    }

    @Nested
    @DisplayName("202 and nothing else is success")
    class ContractSuccess {

        /**
         * A 2xx that is not 202 means something other than the command endpoint answered. Treating
         * it as accepted would move the batch to GENERATING for a render nothing was asked for, and
         * the batch would then wait out its grace period for an event that cannot come.
         */
        @ParameterizedTest(name = "{0} is not the success the contract defines")
        @ValueSource(ints = {200, 201, 204})
        @DisplayName("only_202_is_success")
        void only_202_is_success(final int status) {
            commandAnswering(status);

            assertThat(catchThrowable(() -> client().requestRender(REQUEST, CALLER)))
                    .isInstanceOf(GenerationFailedException.class)
                    .asInstanceOf(InstanceOfAssertFactories.type(GenerationFailedException.class))
                    .satisfies(failure -> {
                        assertThat(failure.reason())
                                .isEqualTo(BatchFailureReason.RENDER_REQUEST_REJECTED);
                        assertThat(failure.classification())
                                .as("asking again cannot turn a 200 into a 202")
                                .isEqualTo(FailureClassification.NON_TRANSIENT);
                        assertThat(failure.responseCode())
                                .as("the batch row records what answered, not what was hoped for")
                                .isEqualTo(OptionalInt.of(status));
                    });
        }

        @ParameterizedTest(name = "{0} is a refusal the batch is failed on")
        @ValueSource(ints = {400, 401, 403, 404, 422})
        @DisplayName("a refusal is a rejection carrying its status")
        void a_refusal_is_a_rejection_carrying_its_status(final int status) {
            commandAnswering(status);

            assertThat(catchThrowable(() -> client().requestRender(REQUEST, CALLER)))
                    .isInstanceOf(GenerationFailedException.class)
                    .asInstanceOf(InstanceOfAssertFactories.type(GenerationFailedException.class))
                    .satisfies(failure -> {
                        assertThat(failure.reason())
                                .isEqualTo(BatchFailureReason.RENDER_REQUEST_REJECTED);
                        assertThat(failure.classification())
                                .isEqualTo(FailureClassification.NON_TRANSIENT);
                        assertThat(failure.responseCode()).isEqualTo(OptionalInt.of(status));
                    });
        }
    }

    @Nested
    @DisplayName("what another attempt could change")
    class Taxonomy {

        /**
         * The C3 promise, held to across two contexts. It is not enough that this client's list of
         * retryable statuses reads like the submission client's: the two are put in front of the
         * same status and their classifications are compared, so a client that grew an opinion of
         * its own about a 429 fails here rather than at 18:00 on a busy renderer.
         */
        @ParameterizedTest(name = "{0} is classified as the submission client classifies it")
        @ValueSource(ints = {200, 201, 204, 400, 401, 403, 404, 408, 422, 429, 500, 502, 503})
        @DisplayName("retry_taxonomy_matches_the_submission_client")
        void retry_taxonomy_matches_the_submission_client(final int status) {
            assertThat(rendererClassificationOf(status))
                    .as("both clients hold the same RetryPolicy, so neither can decide on its own "
                            + "what is worth asking again")
                    .isEqualTo(submissionClassificationOf(status));
        }

        @ParameterizedTest(name = "{0} is worth asking again")
        @ValueSource(ints = {408, 429, 500, 502, 503})
        @DisplayName("a transient answer is handed back for the run to ask again")
        void a_transient_answer_is_handed_back_for_the_run_to_ask_again(final int status) {
            commandAnswering(status);

            assertThat(catchThrowable(() -> client().requestRender(REQUEST, CALLER)))
                    .isInstanceOf(GenerationFailedException.class)
                    .asInstanceOf(InstanceOfAssertFactories.type(GenerationFailedException.class))
                    .satisfies(failure -> {
                        assertThat(failure.classification())
                                .isEqualTo(FailureClassification.TRANSIENT);
                        assertThat(failure.reason())
                                .isEqualTo(BatchFailureReason.RENDER_REQUEST_FAILED);
                        assertThat(failure.responseCode()).isEqualTo(OptionalInt.of(status));
                    });
        }

        /**
         * A request whose answer never arrived may still have been made. It is transient for that
         * reason and carries no status at all: an invented one would say an attempt was answered
         * when nothing answered.
         */
        @Test
        @DisplayName("an unanswered attempt is transient and records no status")
        void an_unanswered_attempt_is_transient_and_records_no_status() {
            sdg.stubFor(post(urlEqualTo(COMMAND_PATH))
                    .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

            assertThat(catchThrowable(() -> client().requestRender(REQUEST, CALLER)))
                    .isInstanceOf(GenerationFailedException.class)
                    .asInstanceOf(InstanceOfAssertFactories.type(GenerationFailedException.class))
                    .satisfies(failure -> {
                        assertThat(failure.classification())
                                .isEqualTo(FailureClassification.TRANSIENT);
                        assertThat(failure.responseCode()).isEmpty();
                    });
        }

        /**
         * The division of labour, asserted rather than assumed. This client is handed a request and
         * a caller and is told nothing about what is left of the run, so a loop here would spend a
         * budget it cannot see; the service that holds the deadline holds the loop (T039).
         */
        @Test
        @DisplayName("asks once and leaves the waiting to the run that holds the deadline")
        void the_client_asks_once_and_leaves_the_waiting_to_the_run() {
            commandAnswering(503);

            assertThat(catchThrowable(() -> client().requestRender(REQUEST, CALLER)))
                    .isInstanceOf(GenerationFailedException.class);

            assertThat(sdg.getAllServeEvents())
                    .as("the run deadline bounds the retrying, and it is not this client's")
                    .hasSize(1);
        }

        private FailureClassification rendererClassificationOf(final int status) {
            commandAnswering(status);
            final Throwable refused = catchThrowable(() -> client().requestRender(REQUEST, CALLER));
            assertThat(refused)
                    .as("systemdocgenerator answered %s, which is not the contract's 202", status)
                    .isInstanceOf(GenerationFailedException.class);
            return ((GenerationFailedException) refused).classification();
        }

        /**
         * The submission client's own verdict on the same status, made against the same server.
         *
         * <p>One attempt, so the two are compared on the same question - what one answer means -
         * rather than on how many times each is willing to ask. The waiting is a no-op and the
         * deadline is unreachable, so neither can end this case.
         */
        private FailureClassification submissionClassificationOf(final int status) {
            sdg.stubFor(post(urlEqualTo(ProgressionCommandGateway.PATH))
                    .willReturn(aResponse().withStatus(status)));
            final Instant now = Instant.parse("2026-09-04T17:00:00Z");
            final SimpleClientHttpRequestFactory requestFactory =
                    new SimpleClientHttpRequestFactory();
            requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
            requestFactory.setReadTimeout(READ_TIMEOUT);
            final ProgressionCommandGateway gateway = new ProgressionCommandGateway(
                    RestClient.builder()
                            .baseUrl(sdg.baseUrl())
                            .requestFactory(requestFactory)
                            .build(),
                    SYSTEM_USER_ID,
                    Map.of(),
                    new RetryPolicy(1, Duration.ofMillis(500), Duration.ofSeconds(5),
                            CONNECT_TIMEOUT.plus(READ_TIMEOUT)),
                    NO_WAITING,
                    AdjustableClock.startingAt(now));
            final Throwable refused = catchThrowable(() -> gateway.post(
                    "{}".getBytes(StandardCharsets.UTF_8),
                    CALLER,
                    now.plus(Duration.ofHours(1))));
            assertThat(refused).isInstanceOf(SubmissionFailedException.class);
            return ((SubmissionFailedException) refused).classification();
        }
    }

    @Nested
    @DisplayName("the query, which is the reconciler's alone")
    class Query {

        @Test
        @DisplayName("carries the contract path, accept header and identity")
        void the_query_carries_the_contract_path_accept_and_identity() {
            queryAnswering(200, queryAnswerWith(""));

            answerAbout(PAYLOAD_FILE_ID);

            sdg.verify(getRequestedFor(urlEqualTo(queryPath(PAYLOAD_FILE_ID)))
                    .withHeader("Accept", equalTo(QUERY_MEDIA_TYPE))
                    .withHeader(IDENTITY_HEADER, equalTo(SHARING_USER)));
        }

        @Test
        @DisplayName("maps a generated document as the document it is")
        void the_query_maps_a_generated_document() {
            queryAnswering(200, queryAnswerWith(
                    ",\"documentFileServiceId\":\"" + DOCUMENT_FILE_ID + "\","
                            + "\"generatedTime\":\"" + GENERATED_TIME + "\""));

            assertThat(answerAbout(PAYLOAD_FILE_ID))
                    .contains(new DocumentStatus(DOCUMENT_FILE_ID, GENERATED_TIME, null, null));
        }

        @Test
        @DisplayName("maps a refusal with the time and systemdocgenerator's own reason")
        void the_query_maps_a_refusal_with_its_reason() {
            queryAnswering(200, queryAnswerWith(
                    ",\"failedTime\":\"" + FAILED_TIME + "\","
                            + "\"reason\":\"" + SDG_TEXT_MARKER + "\""));

            assertThat(answerAbout(PAYLOAD_FILE_ID))
                    .contains(new DocumentStatus(null, null, FAILED_TIME, SDG_TEXT_MARKER));
        }

        /**
         * The answer with no verdict in it, which is a real answer and not an absent one:
         * systemdocgenerator has the payload and has not finished with it. Whether that is a
         * timeout is the reconciler's judgement, because the grace period is the reconciler's.
         */
        @Test
        @DisplayName("maps an answer with no verdict as an answer with no verdict")
        void the_query_maps_an_answer_with_no_verdict() {
            queryAnswering(200, queryAnswerWith(""));

            assertThat(answerAbout(PAYLOAD_FILE_ID))
                    .contains(new DocumentStatus(null, null, null, null));
        }

        @Test
        @DisplayName("answers nothing about a payload systemdocgenerator has no record of")
        void a_payload_systemdocgenerator_has_no_record_of_answers_nothing() {
            queryAnswering(404, "");

            assertThat(answerAbout(PAYLOAD_FILE_ID))
                    .as("nothing to say is not the same as a render that failed")
                    .isEmpty();
        }

        /**
         * The four this service reads are exactly the four the vendored schema leaves optional, so a
         * fifth optional field appearing upstream is a decision somebody has to make rather than a
         * value silently dropped.
         */
        @Test
        @DisplayName("reads the four optional fields the vendored query schema declares")
        void the_four_optional_fields_are_the_ones_the_vendored_schema_declares() {
            final JsonNode schema = vendoredSchema("systemdocgenerator.query.document.json");
            final List<String> optional = new ArrayList<>(declaredProperties(schema));
            optional.removeAll(requiredProperties(schema));

            assertThat(optional).containsExactlyInAnyOrder(
                    "documentFileServiceId", "generatedTime", "failedTime", "reason");
        }

        /**
         * A query that could not be answered is not a generation that failed. The batch stays
         * GENERATING and the reconciler decides what to do about a renderer that will not answer;
         * what this client owes is the classification and the status, and no verdict of its own.
         */
        @Test
        @DisplayName("a query that could not be answered is not a generation that failed")
        void a_query_that_could_not_be_answered_is_not_a_generation_that_failed() {
            queryAnswering(503, "");

            assertThat(catchThrowable(() -> client().query(PAYLOAD_FILE_ID, CALLER)))
                    .isInstanceOf(GenerationFailedException.class)
                    .asInstanceOf(InstanceOfAssertFactories.type(GenerationFailedException.class))
                    .satisfies(failure -> {
                        assertThat(failure.classification())
                                .isEqualTo(FailureClassification.TRANSIENT);
                        assertThat(failure.responseCode()).isEqualTo(OptionalInt.of(503));
                    });
        }

        /**
         * systemdocgenerator's {@code reason} is another system's free text about a document whose
         * every defendant is a child. It is carried to {@code sdg_reason}, where support can read
         * it, and it is never written to a log index (constitution Principle VII).
         */
        @Test
        @DisplayName("never writes systemdocgenerator's own words above DEBUG")
        void systemdocgenerators_own_words_are_never_written_above_debug() {
            queryAnswering(200, queryAnswerWith(
                    ",\"failedTime\":\"" + FAILED_TIME + "\","
                            + "\"reason\":\"" + SDG_TEXT_MARKER + "\""));

            try (CapturedLog log = CapturedLog.capturing(SystemDocGeneratorClient.class)) {
                assertThat(answerAbout(PAYLOAD_FILE_ID))
                        .as("the reason reaches sdg_reason, which is where support reads it")
                        .contains(new DocumentStatus(null, null, FAILED_TIME, SDG_TEXT_MARKER));

                assertThat(log.events().stream()
                        .filter(event -> event.getLevel().isGreaterOrEqual(Level.INFO))
                        .map(ILoggingEvent::getFormattedMessage)
                        .toList())
                        .as("a log line reaches an index the whole estate can read")
                        .noneMatch(line -> line.contains(SDG_TEXT_MARKER));
            }
        }
    }
}
