package uk.gov.hmcts.cp.courtregister.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.courtregister.adapter.http.RetryPause;
import uk.gov.hmcts.cp.courtregister.adapter.http.RetryPolicy;
import uk.gov.hmcts.cp.courtregister.config.GenerationMetrics;
import uk.gov.hmcts.cp.courtregister.config.JacksonConfig;
import uk.gov.hmcts.cp.courtregister.domain.BatchFailureReason;
import uk.gov.hmcts.cp.courtregister.domain.BatchStatus;
import uk.gov.hmcts.cp.courtregister.domain.CallerIdentity;
import uk.gov.hmcts.cp.courtregister.domain.CourtCentreDay;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterDefendant;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterDocument;
import uk.gov.hmcts.cp.courtregister.domain.Deadline;
import uk.gov.hmcts.cp.courtregister.domain.FailureClassification;
import uk.gov.hmcts.cp.courtregister.domain.GenerationFailedException;
import uk.gov.hmcts.cp.courtregister.domain.PayloadStoreUnavailableException;
import uk.gov.hmcts.cp.courtregister.domain.RecordedFlagState;
import uk.gov.hmcts.cp.courtregister.domain.RegisterBatch;
import uk.gov.hmcts.cp.courtregister.domain.RegisterRecord;
import uk.gov.hmcts.cp.courtregister.domain.RenderRequest;
import uk.gov.hmcts.cp.courtregister.pipeline.PdfPayloadMapper;
import uk.gov.hmcts.cp.courtregister.support.AdjustableClock;
import uk.gov.hmcts.cp.courtregister.support.CapturedLog;
import uk.gov.hmcts.cp.courtregister.support.PersonalDataMarkers;

/**
 * One batch, from the payload to the render request, and the order it does those in.
 *
 * <p>The order is the subject, not a detail of it. Every other suite in this phase asks whether one
 * step is right; this one asks whether the steps can be interrupted in the wrong place and leave
 * something nobody can attribute. Three writes have to happen in one sequence - the payload file id
 * is minted and written onto the batch, then the payload is inserted under it, then the render is
 * asked for - and each inversion has its own way of going wrong: an id used before it is recorded is
 * a document that comes back correlated to nothing this service can find, and a render asked for
 * before the payload is stored is a document systemdocgenerator renders from a file that is not
 * there yet.
 *
 * <p><strong>Each step has one failure and one bounded reason.</strong> The four this suite pins are
 * the four a run can produce on its own account - a payload that could not be assembled, a payload
 * that could not be stored, a request that was refused, and a request that never got an answer
 * inside the run's budget - and none of them names a completion mechanism, because nobody outside
 * this service answered for any of them ({@code BatchFailureReason.isGeneratorAttributed()}). The
 * two that are somebody's answer arrive later, on the public-event topic, and belong to
 * {@code DocumentOutcomeSinkTest}.
 *
 * <p><strong>The service returns a verdict; it does not throw one.</strong> That is defect fix P5
 * and it is what {@code assembly_failure_fails_the_batch_and_the_run_continues} pins: progression's
 * {@code processRequests} catches the stream exception, logs it and walks on, leaving no state on
 * any row and no count anywhere, so a batch that failed and a batch that was never there look
 * identical from outside. Per-batch isolation was the right half of that - one bad batch must not
 * cost the estate a night's registers - and it is kept: the batch is failed FAILED
 * {@code ASSEMBLY_FAILED}, counted, and the next batch of the run is still asked for.
 *
 * <p><strong>What GENERATING claims, and what it does not.</strong> A 202 is the only success the
 * contract admits and it moves the batch to GENERATING, which says a render was asked for and not
 * that a document exists. So this suite asserts that the batches counter - the terminal-outcome
 * series - does <em>not</em> move on an accepted request, and that nothing here waits for a
 * document.
 *
 * <p>The privacy case is last and is a "never": the batch this service is rendering is a register
 * every defendant on which is a child, so the run's own lines carry the batch identity and bounded
 * codes and nothing that came out of a document (constitution Principle VII).
 */
