package uk.gov.hmcts.cp.courtregister.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import uk.gov.hmcts.cp.courtregister.config.GenerationMetrics;
import uk.gov.hmcts.cp.courtregister.domain.BatchFailureReason;
import uk.gov.hmcts.cp.courtregister.domain.BatchStatus;
import uk.gov.hmcts.cp.courtregister.domain.CompletedBy;
import uk.gov.hmcts.cp.courtregister.domain.RegisterBatch;
import uk.gov.hmcts.cp.courtregister.persistence.RegisterBatchRepository;

/**
 * What a rendering outcome does to a batch, whoever learned it.
 *
 * <p>The sink is the join between the two things that can learn an outcome and the one thing that
 * writes it down, so this suite is about the join and nothing else: which batch the outcome is
 * applied to, which mark it becomes, who is credited with learning it, and what happens to the two
 * arrivals that are not news - an outcome for a batch this service never recorded, and the same
 * outcome twice.
 *
 * <p><strong>Defect fix P2 is pinned here.</strong> progression's
 * {@code SystemDocGeneratorEventProcessor} branches on PRISON_COURT_REGISTER and NOWS and has no
 * court-register branch at all, so a {@code generation-failed} for a court register is logged and
 * discarded and its rows stay where they were - a render that failed is indistinguishable from one
 * nobody answered about. {@code generation_failed_event_fails_the_batch_with_reason} is the row's
 * pinning test: the batch is FAILED under the bounded reason GENERATION_FAILED, carrying
 * systemdocgenerator's own words into {@code sdg_reason} for support.
 *
 * <p><strong>Defect fix P3 is reused here rather than re-pinned.</strong> Whether a mark actually
 * touches this batch's rows and no others is a question about SQL, and it is answered by
 * {@code RegisterStoreIT.generation_flips_only_the_batchs_own_rows}. What is answered here is the
 * half that lives above the store: the sink applies an outcome to the batch identity the outcome
 * named, and never to the key that batch happens to share with another day's. Two batches for one
 * court centre on two register days are put in front of it precisely so that a sink that reached
 * for the court centre would be seen doing it.
 *
 * <p><strong>Both mechanisms, one code path.</strong> {@link CompletedBy} is a parameter rather than
 * something the sink infers from its caller, so every mark is driven under both values: the store
 * writes {@code completed_by} in the same statement that moves the batch - a compare-and-set leaves
 * no second moment to write it in - and a mechanism the sink substituted for the one that actually
 * learned the outcome would make the {@code reconciled} reading a description of the code rather
 * than of the night.
 *
 * <p><strong>The correlation is the batch's identity, and the payload has to agree with it.</strong>
 * {@code sourceCorrelationId} is what the render request carried and it is the only identifier this
 * service ever asks a batch by; {@code payloadFileServiceId} is the cross-check and never a second
 * way in. So an outcome naming a correlation this store has no batch for is counted and ignored
 * whatever payload it names - reaching for the payload instead would let an event that has lost its
 * correlation complete a batch it was never about - and an outcome whose correlation and payload
 * name different batches is counted and ignored too, because an event that contradicts itself is
 * the one shape that could complete the wrong night's registers. A redelivery - which a durable
 * subscription guarantees and a reconciler racing an in-flight event produces - carries both
 * identifiers of one batch, moves it once and is counted nowhere: it is attributable, and it is
 * already applied.
 *
 * <p>Nothing here reaches a defendant, a recipient or a register. The one piece of free text in the
 * suite is systemdocgenerator's own message about a document, which is carried into
 * {@code sdg_reason} and asserted as an argument rather than as a log line; where it may and may not
 * appear in the log is {@code TelemetryPrivacyTest}'s question about the service as a whole rather
 * than this suite's about one class.
 */
@ExtendWith(SoftAssertionsExtension.class)
@DisplayName("the document outcome sink")
class DocumentOutcomeSinkTest {

    /** Quoted by every case, so the red run names the seam it is waiting on. */
    private static final String PENDING = "T047 implements the sink; this is its red run";

    /** Returned when a meter is absent, so a missing count fails as an assertion. */
    private static final double ABSENT = -1;

