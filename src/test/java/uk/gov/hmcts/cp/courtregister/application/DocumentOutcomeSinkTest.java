package uk.gov.hmcts.cp.courtregister.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
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
import org.mockito.InOrder;
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
 * <p><strong>And the leg a generated batch is handed on to, which is part of the join.</strong> The
 * GENERATED mark and {@link RegisterNotifierService#notify} are one step of one code path, so the
 * order between them and the arrivals that never reach the second of them are this suite's question
 * too: what the recipients are told is {@code RegisterNotifierServiceTest}'s. Those cases are
 * characterisations of the wiring T059 landed rather than the red half of a pair, and they are what
 * pins the never-notified-twice claim this javadoc makes above.
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

    /**
     * When the store stamped the failure, which is deliberately not {@link #FAILED_AT}.
     *
     * <p>{@code markFailed} sets {@code failed_at = now()} and ignores the renderer's own account of
     * when it gave up, which the sink's javadoc says in as many words. The two are different
     * instants in this suite so that a reading taken from the argument rather than from the row
     * fails rather than agreeing by coincidence.
     */
    private static final Instant FAILURE_RECORDED_AT = Instant.parse("2026-03-02T18:01:59Z");

    /** A generator whose clock is behind the store's, which is not a round trip (see below). */
    private static final Instant GENERATED_BEFORE_IT_WAS_ASKED_FOR =
            Instant.parse("2026-03-02T17:59:30Z");

    private static final UUID DOCUMENT_FILE_ID =
            UUID.fromString("2b8a9d10-77c5-4a6d-8f3e-0d51c9a2e4b7");

    /** systemdocgenerator's own words, kept for support and never logged at INFO. */
    private static final String SDG_REASON = "template OEE_Layout5 rendered no pages";

    private final RegisterStore store = mock(RegisterStore.class);
    private final RegisterBatchRepository batches = mock(RegisterBatchRepository.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final GenerationMetrics metrics = new GenerationMetrics(registry);

    /**
     * The leg the sink hands a generated batch on to, doubled because what it does is not this
     * suite's subject: what the recipients of a batch are told is
     * {@code RegisterNotifierServiceTest}'s question, and what is asked of the double here is only
     * whether it was reached, with which batch, and after what.
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
     * Asserts one thing a collaborator was or was not told, softly.
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

    /**
     * The same batch once the renderer has refused it, which is where a redelivery finds it.
     *
     * <p>{@code failed_at} is {@link #FAILURE_RECORDED_AT} and not the {@link #FAILED_AT} the sink
     * was handed, because the store stamps its own instant and drops the renderer's.
     */
    private static RegisterBatch failed(final RegisterBatch batch, final CompletedBy learnedBy) {
        return new RegisterBatch(batch.batchId(), COURT_CENTRE, OU_CODE, COURT_HOUSE,
                batch.registerDate(), batch.fileName(), batch.payloadFileId(), null,
                BatchStatus.FAILED, BatchFailureReason.GENERATION_FAILED, SDG_REASON, true,
                learnedBy, ASSEMBLED_AT, REQUESTED_AT, null, null, FAILURE_RECORDED_AT, 1, null, 0);
    }

    /**
     * A batch in flight, and the row the store is left holding once its document is recorded.
     *
     * <p>Two answers from one lookup, because the round trip is a reading of the row rather than of
     * the event: the sink reads the batch to decide what the outcome means, marks it, and reads
     * back what the store ended up holding.
     *
     * @param learnedBy the mechanism that learned the document exists
     * @return the batch as the first read returns it, GENERATING
     */
    private RegisterBatch inFlightUntilGenerated(final CompletedBy learnedBy) {
        final RegisterBatch batch = generating(MONDAY);
        when(batches.findById(batch.batchId()))
                .thenReturn(Optional.of(batch))
                .thenReturn(Optional.of(generated(batch, learnedBy)));
        return batch;
    }

    /**
     * A batch in flight, and the row the store is left holding once its refusal is recorded.
     *
     * @param learnedBy the mechanism that learned the render failed
     * @return the batch as the first read returns it, GENERATING
     */
    private RegisterBatch inFlightUntilFailed(final CompletedBy learnedBy) {
        final RegisterBatch batch = generating(MONDAY);
        when(batches.findById(batch.batchId()))
                .thenReturn(Optional.of(batch))
                .thenReturn(Optional.of(failed(batch, learnedBy)));
        return batch;
    }

    /**
     * How many render round trips have been timed.
     *
     * @return the count, or {@link #ABSENT} where the timer has no series at all
     */
    private double roundTripsTimed() {
        final Timer timer = registry.find(GenerationMetrics.GENERATION_LATENCY).timer();
        return timer == null ? ABSENT : timer.count();
    }

    /**
     * How long the timed round trips came to.
     *
     * @return the total in seconds, or {@link #ABSENT} where the timer has no series at all
     */
    private double roundTripSeconds() {
        final Timer timer = registry.find(GenerationMetrics.GENERATION_LATENCY).timer();
        return timer == null ? ABSENT : timer.totalTime(TimeUnit.SECONDS);
    }

    /**
     * The label keys the timed series carries.
     *
     * @return the keys, or a named absence so that an unrecorded timer fails rather than passes
     */
    private List<String> roundTripLabels() {
        final Timer timer = registry.find(GenerationMetrics.GENERATION_LATENCY).timer();
        return timer == null
                ? List.of("<the timer recorded nothing>")
                : timer.getId().getTags().stream().map(Tag::getKey).toList();
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

    /**
     * The leg a generated batch is handed on to, and everything that never reaches it.
     *
     * <p>A document that exists and has been sent to nobody is the state defect fix P1 is about, so
     * the moment the batch is recorded as having one is the moment its Youth Offending Teams can be
     * told: the GENERATED mark and {@link RegisterNotifierService#notify} are one step of one code
     * path, which is what makes the reconciler's fetched document reach the same e-mails the topic's
     * delivered one does. What is asked here is the order and the absences, not the content - the
     * mark is what decides whether anyone is told at all, because the store's compare-and-set is
     * what refuses the second of two racing mechanisms, so an e-mail sent before that mark, or in
     * place of it, would be a register announced on a move that never took.
     *
     * <p><strong>These cases are characterisations rather than the red half of a pair.</strong> The
     * wiring landed with {@code RegisterNotifierService} (T059) and they were written afterwards, so
     * they pass on introduction and accept the join as it stands. They exist because the claim that
     * a redelivered document-available is recognised and never notified twice - made in this class's
     * javadoc, in {@code DocumentOutcomeSinkImpl}'s and in {@code data-model.md} - was until now
     * pinned by nothing at unit level, and it is the claim the whole idempotency of the notifying
     * leg rests on.
     */
    @Nested
    @DisplayName("the recipients of a batch whose document now exists")
    class TheRecipientsOfABatchWhoseDocumentNowExists {

        @ParameterizedTest
        @EnumSource(CompletedBy.class)
        void a_generated_batch_should_be_marked_before_its_recipients_are_told(
                final CompletedBy learnedBy) {

            final RegisterBatch batch = inFlight(MONDAY);

            documentAvailable(batch.batchId(), batch.payloadFileId(), learnedBy);

            final InOrder order = inOrder(store, notifier);
            told("the mark comes first because it is the decision: it refuses where another "
                            + "mechanism has already moved this batch, and an e-mail sent ahead of "
                            + "it would be a register announced to a team on the strength of a move "
                            + "that never took",
                    () -> order.verify(store).markGenerated(
                            batch.batchId(), DOCUMENT_FILE_ID, GENERATED_AT, learnedBy));
            told("and then the recipients are told, in the same step and on this thread, under "
                            + "both mechanisms - a document the reconciler fetched reaches the same "
                            + "e-mails a delivered event's does, which is the whole reason one code "
                            + "path exists",
                    () -> order.verify(notifier).notify(batch.batchId()));
            told("once, and for the batch that was marked and no other: the notifying leg reads "
                            + "the batch back by this id, so a second hand-on would be a second "
                            + "e-mail to every team the day's register was addressed to",
                    () -> verify(notifier, times(1)).notify(any()));
        }

        @Test
        void a_redelivered_document_available_should_tell_the_recipients_once() {
            final RegisterBatch batch = generating(MONDAY);
            when(batches.findById(batch.batchId()))
                    .thenReturn(Optional.of(batch))
                    .thenReturn(Optional.of(generated(batch, CompletedBy.EVENT)));

            documentAvailable(batch.batchId(), batch.payloadFileId(), CompletedBy.EVENT);
            documentAvailable(batch.batchId(), batch.payloadFileId(), CompletedBy.EVENT);

            told("a durable subscription guarantees the second delivery and a reconciler racing an "
                            + "in-flight event produces it; the batch already stands where the "
                            + "outcome would put it, so the second arrival is recognised above and "
                            + "the teams are e-mailed exactly once",
                    () -> verify(notifier, times(1)).notify(batch.batchId()));
        }

        /**
         * The reconciler arriving after the listener has already finished, which is the shape the
         * grace-period query is bound to produce: the batch it asks systemdocgenerator about was
         * GENERATING when the query selected it and is GENERATED by the time the answer is applied.
         */
        @Test
        void a_document_available_for_a_batch_already_generated_should_tell_nobody() {
            final RegisterBatch batch = generated(generating(MONDAY), CompletedBy.EVENT);
            when(batches.findById(batch.batchId())).thenReturn(Optional.of(batch));

            documentAvailable(batch.batchId(), batch.payloadFileId(), CompletedBy.RECONCILER);

            told("nothing is re-stamped, so there is no mark for a notification to follow",
                    () -> verify(store, never()).markGenerated(any(), any(), any(), any()));
            told("and the teams are not told again: the rows the notifying leg would mint are "
                            + "already there, and a second hand-on is a second e-mail about a "
                            + "register that has already been sent",
                    () -> verifyNoInteractions(notifier));
        }

        @ParameterizedTest
        @EnumSource(CompletedBy.class)
        void a_generation_that_failed_should_tell_nobody(final CompletedBy learnedBy) {
            final RegisterBatch batch = inFlight(MONDAY);

            generationFailed(batch.batchId(), batch.payloadFileId(), learnedBy);

            told("there is no document, so there is nothing to attach and nobody to tell; the "
                            + "notifying leg is reached from the GENERATED mark alone and a refusal "
                            + "under either mechanism ends the batch rather than announcing it",
                    () -> verifyNoInteractions(notifier));
        }

        /**
         * The race the compare-and-set exists to settle, seen from the losing side.
         *
         * <p>{@code JdbcRegisterStore.permitted} refuses a move the batch has already made with an
         * {@link IllegalStateException}, so the mark two mechanisms make about one batch at one
         * moment succeeds for exactly one of them. The refusal is what stops the loser going on to
         * send, and it is not swallowed here either: the sink lets it out to the caller - the
         * listener's own error handling, or the reconciler's - rather than turning a mark that did
         * not take into a run that looks like it worked.
         */
        @Test
        void a_mark_that_did_not_take_should_not_be_followed_by_an_e_mail() {
            final RegisterBatch batch = inFlight(MONDAY);
            final String refusal =
                    "batch " + batch.batchId() + " may not move from GENERATED to GENERATED";
            doThrow(new IllegalStateException(refusal))
                    .when(store).markGenerated(any(), any(), any(), any());

            softly.assertThatThrownBy(() -> sink.documentAvailable(batch.batchId(),
                            batch.payloadFileId(), DOCUMENT_FILE_ID, GENERATED_AT,
                            CompletedBy.RECONCILER))
                    .as("the refusal reaches the caller rather than being absorbed here, because a "
                            + "mark that did not take is not an outcome this service applied")
                    .isInstanceOf(IllegalStateException.class);
            told("and nobody is e-mailed on the strength of it: the mark decides, so the mechanism "
                            + "whose compare-and-set lost the race sends nothing and the one that "
                            + "won sends everything",
                    () -> verifyNoInteractions(notifier));
        }
    }

    /**
     * How long the render took, which is the one reading of this leg nothing was taking.
     *
     * <p>{@code courtregister_generation_latency} is declared as "time from the render request to
     * the batch's outcome", and its argument is documented as "request to outcome, however the
     * outcome arrived" - so it is the render round trip, closed by whichever of the two mechanisms
     * got there first, and not the request-to-e-mail span the notifying leg's own counters are
     * about.
     *
     * <p><strong>Both ends come off the row, and that is the design rather than a convenience.
     * </strong> The pod that asked systemdocgenerator for a render is not always the pod the topic
     * delivers the outcome to, so a duration measured from an instant held in memory since the
     * request would be absent on the pod that hears the answer and available only where one pod
     * happened to do both. {@code requested_at} and the batch's own outcome stamp are columns, so
     * the reading is the same whoever takes it - which is why the sink reads the batch back after
     * the mark rather than timing the event it was handed.
     *
     * <p>The failure case is what proves that: {@link #FAILURE_RECORDED_AT} is the store's own
     * {@code failed_at} and {@link #FAILED_AT} is the renderer's account of when it gave up, which
     * {@code markFailed} drops. A reading taken from the argument would be fourteen seconds short.
     *
     * <p>The series carries no label, which is the surface {@code plan.md}'s metrics table declares
     * and {@code GenerationMetricsTest} pins: a batch id, a court centre id or an address cannot be
     * a label of a meter that has none, and there is nothing else about a render round trip that a
     * bounded enumeration could carry.
     */
    @Nested
    @DisplayName("how long the render took")
    class HowLongTheRenderTook {

        @ParameterizedTest
        @EnumSource(CompletedBy.class)
        void a_recorded_document_should_time_the_round_trip_the_row_ends_up_holding(
                final CompletedBy learnedBy) {

            final RegisterBatch batch = inFlightUntilGenerated(learnedBy);

            documentAvailable(batch.batchId(), batch.payloadFileId(), learnedBy);

            softly.assertThat(roundTripsTimed())
                    .as("one render, one reading, and it is taken under both mechanisms: a series "
                            + "that only moved for a delivered event would read as a renderer "
                            + "getting faster every time the topic broke")
                    .isEqualTo(1);
            softly.assertThat(roundTripSeconds())
                    .as("requested_at to generated_at, both off the row the store ended up "
                            + "holding")
                    .isEqualTo(Duration.between(REQUESTED_AT, GENERATED_AT).toSeconds());
            softly.assertThat(roundTripLabels())
                    .as("no label at all, so no batch id, court centre id or address can be one")
                    .isEmpty();
        }

        @ParameterizedTest
        @EnumSource(CompletedBy.class)
        void a_recorded_refusal_should_time_the_round_trip_too(final CompletedBy learnedBy) {
            final RegisterBatch batch = inFlightUntilFailed(learnedBy);

            generationFailed(batch.batchId(), batch.payloadFileId(), learnedBy);

            softly.assertThat(roundTripsTimed())
                    .as("a refusal is an outcome - batchCompleted counts FAILED as one - and a "
                            + "renderer that takes forty minutes to say no is exactly what a "
                            + "latency series is read for; timing only the documents would leave "
                            + "that invisible")
                    .isEqualTo(1);
            softly.assertThat(roundTripSeconds())
                    .as("requested_at to the store's own failed_at, which is 107 seconds and not "
                            + "the 93 the renderer's failedTime argument would have given: the "
                            + "store stamps that column itself and drops what it was handed")
                    .isEqualTo(Duration.between(REQUESTED_AT, FAILURE_RECORDED_AT).toSeconds());
            softly.assertThat(roundTripLabels())
                    .as("and the failure's series is the same unlabelled series, not one split by "
                            + "outcome")
                    .isEmpty();
        }

        /**
         * An outcome this service cannot attribute closes no round trip, because there is no batch
         * whose row the two instants could be read from. Counting a nought here would put a
         * renderer that answers instantly into the same reading as another consumer's event.
         */
        @Test
        void an_outcome_no_batch_answers_to_should_time_nothing() {
            documentAvailable(UUID.randomUUID(), UUID.randomUUID(), CompletedBy.EVENT);

            softly.assertThat(roundTripsTimed())
                    .as("no batch, no row, no two instants, no reading")
                    .isEqualTo(ABSENT);
        }

        /**
         * The redelivery a durable subscription guarantees, which moves the batch nowhere: the
         * round trip it would time was timed when the first arrival closed it, and timing it again
         * would make one render read as two - halving the average every time the broker did what a
         * durable subscription is for.
         */
        @Test
        void an_outcome_that_moved_nothing_should_time_nothing() {
            final RegisterBatch batch = generated(generating(MONDAY), CompletedBy.EVENT);
            when(batches.findById(batch.batchId())).thenReturn(Optional.of(batch));

            documentAvailable(batch.batchId(), batch.payloadFileId(), CompletedBy.RECONCILER);

            softly.assertThat(roundTripsTimed())
                    .as("the batch already stands where the outcome would put it, so nothing was "
                            + "marked and nothing is timed")
                    .isEqualTo(ABSENT);
        }

        /**
         * The one shape the two columns can take that is not a round trip.
         *
         * <p>{@code requested_at} is the store's own {@code now()} and {@code generated_at} is
         * systemdocgenerator's account of when it rendered, so a generator whose clock is behind
         * this service's database puts the outcome before the request. That is a clock to fix
         * rather than a latency to alert on, and Micrometer would drop the negative silently one
         * layer down - so it is refused here, where the refusal can be read.
         */
        @Test
        void two_clocks_disagreeing_should_not_be_timed_as_a_round_trip() {
            final RegisterBatch batch = generating(MONDAY);
            final RegisterBatch impossible = new RegisterBatch(batch.batchId(), COURT_CENTRE,
                    OU_CODE, COURT_HOUSE, MONDAY, batch.fileName(), batch.payloadFileId(),
                    DOCUMENT_FILE_ID, BatchStatus.GENERATED, null, null, true, CompletedBy.EVENT,
                    ASSEMBLED_AT, REQUESTED_AT, GENERATED_BEFORE_IT_WAS_ASKED_FOR, null, null, 1,
                    null, 0);
            when(batches.findById(batch.batchId()))
                    .thenReturn(Optional.of(batch)).thenReturn(Optional.of(impossible));

            documentAvailable(batch.batchId(), batch.payloadFileId(), CompletedBy.EVENT);

            told("the document is still recorded - a clock disagreement is not a reason to throw "
                            + "a register away",
                    () -> verify(store).markGenerated(
                            batch.batchId(), DOCUMENT_FILE_ID, GENERATED_AT, CompletedBy.EVENT));
            softly.assertThat(roundTripsTimed())
                    .as("and the negative round trip is not recorded, because a histogram that "
                            + "accepted it would answer questions about a clock rather than about "
                            + "a renderer")
                    .isEqualTo(ABSENT);
        }
    }
}