@ExtendWith(SoftAssertionsExtension.class)
@DisplayName("one batch, from the payload to the render request")
class RegisterGenerationServiceTest {

    /** Quoted by every case, so the red run names the seam it is waiting on. */
    private static final String PENDING =
            "T049 implements the generation service; this is its red run";

    /** Returned when a meter is absent, so a missing instrument fails as an assertion. */
    private static final double ABSENT = -1;

    /** The instant every case that is not about the budget is measured from: 18:00, as scheduled. */
    private static final Instant NOW = Instant.parse("2026-08-20T17:00:00Z");

    private static final UUID BATCH_ID = UUID.fromString("6f6b1a8e-4c67-4f0f-9b2b-5f0f6a3a1d21");
    private static final UUID COURT_CENTRE_ID =
            UUID.fromString("853b1ff8-fc2a-44d1-a621-0cd16419f54a");
    private static final UUID OTHER_BATCH_ID =
            UUID.fromString("2b2f4d38-9d2e-4a41-9f4e-1a1c0b6d7e55");
    private static final LocalDate REGISTER_DATE = LocalDate.parse("2026-08-20");

    /** The file name progression builds, as the batch's first record named it. */
    private static final String FILE_NAME = "courtregister_2026-08-20.json";

    /** The template and the format, spelled as systemdocgenerator's own contract spells them. */
    private static final String TEMPLATE = "OEE_Layout5";
    private static final String FORMAT = "pdf";

    /** This service's own name, which is what keeps progression's still-deployed listener out. */
    private static final String ORIGINATING_SOURCE = "CourtRegisterService";

    /** The key progression's generator reads the batch's documents from. */
    private static final String DOCUMENT_REQUESTS = "courtRegisterDocumentRequests";

    private static final int MAX_ATTEMPTS = 3;
    private static final Duration INITIAL_BACKOFF = Duration.ofMillis(200);
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(2);

    /**
     * A declared attempt cost small enough that the attempt is never the thing that does not fit, so
     * a case about the run's budget is about the budget.
     */
    private static final Duration CHEAP_ATTEMPT = Duration.ofMillis(20);

    /** The status the contract admits, and the only one. */
    private static final int ACCEPTED = 202;

    /** A 2xx that is not 202: something other than the command endpoint answered. */
    private static final int NOT_THE_COMMAND_ENDPOINT = 200;

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final GenerationMetrics metrics = new GenerationMetrics(registry);
    private final ObjectMapper objectMapper = JacksonConfig.contractObjectMapper();
    private final RegisterStore store = mock(RegisterStore.class);
    private final PdfPayloadMapper payloadMapper = mock(PdfPayloadMapper.class);
    private final PayloadFileStore payloadFileStore = mock(PayloadFileStore.class);
    private final DocumentRenderer renderer = mock(DocumentRenderer.class);

    private RecordingPause pause;
    private AdjustableClock clock;

    /** What the ported generator answers with; opaque here, and pinned by T031 rather than by this. */
    private JsonNode payload;

    @InjectSoftAssertions
    private SoftAssertions softly;

    @BeforeEach
    void seedTheRun() {
        clock = AdjustableClock.startingAt(NOW);
        pause = new RecordingPause();
        payload = objectMapper.createObjectNode().put("registerDate", "2026-08-20");
        when(store.batched(BATCH_ID)).thenReturn(List.of(record("Fred Smith")));
        when(payloadMapper.mapPayload(any())).thenReturn(payload);
    }

    /** The service a case that is not about the budget uses. */
    private RegisterGenerationService service() {
        return service(MAX_ATTEMPTS, CHEAP_ATTEMPT);
    }

    private RegisterGenerationService service(
            final int maxAttempts, final Duration attemptWorstCase) {
        return new RegisterGenerationService(store, payloadMapper, payloadFileStore, renderer,
                objectMapper,
                new RetryPolicy(maxAttempts, INITIAL_BACKOFF, MAX_BACKOFF, attemptWorstCase),
                pause, metrics, clock);
    }