    private static final UUID COURT_CENTRE =
            UUID.fromString("0f3f4a52-4a3f-4a1b-9c4e-6c2f1e7a5b31");
    private static final String OU_CODE = "B01LY00";
    private static final String COURT_HOUSE = "Lavender Hill Youth Court";

    /** Two register days at one court centre - the shape defect P3 is about. */
    private static final LocalDate MONDAY = LocalDate.of(2026, 3, 2);
    private static final LocalDate TUESDAY = LocalDate.of(2026, 3, 3);

    private static final Instant ASSEMBLED_AT = Instant.parse("2026-03-02T18:00:00Z");
    private static final Instant REQUESTED_AT = Instant.parse("2026-03-02T18:00:12Z");
    private static final Instant GENERATED_AT = Instant.parse("2026-03-02T18:01:40Z");
    private static final Instant FAILED_AT = Instant.parse("2026-03-02T18:01:45Z");

    private static final UUID DOCUMENT_FILE_ID =
            UUID.fromString("2b8a9d10-77c5-4a6d-8f3e-0d51c9a2e4b7");

    /** systemdocgenerator's own words, kept for support and never logged at INFO. */
    private static final String SDG_REASON = "template OEE_Layout5 rendered no pages";

    private final RegisterStore store = mock(RegisterStore.class);
    private final RegisterBatchRepository batches = mock(RegisterBatchRepository.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final GenerationMetrics metrics = new GenerationMetrics(registry);

    /**
     * The leg the sink hands a generated batch on to, doubled because it is not this suite's
     * subject: what the recipients of a batch are told is {@code RegisterNotifierServiceTest}'s
     * question, and the join asserted here is which batch an outcome is applied to.
     */
    private final RegisterNotifierService notifier = mock(RegisterNotifierService.class);

    private final DocumentOutcomeSinkImpl sink =
            new DocumentOutcomeSinkImpl(store, batches, metrics, notifier);

    @InjectSoftAssertions
    private SoftAssertions softly;

    /**
     * How many outcomes were ignored for one bounded reason.
     *
     * <p>The documented instrument rather than a tally of the sink's own, because the number is
     * read beside the subscription's own health by whoever is asking why a night's outcomes went
     * nowhere, and a field on one bean is not something a dashboard can ask.
     *
     * @param reason the {@code reason} label
     * @return the count, or {@link #ABSENT} where the series does not exist
     */
    private double ignored(final String reason) {
        final Counter counter = registry.find(GenerationMetrics.PUBLIC_EVENTS_IGNORED)
                .tag(GenerationMetrics.REASON_TAG, reason)
                .counter();
        return counter == null ? ABSENT : counter.count();
    }

    /**
     * Hands the sink a document and records the seam's refusal rather than ending the case.
     *
     * <p>The call is made inside {@code assertThatCode(...).doesNotThrowAnyException()} for the same
     * reason {@code FeatureFlagGateTest} makes its own that way: the red run is then the property
     * under test rather than the stub, and the green run is these same assertions unchanged.
     *
     * @param correlationId the batch identity the event named, which the contract allows to be
     *                      absent
     * @param payloadFileId the payload the document was rendered from, which the contract requires
     * @param learnedBy     the mechanism naming itself
     */
    private void documentAvailable(final UUID correlationId, final UUID payloadFileId,
            final CompletedBy learnedBy) {
        softly.assertThatCode(() -> sink.documentAvailable(
                        correlationId, payloadFileId, DOCUMENT_FILE_ID, GENERATED_AT, learnedBy))
                .as(PENDING)
                .doesNotThrowAnyException();
    }

    /**
     * Hands the sink a failed generation, under the same refusal-recording rule.
     *
     * @param correlationId the batch identity the event named, which the contract allows to be
     *                      absent
     * @param payloadFileId the payload the render was requested for, which the contract requires
     * @param learnedBy     the mechanism naming itself
     */
    private void generationFailed(final UUID correlationId, final UUID payloadFileId,
            final CompletedBy learnedBy) {
        softly.assertThatCode(() -> sink.generationFailed(
                        correlationId, payloadFileId, SDG_REASON, FAILED_AT, learnedBy))
                .as(PENDING)
                .doesNotThrowAnyException();
    }

    /**
     * Asserts one thing the store was or was not told, softly.
     *
     * <p>A Mockito verification is an assertion that throws, and a case that ended at the first one
     * would hide the rest of what the sink did or failed to do. Wrapping each verification keeps
     * every case reporting its whole story in one run.
     *
     * @param as           what the verification is claiming, quoted into the failure
     * @param verification the verification to make
     */
    private void told(final String as, final ThrowingCallable verification) {
        softly.assertThatCode(verification).as(as).doesNotThrowAnyException();
    }

    /**
     * A batch the renderer has been asked about and has not answered for.
     *
     * <p>One lookup is stubbed because there is one lookup: a batch is found by the identity the
     * render request carried, and the payload the event names is a cross-check on that answer
     * rather than a second way to reach a batch.
     *
     * @param registerDate the register day this batch groups
     * @return the batch, GENERATING
     */
    private RegisterBatch inFlight(final LocalDate registerDate) {
        final RegisterBatch batch = generating(registerDate);
        when(batches.findById(batch.batchId())).thenReturn(Optional.of(batch));
        return batch;
    }

    /** A batch in the state a render request leaves it in: a payload, a stamp, and no answer. */
    private static RegisterBatch generating(final LocalDate registerDate) {
        return new RegisterBatch(UUID.randomUUID(), COURT_CENTRE, OU_CODE, COURT_HOUSE,
                registerDate, fileName(registerDate), UUID.randomUUID(), null,
                BatchStatus.GENERATING, null, null, true, null, ASSEMBLED_AT, REQUESTED_AT, null,
                null, null, 1, null, 0);
    }

    /** The same batch once the document exists, which is where a redelivery finds it. */
    private static RegisterBatch generated(final RegisterBatch batch, final CompletedBy learnedBy) {
        return new RegisterBatch(batch.batchId(), COURT_CENTRE, OU_CODE, COURT_HOUSE,
                batch.registerDate(), batch.fileName(), batch.payloadFileId(), DOCUMENT_FILE_ID,
                BatchStatus.GENERATED, null, null, true, learnedBy, ASSEMBLED_AT, REQUESTED_AT,
                GENERATED_AT, null, null, 1, null, 0);
    }

    /** The same batch once the renderer has refused it, which is where a redelivery finds it. */
    private static RegisterBatch failed(final RegisterBatch batch, final CompletedBy learnedBy) {
        return new RegisterBatch(batch.batchId(), COURT_CENTRE, OU_CODE, COURT_HOUSE,
                batch.registerDate(), batch.fileName(), batch.payloadFileId(), null,
                BatchStatus.FAILED, BatchFailureReason.GENERATION_FAILED, SDG_REASON, true,
                learnedBy, ASSEMBLED_AT, REQUESTED_AT, null, null, FAILED_AT, 1, null, 0);
    }

    private static String fileName(final LocalDate registerDate) {
        return "court-register_" + registerDate + '_' + OU_CODE + ".pdf";
    }

    /**
     * systemdocgenerator rendered the document, and said so.
     */
    @Nested
    @DisplayName("a document that was generated")
    class ADocumentThatWasGenerated {

        @Test
        void a_document_available_should_mark_its_own_batch_generated() {
            final RegisterBatch batch = inFlight(MONDAY);

            documentAvailable(batch.batchId(), batch.payloadFileId(), CompletedBy.EVENT);

            told("the document's file-service id and the instant it was generated are what the "
                            + "batch had no way of knowing until now, and the mechanism that "
                            + "learned them travels with the same mark because the store writes "
                            + "completed_by in the statement that moves the batch",
                    () -> verify(store).markGenerated(
                            batch.batchId(), DOCUMENT_FILE_ID, GENERATED_AT, CompletedBy.EVENT));
            told("a document that exists is not a failure of any kind, and a batch that was both "
                            + "would be a register nobody could say the state of",
                    () -> verify(store, never()).markFailed(any(), any(), any(), any()));
        }

        @Test
        void generation_should_be_scoped_to_the_batch_the_outcome_named() {
            final RegisterBatch monday = inFlight(MONDAY);
            final RegisterBatch tuesday = inFlight(TUESDAY);

            documentAvailable(monday.batchId(), monday.payloadFileId(), CompletedBy.EVENT);

            told("the outcome names a batch, and the batch is what it is applied to",
                    () -> verify(store).markGenerated(
                            eq(monday.batchId()), any(), any(), any()));
            told("defect P3 is progression flipping every register for the court centre the first "
                            + "request named; Tuesday's batch shares that court centre and is not "
                            + "this document's",
                    () -> verify(store, never()).markGenerated(
                            eq(tuesday.batchId()), any(), any(), any()));
            told("and exactly one batch is marked, so a sink that widened to the key would be "
                            + "seen doing it rather than inferred not to have",
                    () -> verify(store, times(1)).markGenerated(any(), any(), any(), any()));
        }

        @ParameterizedTest
        @EnumSource(CompletedBy.class)
        void each_mechanism_should_be_named_on_the_document_it_learned_about(
                final CompletedBy learnedBy) {

            final RegisterBatch batch = inFlight(MONDAY);

            documentAvailable(batch.batchId(), batch.payloadFileId(), learnedBy);

            told("one code path for the listener and the reconciler, and the caller's own name "
                            + "carried through it verbatim: a run whose outcomes all arrive by "
                            + "RECONCILER is a subscription to investigate, and nothing else in "
                            + "the flow would say so",
                    () -> verify(store).markGenerated(
                            batch.batchId(), DOCUMENT_FILE_ID, GENERATED_AT, learnedBy));
        }
    }

    /**
     * systemdocgenerator refused the render, which progression records nowhere (defect P2).
     */
    @Nested
    @DisplayName("a generation that failed")
    class AGenerationThatFailed {

        @Test
        void generation_failed_event_fails_the_batch_with_reason() {
            final RegisterBatch batch = inFlight(MONDAY);

            generationFailed(batch.batchId(), batch.payloadFileId(), CompletedBy.EVENT);

            told("P2: progression has no court-register branch for generation-failed, so the "
                            + "refusal is logged and discarded and the batch is indistinguishable "
                            + "from one nobody answered about; here it is FAILED under the bounded "
                            + "reason, carrying the renderer's own words into sdg_reason for "
                            + "support, and credited to the mechanism that learned it",
                    () -> verify(store).markFailed(batch.batchId(),
                            BatchFailureReason.GENERATION_FAILED, SDG_REASON, CompletedBy.EVENT));
            told("and no document is recorded for a render that produced none",
                    () -> verify(store, never()).markGenerated(any(), any(), any(), any()));
        }

        @ParameterizedTest
        @EnumSource(CompletedBy.class)
        void each_mechanism_should_be_named_on_the_failure_it_learned_about(
                final CompletedBy learnedBy) {

            final RegisterBatch batch = inFlight(MONDAY);

            generationFailed(batch.batchId(), batch.payloadFileId(), learnedBy);

            told("GENERATION_FAILED is one of the two endings somebody outside this service "
                            + "reported, so it always names a mechanism - and it names the one "
                            + "that reported it rather than the one the sink was written for",
                    () -> verify(store).markFailed(batch.batchId(),
                            BatchFailureReason.GENERATION_FAILED, SDG_REASON, learnedBy));
        }
    }

    /**
     * The outcome arrived; the batch it is about did not.
     */
    @Nested
    @DisplayName("an outcome no batch answers to")
    class AnOutcomeNoBatchAnswersTo {

        @Test
        void an_unknown_correlation_should_be_counted_and_ignored() {
            documentAvailable(UUID.randomUUID(), UUID.randomUUID(), CompletedBy.EVENT);

            softly.assertThat(ignored(GenerationMetrics.UNKNOWN_CORRELATION))
                    .as("another consumer's document, or one from a batch that predates this "
                            + "store; zero is the expected reading and anything else is a "
                            + "correlation lost between the render request and the event")
                    .isEqualTo(1);
            told("and nothing is written: a row invented for an outcome this service cannot "
                            + "attribute is a register nobody asked for",
                    () -> verifyNoInteractions(store));
        }

        @Test
        void an_unattributable_failure_should_be_counted_and_ignored() {
            generationFailed(UUID.randomUUID(), UUID.randomUUID(), CompletedBy.EVENT);

            softly.assertThat(ignored(GenerationMetrics.UNKNOWN_CORRELATION))
                    .as("the failure half of the same reading, because a failure this service "
                            + "cannot attribute is exactly as unattributable as a document")
                    .isEqualTo(1);
            told("and no batch is failed on the strength of somebody else's refusal",
                    () -> verifyNoInteractions(store));
        }

        /**
         * The payload is a cross-check and never a way in.
         *
         * <p>The store this batch is held in knows the payload the outcome names, so a sink that
         * went looking for a batch by it would complete this one on an event whose own account of
         * which batch it is about names nothing this store holds. That is not a batch identified by
         * a second means: it is an event whose correlation was lost or rewritten somewhere between
         * the render request and the topic, and completing a night's registers on it is exactly the
         * guess the correlation exists to make unnecessary. The repository offers no such read, and
         * this is the case that says the sink does not want one.
         */
        @Test
        void an_unknown_correlation_should_not_be_rescued_by_a_payload_that_matches() {
            final RegisterBatch batch = inFlight(MONDAY);

            documentAvailable(UUID.randomUUID(), batch.payloadFileId(), CompletedBy.EVENT);

            told("the correlation names no batch this service holds, so nothing is marked - the "
                            + "payload is the cross-check on a correlation, not a second way to a "
                            + "batch",
                    () -> verifyNoInteractions(store));
            softly.assertThat(ignored(GenerationMetrics.UNKNOWN_CORRELATION))
                    .as("and it is counted under the reason that is true of it: this service has "
                            + "no batch by that identity, whatever payload the event names")
                    .isEqualTo(1);
        }

        /**
         * {@code sourceCorrelationId} is optional on both vendored schemas, and this service always
         * sends one, so an outcome carrying none answers a request that was not ours. The listener
         * already drops it before the sink is reached; the sink says the same thing on its own
         * account, because the reconciler is the other caller and a port that behaved differently
         * for its two drivers would have two answers to one question.
         */
        @Test
        void an_outcome_without_a_correlation_should_be_counted_and_ignored() {
            final RegisterBatch batch = inFlight(MONDAY);

            documentAvailable(null, batch.payloadFileId(), CompletedBy.EVENT);

            told("an outcome that names no batch is applied to none",
                    () -> verifyNoInteractions(store));
            softly.assertThat(ignored(GenerationMetrics.UNKNOWN_CORRELATION))
                    .as("counted, so a subscription whose events have stopped carrying the "
                            + "correlation is legible rather than silently idle")
                    .isEqualTo(1);
        }
    }

    /**
     * The outcome named a batch this service holds, and a payload that batch was never rendered
     * from.
     *
     * <p>The two identifiers are one fact told twice, and an event where they disagree is an event
     * this service cannot believe either half of. Applying the correlation's half anyway would
     * complete a batch on somebody else's document; applying the payload's half would complete a
     * different batch than the event claims to be about. Neither is a guess worth making about a
     * night's registers, so the event moves nothing and is counted.
     */
    @Nested
    @DisplayName("an outcome whose two identifiers disagree")
    class AnOutcomeThatContradictsItself {

        @Test
        void a_document_naming_the_wrong_payload_should_complete_nothing() {
            final RegisterBatch batch = inFlight(MONDAY);

            documentAvailable(batch.batchId(), UUID.randomUUID(), CompletedBy.EVENT);

            told("the correlation names this batch and the payload is not the one it was "
                            + "requested for, so the event is not about it and nothing is marked",
                    () -> verifyNoInteractions(store));
            softly.assertThat(ignored(GenerationMetrics.PAYLOAD_MISMATCH))
                    .as("counted under its own reason, because an event that contradicts itself "
                            + "is a renderer or a broker to investigate rather than a correlation "
                            + "this service never had")
                    .isEqualTo(1);
        }

        @Test
        void a_refusal_naming_the_wrong_payload_should_fail_nothing() {
            final RegisterBatch batch = inFlight(MONDAY);

            generationFailed(batch.batchId(), UUID.randomUUID(), CompletedBy.EVENT);

            told("the failure half of the same rule: a batch is not failed on a refusal about "
                            + "some other payload",
                    () -> verifyNoInteractions(store));
            softly.assertThat(ignored(GenerationMetrics.PAYLOAD_MISMATCH))
                    .as("and the refusal is counted rather than dropped in silence")
                    .isEqualTo(1);
        }

        /**
         * The reconciler asks systemdocgenerator about a payload it read off the batch row, so its
         * two identifiers always agree; the listener's come off the wire and are the ones that can
         * disagree. The rule is the sink's rather than the listener's because there is one code
         * path for what an outcome means, and a check that lived in one driver would be a check the
         * other did not make.
         */
        @Test
        void a_batch_whose_payload_is_not_yet_known_should_take_no_outcome_at_all() {
            final RegisterBatch pending = new RegisterBatch(UUID.randomUUID(), COURT_CENTRE,
                    OU_CODE, COURT_HOUSE, MONDAY, fileName(MONDAY), null, null,
                    BatchStatus.PENDING, null, null, true, null, ASSEMBLED_AT, null, null, null,
                    null, 1, null, 0);
            when(batches.findById(pending.batchId())).thenReturn(Optional.of(pending));

            documentAvailable(pending.batchId(), UUID.randomUUID(), CompletedBy.EVENT);

            told("a batch that never reached the renderer has no payload for an outcome to agree "
                            + "with, so an outcome claiming to be about one is not about this batch",
                    () -> verifyNoInteractions(store));
            softly.assertThat(ignored(GenerationMetrics.PAYLOAD_MISMATCH))
                    .as("and it is the same disagreement, counted the same way")
                    .isEqualTo(1);
        }
    }

    /**
     * The same outcome twice, which a durable subscription guarantees will happen.
     */
    @Nested
    @DisplayName("an outcome that has already been applied")
    class AnOutcomeThatHasAlreadyBeenApplied {

        @Test
        void a_redelivered_document_available_should_move_the_batch_once() {
            final RegisterBatch batch = generating(MONDAY);
            final RegisterBatch afterwards = generated(batch, CompletedBy.EVENT);
            when(batches.findById(batch.batchId()))
                    .thenReturn(Optional.of(batch)).thenReturn(Optional.of(afterwards));

            documentAvailable(batch.batchId(), batch.payloadFileId(), CompletedBy.EVENT);
            documentAvailable(batch.batchId(), batch.payloadFileId(), CompletedBy.EVENT);

            told("a batch already where the event would put it is recognised rather than "
                            + "re-stamped, which is the reading BatchStatus was narrowed to force: "
                            + "GENERATED to GENERATED is a move the machine does not draw",
                    () -> verify(store, times(1)).markGenerated(any(), any(), any(), any()));
            softly.assertThat(ignored(GenerationMetrics.UNKNOWN_CORRELATION))
                    .as("a redelivery is attributable and already applied; counting it as "
                            + "unattributed would report a lost correlation every time the broker "
                            + "did what a durable subscription is for")
                    .isEqualTo(ABSENT);
        }

        @Test
        void a_redelivered_generation_failed_should_fail_the_batch_once() {
            final RegisterBatch batch = generating(MONDAY);
            final RegisterBatch afterwards = failed(batch, CompletedBy.EVENT);
            when(batches.findById(batch.batchId()))
                    .thenReturn(Optional.of(batch)).thenReturn(Optional.of(afterwards));

            generationFailed(batch.batchId(), batch.payloadFileId(), CompletedBy.EVENT);
            generationFailed(batch.batchId(), batch.payloadFileId(), CompletedBy.EVENT);

            told("FAILED is terminal, so a second refusal for the same batch has nothing left to "
                            + "record; re-failing it would restate a verdict and move failed_at "
                            + "away from the moment the render was actually refused",
                    () -> verify(store, times(1)).markFailed(any(), any(), any(), any()));
        }
    }
}