    /**
     * A budget no case that is not about the deadline can exhaust: the clock moves only when a wait
     * is taken, so an hour is unreachable by construction.
     */
    private static Deadline farDeadline() {
        return Deadline.startingAt(NOW, Duration.ofHours(1));
    }

    /** The batch as the assembler left it: durable, PENDING, and carrying no payload id yet. */
    private static RegisterBatch batch(final UUID batchId) {
        return new RegisterBatch(batchId, COURT_CENTRE_ID, "B01LY", "Lavender Hill", REGISTER_DATE,
                FILE_NAME, null, null, BatchStatus.PENDING, null, null, true, null, NOW, null, null,
                null, null, 0, null, 0);
    }

    private static RegisterBatch batch() {
        return batch(BATCH_ID);
    }

    /**
     * One recorded register, carrying a defendant name so that the privacy case has something it
     * would be a breach to log.
     *
     * @param defendantName the name that must never reach a line at INFO or above
     * @return the record, as the batch half reads it back
     */
    private static RegisterRecord record(final String defendantName) {
        final UUID hearingId = UUID.randomUUID();
        return new RegisterRecord(UUID.randomUUID(), hearingId, NOW,
                new CourtCentreDay(COURT_CENTRE_ID, REGISTER_DATE), NOW, FILE_NAME, "Applicant",
                RecordedFlagState.ON,
                new CourtRegisterDocument("2026-08-20T09:00:00Z", "2026-08-20T09:00:00Z",
                        hearingId.toString(), COURT_CENTRE_ID.toString(), FILE_NAME, "Applicant",
                        null, null,
                        List.of(new CourtRegisterDefendant(UUID.randomUUID().toString(),
                                defendantName, "2009-11-23", null, null, null, null, null, null,
                                null, null, null, null, null))));
    }

    /**
     * Puts one batch in front of the service and asks it to request a render.
     *
     * <p>The call is made inside {@code assertThatCode(...).doesNotThrowAnyException()} so that the
     * seam's refusal is recorded as an assertion rather than ending the case, and the outcome it did
     * not produce is then asserted on as {@code null} - the red run is the outcome and the green run
     * is the same assertions unchanged.
     *
     * @param requested the batch being asked about
     * @param deadline  the run's requesting bound
     * @return what the service answered, or {@code null} where the seam refused
     */
    private BatchOutcome request(final RegisterBatch requested, final Deadline deadline) {
        return request(service(), requested, deadline);
    }

    private BatchOutcome request(final RegisterGenerationService service,
            final RegisterBatch requested, final Deadline deadline) {
        final AtomicReference<BatchOutcome> answered = new AtomicReference<>();
        softly.assertThatCode(() -> answered.set(service.request(requested, deadline)))
                .as(PENDING)
                .doesNotThrowAnyException();
        return answered.get();
    }

    private BatchOutcome request() {
        return request(batch(), farDeadline());
    }

    /** The payload id the service minted, as the batch row was told it. */
    private UUID mintedId() {
        final ArgumentCaptor<UUID> minted = ArgumentCaptor.forClass(UUID.class);
        verify(store).markPayloadMinted(eq(BATCH_ID), minted.capture());
        return minted.getValue();
    }

    private RenderRequest renderRequest() {
        final ArgumentCaptor<RenderRequest> asked = ArgumentCaptor.forClass(RenderRequest.class);
        verify(renderer).requestRender(asked.capture(), any());
        return asked.getValue();
    }

    private PayloadMetadata storedMetadata() {
        final ArgumentCaptor<PayloadMetadata> written =
                ArgumentCaptor.forClass(PayloadMetadata.class);
        verify(payloadFileStore).store(any(), any(), written.capture());
        return written.getValue();
    }

    private double batches(final BatchStatus outcome) {
        return count(GenerationMetrics.BATCHES, GenerationMetrics.OUTCOME_TAG,
                outcome.name().toLowerCase(Locale.ROOT).replace('_', '-'));
    }

    private double requests(final int responseCode) {
        return count(GenerationMetrics.GENERATION_REQUEST, GenerationMetrics.RESPONSE_CODE_TAG,
                String.valueOf(responseCode));
    }

    private double count(final String name, final String tag, final String value) {
        final Counter counter = registry.find(name).tag(tag, value).counter();
        return counter == null ? ABSENT : counter.count();
    }

    /**
     * Every line a log index would keep, at or above the given level.
     *
     * @param log   what the run wrote while the case ran
     * @param level the lowest level a reader outside this pod ever sees
     * @return the formatted messages of those events
     */
    private static List<String> atOrAbove(final CapturedLog log, final Level level) {
        return log.events().stream()
                .filter(event -> event.getLevel().isGreaterOrEqual(level))
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    /** A refusal systemdocgenerator will give the same answer to however often it is asked. */
    private static GenerationFailedException refusal() {
        return new GenerationFailedException(FailureClassification.NON_TRANSIENT,
                BatchFailureReason.RENDER_REQUEST_REJECTED, NOT_THE_COMMAND_ENDPOINT);
    }

    /** A failure another attempt inside the run deadline could answer differently. */
    private static GenerationFailedException transientFailure() {
        return new GenerationFailedException(FailureClassification.TRANSIENT,
                BatchFailureReason.RENDER_REQUEST_FAILED);
    }

    /**
     * The three writes that cannot be reordered without losing a document.
     */
    @Nested
    @DisplayName("the payload id, written down before it is used")
    class MintingThePayloadId {

        @Test
        void the_payload_id_should_be_on_the_batch_row_before_the_payload_is_stored() {
            request();

            final InOrder order = inOrder(store, payloadFileStore, renderer);
            order.verify(store).markPayloadMinted(eq(BATCH_ID), any());
            order.verify(payloadFileStore).store(any(), any(), any());
            order.verify(renderer).requestRender(any(), any());
            order.verify(store).markRequested(eq(BATCH_ID), any());
        }

        @Test
        void the_id_written_down_should_be_the_one_the_payload_is_stored_under() {
            request();

            final ArgumentCaptor<UUID> stored = ArgumentCaptor.forClass(UUID.class);
            verify(payloadFileStore).store(stored.capture(), any(), any());

            softly.assertThat(stored.getValue())
                    .as("an insert made under an id other than the one the batch row carries is a "
                            + "payload this service cannot find again, and a document it could not "
                            + "attribute if one came back")
                    .isEqualTo(mintedId());
        }

        @Test
        void the_id_written_down_should_be_the_one_the_render_is_asked_about() {
            request();

            final ArgumentCaptor<UUID> requested = ArgumentCaptor.forClass(UUID.class);
            verify(store).markRequested(eq(BATCH_ID), requested.capture());

            softly.assertThat(renderRequest().payloadFileId())
                    .as("systemdocgenerator renders whatever payloadFileServiceId names, so this is "
                            + "the id that decides which payload becomes the document")
                    .isEqualTo(mintedId());
            softly.assertThat(requested.getValue())
                    .as("and the mark that moves the batch names the same one, so the row and the "
                            + "request cannot disagree about which payload was rendered")
                    .isEqualTo(mintedId());
        }

        @Test
        void the_payload_id_should_be_its_own_identity_and_never_the_batchs() {
            request();

            softly.assertThat(mintedId())
                    .as("two identities doing two jobs: the batch id correlates the outcome event "
                            + "back to rows and the payload id names a file in somebody else's "
                            + "database; one value doing both would make a file-service id a "
                            + "correlation key")
                    .isNotEqualTo(BATCH_ID);
        }
    }

    /**
     * What the renderer is given to render, and what is written beside it.
     */
    @Nested
    @DisplayName("the payload the batch is turned into")
    class ThePayload {

        @Test
        void the_mapper_should_be_given_the_batchs_registers_under_progressions_own_key() {
            final List<RegisterRecord> assembled = List.of(record("Fred Smith"), record("Ada Khan"));
            when(store.batched(BATCH_ID)).thenReturn(assembled);

            request();

            final ArgumentCaptor<JsonNode> mapped = ArgumentCaptor.forClass(JsonNode.class);
            verify(payloadMapper).mapPayload(mapped.capture());

            softly.assertThat(mapped.getValue().get(DOCUMENT_REQUESTS))
                    .as("the ported generator was written against the shape progression's own "
                            + "CourtRegisterGenerated event carried - every register of the batch, "
                            + "in the order the batch holds them, under that one key; a wrapper "
                            + "spelled any other way is a payload it reads as empty")
                    .isEqualTo(objectMapper.createArrayNode()
                            .add(objectMapper.valueToTree(assembled.get(0).document()))
                            .add(objectMapper.valueToTree(assembled.get(1).document())));
        }

        @Test
        void the_payload_stored_should_be_exactly_what_the_mapper_produced() {
            request();

            final ArgumentCaptor<JsonNode> written = ArgumentCaptor.forClass(JsonNode.class);
            verify(payloadFileStore).store(any(), written.capture(), any());

            softly.assertThat(written.getValue())
                    .as("the port is byte-identical to progression's generator (T031) and this "
                            + "service adds nothing to its answer on the way past; a field added "
                            + "here would be a template change nobody agreed to")
                    .isSameAs(payload);
        }

        @Test
        void the_metadata_should_carry_progressions_five_keys_as_progression_spells_them() {
            request();

            final PayloadMetadata metadata = storedMetadata();

            softly.assertThat(metadata.fileName())
                    .as("the first record's file name, as progression named the document")
                    .isEqualTo(FILE_NAME);
            softly.assertThat(metadata.conversionFormat())
                    .as("the format systemdocgenerator is asked for and the format the metadata "
                            + "row records are one decision, not two")
                    .isEqualTo(FORMAT);
            softly.assertThat(metadata.templateName())
                    .as("the file service is not this service's database: the keys and their "
                            + "values are progression's, unchanged")
                    .isEqualTo(TEMPLATE);
            softly.assertThat(metadata.numberOfPages())
                    .as("the page count progression writes, which is one")
                    .isEqualTo(1);
            softly.assertThat(metadata.fileSize())
                    .as("the payload's own size, so the metadata row describes the content row "
                            + "beside it rather than a number nobody derived")
                    .isEqualTo(objectMapper.writeValueAsString(payload)
                            .getBytes(StandardCharsets.UTF_8).length);
        }
    }

    /**
     * The file service could not be written to, so there is nothing to render.
     */
    @Nested
    @DisplayName("a payload that could not be stored")
    class WhenThePayloadCannotBeStored {

        @BeforeEach
        void refuseTheWrite() {
            doThrow(new PayloadStoreUnavailableException("the file-service insert wrote no row"))
                    .when(payloadFileStore).store(any(), any(), any());
        }

        @Test
        void a_payload_that_could_not_be_stored_should_fail_the_batch_under_one_bounded_reason() {
            final BatchOutcome outcome = request();

            verify(store).markFailed(BATCH_ID, BatchFailureReason.PAYLOAD_STORE_UNAVAILABLE, null,
                    null);
            softly.assertThat(outcome)
                    .as("one way this can go wrong from the batch's point of view - the document "
                            + "has nothing to be rendered from - and the run is told so rather "
                            + "than left to read the row back")
                    .isEqualTo(new BatchOutcome(BATCH_ID, BatchStatus.FAILED,
                            BatchFailureReason.PAYLOAD_STORE_UNAVAILABLE));
        }

        @Test
        void nothing_should_be_asked_of_the_renderer_when_there_is_no_payload_to_render() {
            request();

            verifyNoInteractions(renderer);
            verify(store, never()).markRequested(any(), any());
        }

        @Test
        void a_batch_that_ended_failed_should_be_counted_under_that_outcome() {
            request();

            softly.assertThat(batches(BatchStatus.FAILED))
                    .as("the rows go back to the next run, so nothing else in this flow will ever "
                            + "mention this batch again; the counter is the whole of what a "
                            + "dashboard sees")
                    .isEqualTo(1);
        }
    }

    /**
     * systemdocgenerator accepted the request, which says a render was asked for and nothing else.
     */
    @Nested
    @DisplayName("a render request the contract's 202 accepted")
    class WhenTheRenderIsAccepted {

        @Test
        void an_accepted_render_should_leave_the_batch_generating() {
            final BatchOutcome outcome = request();

            softly.assertThat(outcome)
                    .as("GENERATING is honest about what has happened: a render was asked for, and "
                            + "the document arrives later on the public-event topic")
                    .isEqualTo(new BatchOutcome(BATCH_ID, BatchStatus.GENERATING, null));
        }

        @Test
        void an_accepted_render_should_be_written_down_with_the_payload_it_asked_about() {
            request();

            verify(store).markRequested(eq(BATCH_ID), any());
        }

        @Test
        void the_render_request_should_be_the_five_fields_the_contract_names() {
            request();

            softly.assertThat(renderRequest())
                    .as("the batch id travels as sourceCorrelationId, which is the only thing that "
                            + "correlates an outcome event back to rows, and originatingSource is "
                            + "this service's own name, which is what keeps progression's "
                            + "still-deployed listener out of this service's documents")
                    .isEqualTo(new RenderRequest(mintedId(), BATCH_ID, TEMPLATE, FORMAT,
                            ORIGINATING_SOURCE));
        }

        @Test
        void a_nightly_render_should_be_asked_for_as_the_configured_system_identity() {
            request();

            final ArgumentCaptor<CallerIdentity> caller =
                    ArgumentCaptor.forClass(CallerIdentity.class);
            verify(renderer).requestRender(any(), caller.capture());

            softly.assertThat(caller.getValue())
                    .as("a scheduled run is nobody's request: no message named a user, so the call "
                            + "is made under the configured identity rather than under whichever "
                            + "user happened to share the last hearing of the day")
                    .isEqualTo(CallerIdentity.SYSTEM);
        }

        @Test
        void an_accepted_request_should_be_counted_under_the_status_the_contract_admits() {
            request();

            softly.assertThat(requests(ACCEPTED))
                    .as("the request counter's one dimension is what systemdocgenerator answered, "
                            + "so a night of 202s and a night of refusals are two series rather "
                            + "than one number")
                    .isEqualTo(1);
        }

        @Test
        void a_batch_that_only_reached_generating_should_not_be_counted_as_a_terminal_outcome() {
            request();

            softly.assertThat(registry.find(GenerationMetrics.BATCHES).counter())
                    .as("the batches counter is the terminal-outcome series; counting a batch on "
                            + "the way past would make every accepted request look like a finished "
                            + "one and hide the batches that never came back")
                    .isNull();
        }
    }

    /**
     * The renderer answered, and its answer was not the contract's 202.
     */
    @Nested
    @DisplayName("a render request systemdocgenerator refused")
    class WhenTheRenderIsRefused {

        @BeforeEach
        void refuseTheRequest() {
            doThrow(refusal()).when(renderer).requestRender(any(), any());
        }

        @Test
        void a_refusal_should_fail_the_batch_under_the_reason_the_renderer_classified_it_as() {
            final BatchOutcome outcome = request();

            verify(store).markFailed(BATCH_ID, BatchFailureReason.RENDER_REQUEST_REJECTED, null,
                    null);
            softly.assertThat(outcome)
                    .as("a 2xx that is not 202 means something other than the command endpoint "
                            + "answered, which is a different investigation from a refusal and is "
                            + "never treated as a render that will happen")
                    .isEqualTo(new BatchOutcome(BATCH_ID, BatchStatus.FAILED,
                            BatchFailureReason.RENDER_REQUEST_REJECTED));
        }

        @Test
        void a_refusal_should_not_be_asked_again() {
            request();

            verify(renderer, times(1)).requestRender(any(), any());
            softly.assertThat(pause.waits)
                    .as("the service branches on the classification and never on the type: "
                            + "NON_TRANSIENT means another attempt at the same request cannot "
                            + "answer differently, and waiting to prove it costs the run's budget")
                    .isEmpty();
        }

        @Test
        void a_refused_batch_should_never_be_marked_requested() {
            request();

            verify(store, never()).markRequested(any(), any());
        }

        @Test
        void a_batch_that_ended_failed_should_be_counted_under_that_outcome() {
            request();

            softly.assertThat(batches(BatchStatus.FAILED))
                    .as("terminal, and counted where the four failure reasons are read together")
                    .isEqualTo(1);
        }
    }

    /**
     * The request did not reach a verdict, and another attempt inside the run's budget might.
     */
    @Nested
    @DisplayName("a render request worth asking again")
    class WhenTheRequestIsWorthAnotherAttempt {

        @Test
        void a_transient_failure_should_be_asked_again_inside_the_run_deadline() {
            doThrow(transientFailure()).doNothing().when(renderer).requestRender(any(), any());

            final BatchOutcome outcome = request();

            verify(renderer, times(2)).requestRender(any(), any());
            softly.assertThat(outcome)
                    .as("a connect failure and a 503 are not a refusal, and a batch failed on the "
                            + "first of them is a night's register lost to a restart somewhere else")
                    .isEqualTo(new BatchOutcome(BATCH_ID, BatchStatus.GENERATING, null));
            softly.assertThat(pause.waits)
                    .as("the wait is the shared policy's, so this service cannot hold a different "
                            + "opinion about a back-off than the two clients 001 built (C3)")
                    .containsExactly(INITIAL_BACKOFF);
        }

        @Test
        void the_attempts_should_run_out_under_the_shared_policys_budget() {
            doThrow(transientFailure()).when(renderer).requestRender(any(), any());

            final BatchOutcome outcome = request();

            verify(renderer, times(MAX_ATTEMPTS)).requestRender(any(), any());
            verify(store).markFailed(BATCH_ID, BatchFailureReason.RENDER_REQUEST_FAILED, null, null);
            softly.assertThat(outcome)
                    .as("the request could not be delivered, which is its own reason: the rows go "
                            + "back to the next run and nothing downstream believes a register is "
                            + "coming")
                    .isEqualTo(new BatchOutcome(BATCH_ID, BatchStatus.FAILED,
                            BatchFailureReason.RENDER_REQUEST_FAILED));
            softly.assertThat(pause.waits)
                    .as("doubling per attempt, bounded by max-backoff, and a wait between each "
                            + "pair of attempts rather than after the last one")
                    .containsExactly(INITIAL_BACKOFF, INITIAL_BACKOFF.multipliedBy(2));
        }

        @Test
        void no_further_attempt_should_be_started_once_the_run_deadline_would_not_hold_one() {
            doThrow(transientFailure()).when(renderer).requestRender(any(), any());
            final Deadline nearlyGone = Deadline.startingAt(NOW, Duration.ofMillis(150));

            final BatchOutcome outcome = request(service(), batch(), nearlyGone);

            verify(renderer, times(1)).requestRender(any(), any());
            verify(store).markFailed(BATCH_ID, BatchFailureReason.RENDER_REQUEST_FAILED, null, null);
            softly.assertThat(outcome)
                    .as("the deadline bounds requesting, and it bounds it against what an attempt "
                            + "costs at worst rather than against the instant it starts: a run "
                            + "still asking at midnight is a run that collides with the next one")
                    .isEqualTo(new BatchOutcome(BATCH_ID, BatchStatus.FAILED,
                            BatchFailureReason.RENDER_REQUEST_FAILED));
        }

        @Test
        void a_batch_whose_render_was_never_accepted_should_never_be_marked_requested() {
            doThrow(transientFailure()).when(renderer).requestRender(any(), any());

            request();

            verify(store, never()).markRequested(any(), any());
        }
    }

    /**
     * Defect fix P5, which is about what a run leaves behind rather than about what it does.
     *
     * <p>progression's {@code CourtRegisterHandler.processRequests} catches the stream exception,
     * writes one error line and continues; the batch it was building leaves no state on any row and
     * no count anywhere, so nothing downstream can tell a batch that failed from a batch that was
     * never there. The isolation was right and is kept. The silence is what is removed.
     */
    @Nested
    @DisplayName("a batch that could not be assembled into a payload (P5)")
    class WhenTheBatchCannotBeAssembled {

        @BeforeEach
        void refuseToMapThePayload() {
            when(payloadMapper.mapPayload(any()))
                    .thenThrow(new IllegalStateException("the batch could not be mapped"));
        }

        @Test
        void assembly_failure_fails_the_batch_and_the_run_continues() {
            when(store.batched(OTHER_BATCH_ID)).thenReturn(List.of(record("Ada Khan")));
            final RegisterGenerationService service = service();

            final BatchOutcome failed = request(service, batch(), farDeadline());

            verify(store).markFailed(BATCH_ID, BatchFailureReason.ASSEMBLY_FAILED, null, null);
            softly.assertThat(failed)
                    .as("a batch that cannot be turned into a payload is recorded FAILED under a "
                            + "bounded reason, which is the half progression never wrote down")
                    .isEqualTo(new BatchOutcome(BATCH_ID, BatchStatus.FAILED,
                            BatchFailureReason.ASSEMBLY_FAILED));
            softly.assertThat(batches(BatchStatus.FAILED))
                    .as("and counted, which is the other half: a failure nothing counts is a "
                            + "failure nobody is paged about")
                    .isEqualTo(1);

            // doReturn, not when(...).thenReturn: the mapper is currently stubbed to throw, and
            // when() would evaluate that stubbing here - in the arrangement of the second batch,
            // where the throw belongs to the first.
            doReturn(payload).when(payloadMapper).mapPayload(any());
            final BatchOutcome next = request(service, batch(OTHER_BATCH_ID), farDeadline());

            softly.assertThat(next)
                    .as("per-batch isolation is the legacy's useful half and is kept: one bad "
                            + "batch must not cost the estate a night's registers, so the verdict "
                            + "is returned to the run rather than thrown at it")
                    .isEqualTo(new BatchOutcome(OTHER_BATCH_ID, BatchStatus.GENERATING, null));
        }

        @Test
        void a_batch_that_could_not_be_assembled_should_never_reach_the_file_service() {
            request();

            verifyNoInteractions(payloadFileStore, renderer);
            verify(store, never()).markRequested(any(), any());
        }
    }

    /**
     * What a run is allowed to write down about a register every defendant on which is a child.
     *
     * <p>A "never", and deliberately the last word of the suite. The service holds whole register
     * documents in memory for the length of a batch, so the interpolation that would leak one is
     * always one field reference away; every value it puts in a line is the batch identity or a
     * bounded code, and an implementer who wants to log a defendant, a file's contents or another
     * system's words has to widen that deliberately (constitution Principle VII).
     */
    @Nested
    @DisplayName("what the run writes down")
    class WhatItWritesDown {

        @Test
        void nothing_at_info_or_above_should_carry_the_register_being_rendered() {
            when(store.batched(BATCH_ID))
                    .thenReturn(List.of(record(PersonalDataMarkers.CHILD_NAME)));

            try (CapturedLog log = CapturedLog.capturing(RegisterGenerationService.class)) {
                request();
                doThrow(refusal()).when(renderer).requestRender(any(), any());
                request(service(), batch(), farDeadline());

                softly.assertThat(atOrAbove(log, Level.INFO))
                        .as("the batch is a register about children; the run's own lines carry the "
                                + "batch identity and bounded codes, and a defendant's name is "
                                + "never one of them")
                        .noneMatch(line -> line.contains(PersonalDataMarkers.CHILD_NAME));
            }
        }
    }

    /**
     * Records what the run would have waited and moves the clock by it, so the suite proves the
     * policy and the budget without living either.
     */
    private final class RecordingPause implements RetryPause {

        private final List<Duration> waits = new ArrayList<>();

        @Override
        public void pause(final Duration duration) {
            waits.add(duration);
            clock.advance(duration);
        }
    }
}
