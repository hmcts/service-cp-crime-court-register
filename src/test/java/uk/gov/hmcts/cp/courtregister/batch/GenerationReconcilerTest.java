package uk.gov.hmcts.cp.courtregister.batch;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.annotation.Scheduled;
import uk.gov.hmcts.cp.courtregister.application.DocumentOutcomeSink;
import uk.gov.hmcts.cp.courtregister.application.DocumentRenderer;
import uk.gov.hmcts.cp.courtregister.application.RegisterStore;
import uk.gov.hmcts.cp.courtregister.config.GenerationMetrics;
import uk.gov.hmcts.cp.courtregister.domain.BatchFailureReason;
import uk.gov.hmcts.cp.courtregister.domain.BatchStatus;
import uk.gov.hmcts.cp.courtregister.domain.CallerIdentity;
import uk.gov.hmcts.cp.courtregister.domain.CompletedBy;
import uk.gov.hmcts.cp.courtregister.domain.DocumentStatus;
import uk.gov.hmcts.cp.courtregister.domain.FailureClassification;
import uk.gov.hmcts.cp.courtregister.domain.GenerationFailedException;
import uk.gov.hmcts.cp.courtregister.domain.RegisterBatch;
import uk.gov.hmcts.cp.courtregister.persistence.RegisterBatchRepository;
import uk.gov.hmcts.cp.courtregister.support.AdjustableClock;
import uk.gov.hmcts.cp.courtregister.support.CapturedLog;
import uk.gov.hmcts.cp.courtregister.support.PersonalDataMarkers;

/**
 * The safety net, asserted as the only thing standing between a lost event and a lost night.
 *
 * <p>Completion normally arrives on the {@code public.event} topic, and everything about that path
 * is somebody else's suite. This one is about the path that exists because that one can fail
 * silently: a subscription that was down when the event was published, a broker that dropped it, a
 * selector that was wrong for one deployment. In every one of those the batch sits in GENERATING and
 * nothing ever says so, and the registers for that court centre and day are never sent to the Youth
 * Offending Teams waiting for them.
 *
 * <p>So four things are pinned here, and they are the four the plan's test matrix names.
 *
 * <ul>
 *   <li><strong>The grace period is measured back from now</strong>, and is the whole of the
 *       selection: batches are read by {@code RegisterBatchRepository.generatingSince(cutoff)}, so
 *       what this class decides is the cutoff and nothing else. A cutoff computed from a run's start
 *       rather than from the clock would drift by however long the run before it took.</li>
 *   <li><strong>An answer is applied through the sink</strong>, the same {@link DocumentOutcomeSink}
 *       the listener drives, naming RECONCILER rather than EVENT. One code path for both mechanisms
 *       is what makes a duplicate absorbable and what keeps defect P3's scoping in one place; a
 *       reconciler that wrote the store itself would be a second implementation of the outcome
 *       rules, and the second one is always the one that gets P3 wrong again.</li>
 *   <li><strong>Silence is failed GENERATION_TIMED_OUT</strong>, through the store rather than the
 *       sink, because it is this service's own verdict about a render nobody answered for and not an
 *       answer anybody gave. It still names RECONCILER, which is what
 *       {@link BatchFailureReason#isGeneratorAttributed()} requires of that reason and what
 *       {@code register_batch_completed_by_shape_chk} enforces of the row.</li>
 *   <li><strong>Every completion this class reaches is counted</strong> on {@code reconciled} and
 *       returned to the run report. The counter's question is how many of tonight's outcomes the
 *       topic failed to deliver, and a batch nobody ever answered for is the worst instance of that,
 *       not an exception to it - which is also the set of rows {@code completed_by} names
 *       RECONCILER, so the metric and the table cannot disagree.</li>
 * </ul>
 *
 * <p>Two failure shapes are pinned alongside them because both are ways the safety net could quietly
 * become the thing that loses a batch. A query that could not be answered is not a generation that
 * failed, so the batch stays GENERATING and is asked again next time rather than being failed on the
 * strength of an outage here; and one batch's trouble does not end the run, because the batches after
 * it are exactly the ones that have been waiting longest.
 *
 * <p>The last case is the privacy one. systemdocgenerator's words about a failure are another
 * system's free text about a document whose every defendant is a child, and this class is the one
 * place in the flow that reads them outside the sink. They travel to {@code sdg_reason} and they do
 * not travel into a log line an estate-wide index keeps for a year (constitution Principle VII).
 */
@ExtendWith(SoftAssertionsExtension.class)
@DisplayName("the grace-period reconciler")
class GenerationReconcilerTest {

    /** Quoted by every case, so the red run names the seam it is waiting on. */
    private static final String PENDING = "T048 implements the reconciler; this is its red run";

    /** Returned when the seam refused, so an uncounted run fails as an assertion. */
    private static final int NOT_RECONCILED = -1;

    /** Returned when a meter is absent, so a missing instrument fails as an assertion. */
    private static final double ABSENT = -1;

    private static final Duration GRACE = Duration.ofMinutes(10);

    private static final Instant NOW = Instant.parse("2026-03-02T19:30:00Z");
    private static final Instant REQUESTED_AT = NOW.minus(Duration.ofMinutes(25));
    private static final Instant ASSEMBLED_AT = NOW.minus(Duration.ofMinutes(30));
    private static final Instant GENERATED_AT = NOW.minus(Duration.ofMinutes(20));
    private static final Instant FAILED_AT = NOW.minus(Duration.ofMinutes(18));

    private static final UUID DOCUMENT_FILE_ID =
            UUID.fromString("6d1f1d8c-2b0e-4a51-9f2f-9b6e9d3a4c11");
    private static final LocalDate REGISTER_DATE = LocalDate.of(2026, 3, 2);

    /**
     * systemdocgenerator's own words, carrying the marker no other value in this repository
     * produces: the privacy case can then say the reason was never written down rather than that
     * nothing recognisable was.
     */
    private static final String SDG_REASON =
            "OEE_Layout5 refused the payload: no date of birth for "
                    + PersonalDataMarkers.CHILD_NAME;

    private final RegisterBatchRepository batches = mock(RegisterBatchRepository.class);
    private final DocumentRenderer renderer = mock(DocumentRenderer.class);
    private final DocumentOutcomeSink sink = mock(DocumentOutcomeSink.class);
    private final RegisterStore store = mock(RegisterStore.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final GenerationMetrics metrics = new GenerationMetrics(registry);
    private final AdjustableClock clock = AdjustableClock.startingAt(NOW);

    private final GenerationReconciler reconciler =
            new GenerationReconciler(batches, renderer, sink, store, metrics, GRACE, clock);

    @InjectSoftAssertions
    private SoftAssertions softly;

    /**
     * Runs one reconciliation.
     *
     * <p>The call is made inside {@code assertThatCode(...).doesNotThrowAnyException()} so that the
     * seam's refusal is recorded as an assertion rather than ending the case, and the count it did
     * not produce is then asserted on as {@link #NOT_RECONCILED} - the red run is the count and the
     * green run is the same assertions unchanged.
     *
     * @return how many batches the reconciler completed, or {@link #NOT_RECONCILED} where the seam
     *     refused
     */
    private int reconcile() {
        final AtomicInteger completed = new AtomicInteger(NOT_RECONCILED);
        softly.assertThatCode(() -> completed.set(reconciler.reconcile()))
                .as(PENDING)
                .doesNotThrowAnyException();
        return completed.get();
    }

    /** A batch in the state the reconciler reads: requested, and not answered for since. */
    private static RegisterBatch generating(final UUID payloadFileId, final Instant requestedAt) {
        return new RegisterBatch(UUID.randomUUID(), UUID.randomUUID(), "B01OU", "Court House",
                REGISTER_DATE, "CourtRegister_B01OU_2026-03-02.pdf", payloadFileId, null,
                BatchStatus.GENERATING, null, null, true, null, ASSEMBLED_AT, requestedAt, null,
                null, null, 1, null, 0);
    }

    /**
     * A batch left where nothing else looks: PENDING, with a payload id and no request recorded.
     *
     * @param assembledAt when the batch was stamped onto its rows, which is all this batch has
     * @return the batch as the stale-PENDING read returns it
     */
    private static RegisterBatch stalePending(final Instant assembledAt) {
        return new RegisterBatch(UUID.randomUUID(), UUID.randomUUID(), "B01OU", "Court House",
                REGISTER_DATE, "CourtRegister_B01OU_2026-03-02.pdf", UUID.randomUUID(), null,
                BatchStatus.PENDING, null, null, true, null, assembledAt, null, null, null, null,
                0, null, 0);
    }

    /** A batch overdue by fifteen minutes, which is the ordinary subject of every case here. */
    private static RegisterBatch overdue() {
        return generating(UUID.randomUUID(), REQUESTED_AT);
    }

    /**
     * A batch holding a document nobody was told about: GENERATED, and never notified.
     *
     * @param generatedAt when its document arrived, which is all the age can be measured from
     * @return the batch as the parked read returns it
     */
    private static RegisterBatch parked(final Instant generatedAt) {
        return new RegisterBatch(UUID.randomUUID(), UUID.randomUUID(), "B01OU", "Court House",
                REGISTER_DATE, "CourtRegister_B01OU_2026-03-02.pdf", UUID.randomUUID(),
                DOCUMENT_FILE_ID, BatchStatus.GENERATED, null, null, true, CompletedBy.EVENT,
                ASSEMBLED_AT, REQUESTED_AT, generatedAt, null, null, 1, null, 0);
    }

    /** What the repository's overdue read answers this time. */
    private void generatingSince(final RegisterBatch... overdue) {
        when(batches.generatingSince(any())).thenReturn(List.of(overdue));
    }

    /** What the repository's stale-PENDING read answers this time. */
    private void pendingSince(final RegisterBatch... stale) {
        when(batches.pendingSince(any())).thenReturn(List.of(stale));
    }

    /** What the repository's parked-at-GENERATED read answers this time. */
    private void generatedSince(final RegisterBatch... parked) {
        when(batches.generatedSince(any())).thenReturn(List.of(parked));
    }

    /** What systemdocgenerator says about one batch's payload. */
    private void answers(final RegisterBatch batch, final DocumentStatus status) {
        when(renderer.query(eq(batch.payloadFileId()), any())).thenReturn(Optional.of(status));
    }

    /** systemdocgenerator with nothing to say about one batch's payload. */
    private void saysNothingAbout(final RegisterBatch batch) {
        when(renderer.query(eq(batch.payloadFileId()), any())).thenReturn(Optional.empty());
    }

    private double reconciled() {
        final Counter counter = registry.find(GenerationMetrics.GENERATION_RECONCILED).counter();
        return counter == null ? ABSENT : counter.count();
    }

    private static List<String> atInfoOrAbove(final CapturedLog log) {
        final List<String> written = new ArrayList<>();
        log.events().stream()
                .filter(event -> event.getLevel().isGreaterOrEqual(Level.INFO))
                .forEach(event -> {
                    written.add(event.getFormattedMessage());
                    Stream.ofNullable(event.getArgumentArray())
                            .flatMap(Stream::of)
                            .filter(Objects::nonNull)
                            .map(String::valueOf)
                            .forEach(written::add);
                });
        return written;
    }

    /**
     * Which batches are asked about at all, which is the only selection this class makes.
     */
    @Nested
    @DisplayName("the batches it asks about")
    class Selection {

        @Test
        void the_overdue_read_should_be_the_grace_period_back_from_now() {
            generatingSince();

            reconcile();

            final ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
            verify(batches).generatingSince(cutoff.capture());
            softly.assertThat(cutoff.getValue())
                    .as("the grace period is how long a batch may wait for its event, so the far "
                            + "edge of it is now minus the period and nothing else")
                    .isEqualTo(NOW.minus(GRACE));
        }

        @Test
        void a_later_run_should_move_the_cutoff_with_the_clock() {
            generatingSince();

            reconcile();
            clock.advance(Duration.ofMinutes(5));
            reconcile();

            final ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
            verify(batches, times(2)).generatingSince(cutoff.capture());
            softly.assertThat(cutoff.getAllValues())
                    .as("measured from the clock and not from a run's own start: a cutoff carried "
                            + "over from the last run would let a batch requested since then be "
                            + "asked about before its grace period had passed")
                    .containsExactly(NOW.minus(GRACE), NOW.plus(Duration.ofMinutes(5)).minus(GRACE));
        }

        @Test
        void a_run_with_nothing_overdue_should_ask_the_renderer_nothing() {
            generatingSince();

            final int completed = reconcile();

            softly.assertThat(completed)
                    .as("the ordinary night: every outcome arrived on the topic and the safety net "
                            + "had nothing to catch")
                    .isZero();
            verifyNoInteractions(renderer, sink, store);
            softly.assertThat(reconciled())
                    .as("and a counter that moved on a run that reconciled nothing would make "
                            + "every night look like a broker to investigate")
                    .isEqualTo(ABSENT);
        }
    }

    /**
     * The document existed all along and the event that said so never arrived.
     */
    @Nested
    @DisplayName("a document the topic never delivered")
    class DocumentFetched {

        private final RegisterBatch batch = overdue();

        private void generatedDocument() {
            generatingSince(batch);
            answers(batch, new DocumentStatus(DOCUMENT_FILE_ID, GENERATED_AT, null, null));
        }

        @Test
        void an_overdue_batch_should_be_asked_about_once_by_the_payload_it_was_rendered_from() {
            generatedDocument();

            reconcile();

            final ArgumentCaptor<UUID> asked = ArgumentCaptor.forClass(UUID.class);
            verify(renderer, times(1)).query(asked.capture(), any());
            softly.assertThat(asked.getValue())
                    .as("the query API is addressed by the payload file id - the id this service "
                            + "minted and sent as payloadFileServiceId - and not by the batch "
                            + "identity, which is systemdocgenerator's correlation and nothing it "
                            + "can be asked about")
                    .isEqualTo(batch.payloadFileId());
        }

        @Test
        void the_query_should_be_made_as_the_system_identity() {
            generatedDocument();

            reconcile();

            final ArgumentCaptor<CallerIdentity> caller =
                    ArgumentCaptor.forClass(CallerIdentity.class);
            verify(renderer).query(eq(batch.payloadFileId()), caller.capture());
            softly.assertThat(caller.getValue())
                    .as("nobody asked for this call: it is the schedule making good on an event "
                            + "that never arrived, so it is made under the configured system "
                            + "identity rather than attributed to whichever user shared the "
                            + "results hours earlier")
                    .isEqualTo(CallerIdentity.SYSTEM);
        }

        @Test
        void a_fetched_document_should_be_applied_through_the_sink_as_the_reconciler() {
            generatedDocument();

            reconcile();

            verify(sink).documentAvailable(batch.batchId(), batch.payloadFileId(),
                    DOCUMENT_FILE_ID, GENERATED_AT, CompletedBy.RECONCILER);
        }

        @Test
        void a_fetched_document_should_not_reach_the_store_directly() {
            generatedDocument();

            reconcile();

            verifyNoInteractions(store);
        }

        @Test
        void a_fetched_document_should_be_counted_and_reported_as_reconciled() {
            generatedDocument();

            final int completed = reconcile();

            softly.assertThat(reconciled())
                    .as("an outcome this service had to go and fetch is the one reading that says "
                            + "the event path needs looking at")
                    .isEqualTo(1);
            softly.assertThat(completed)
                    .as("and the run report carries the same number, so an operator reading the "
                            + "report and an operator reading the counter see one story")
                    .isEqualTo(1);
        }

        @Test
        void a_document_id_without_a_generated_time_should_not_be_taken_for_a_document() {
            generatingSince(batch);
            answers(batch, new DocumentStatus(DOCUMENT_FILE_ID, null, null, null));

            reconcile();

            verifyNoInteractions(sink);
            verify(store).markFailed(batch.batchId(), BatchFailureReason.GENERATION_TIMED_OUT,
                    null, CompletedBy.RECONCILER);
        }
    }

    /**
     * The generation failed and the event that said so never arrived, which is defect P2's shape
     * reached by the other road.
     */
    @Nested
    @DisplayName("a refusal the topic never delivered")
    class RefusalFetched {

        private final RegisterBatch batch = overdue();

        @Test
        void a_fetched_refusal_should_be_applied_through_the_sink_with_the_renderers_words() {
            generatingSince(batch);
            answers(batch, new DocumentStatus(null, null, FAILED_AT, SDG_REASON));

            reconcile();

            verify(sink).generationFailed(batch.batchId(), batch.payloadFileId(), SDG_REASON,
                    FAILED_AT, CompletedBy.RECONCILER);
        }

        @Test
        void a_refusal_with_no_words_should_still_be_applied_as_a_refusal() {
            generatingSince(batch);
            answers(batch, new DocumentStatus(null, null, FAILED_AT, null));

            reconcile();

            verify(sink).generationFailed(batch.batchId(), batch.payloadFileId(), null, FAILED_AT,
                    CompletedBy.RECONCILER);
            verify(store, never()).markFailed(any(), any(), any(), any());
        }

        @Test
        void a_fetched_refusal_should_be_counted_and_reported_as_reconciled() {
            generatingSince(batch);
            answers(batch, new DocumentStatus(null, null, FAILED_AT, SDG_REASON));

            final int completed = reconcile();

            softly.assertThat(reconciled())
                    .as("a refusal fetched here is an outcome the topic owed and did not deliver, "
                            + "exactly as a document is")
                    .isEqualTo(1);
            softly.assertThat(completed)
                    .as("and it is one of the batches this run completed rather than the topic")
                    .isEqualTo(1);
        }
    }

    /**
     * Two systems have had their chance to say what happened and neither has anything to say.
     */
    @Nested
    @DisplayName("a renderer with nothing to say")
    class Silence {

        private final RegisterBatch batch = overdue();

        @Test
        void a_batch_nothing_can_be_learned_about_should_be_failed_generation_timed_out() {
            generatingSince(batch);
            saysNothingAbout(batch);

            reconcile();

            verify(store).markFailed(batch.batchId(), BatchFailureReason.GENERATION_TIMED_OUT,
                    null, CompletedBy.RECONCILER);
        }

        @Test
        void a_status_that_says_neither_should_be_read_as_silence() {
            generatingSince(batch);
            answers(batch, new DocumentStatus(null, null, null, null));

            reconcile();

            verify(store).markFailed(batch.batchId(), BatchFailureReason.GENERATION_TIMED_OUT,
                    null, CompletedBy.RECONCILER);
            verifyNoInteractions(sink);
        }

        @Test
        void a_timed_out_batch_should_not_be_applied_through_the_sink() {
            generatingSince(batch);
            saysNothingAbout(batch);

            reconcile();

            verifyNoInteractions(sink);
        }

        @Test
        void a_timed_out_batch_should_be_asked_about_once_and_not_again() {
            generatingSince(batch);
            saysNothingAbout(batch);

            reconcile();

            verify(renderer, times(1)).query(batch.payloadFileId(), CallerIdentity.SYSTEM);
        }

        @Test
        void a_timed_out_batch_should_be_counted_and_reported_as_reconciled() {
            generatingSince(batch);
            saysNothingAbout(batch);

            final int completed = reconcile();

            softly.assertThat(reconciled())
                    .as("the set the counter reports is the set completed_by names RECONCILER, "
                            + "and GENERATION_TIMED_OUT is one of the two reasons that carries it")
                    .isEqualTo(1);
            softly.assertThat(completed)
                    .as("a night whose batches all timed out is the loudest broker problem there "
                            + "is, and a report that counted none of them would be silent about it")
                    .isEqualTo(1);
        }
    }

    /**
     * The question itself could not be asked, which is not an answer about the render.
     */
    @Nested
    @DisplayName("a query that could not be answered")
    class QueryUnavailable {

        private final RegisterBatch batch = overdue();

        private void queryFails(final RegisterBatch subject) {
            when(renderer.query(eq(subject.payloadFileId()), any()))
                    .thenThrow(new GenerationFailedException(FailureClassification.TRANSIENT,
                            BatchFailureReason.GENERATION_TIMED_OUT));
        }

        @Test
        void a_query_that_failed_should_leave_the_batch_generating() {
            generatingSince(batch);
            queryFails(batch);

            final int completed = reconcile();

            verifyNoInteractions(sink);
            verify(store, never()).markFailed(any(), any(), any(), any());
            softly.assertThat(completed)
                    .as("systemdocgenerator being unreachable is not systemdocgenerator saying "
                            + "the render failed; the batch keeps its grace and is asked again "
                            + "next time rather than being failed on the strength of an outage "
                            + "here")
                    .isZero();
        }

        @Test
        void a_query_that_failed_should_not_be_counted_as_a_reconciliation() {
            generatingSince(batch);
            queryFails(batch);

            reconcile();

            softly.assertThat(reconciled())
                    .as("nothing was learned and nothing was decided, so there is no completion "
                            + "for the counter to report")
                    .isEqualTo(ABSENT);
        }

        @Test
        void a_query_that_failed_should_not_stop_the_run() {
            final RegisterBatch first = overdue();
            final RegisterBatch second = overdue();
            generatingSince(first, batch, second);
            queryFails(batch);
            answers(first, new DocumentStatus(DOCUMENT_FILE_ID, GENERATED_AT, null, null));
            saysNothingAbout(second);

            final int completed = reconcile();

            verify(sink).documentAvailable(first.batchId(), first.payloadFileId(), DOCUMENT_FILE_ID,
                    GENERATED_AT, CompletedBy.RECONCILER);
            verify(store).markFailed(second.batchId(), BatchFailureReason.GENERATION_TIMED_OUT,
                    null, CompletedBy.RECONCILER);
            softly.assertThat(completed)
                    .as("the overdue batches are read oldest first, so the ones after a batch that "
                            + "could not be asked about are the ones a Youth Offending Team has "
                            + "been waiting longest for")
                    .isEqualTo(2);
        }
    }

    /**
     * The batch that never reached the renderer at all, which nothing else in the flow revisits.
     *
     * <p>{@code RegisterGenerationService.storeAndRequest} mints the payload id, writes it down,
     * stores the payload, POSTs, and only then marks the batch requested. A pod that dies between
     * the 202 and that mark - or a store that blips on the mark itself - leaves the batch PENDING
     * with a payload id and its registers stamped, and from there nothing moves it again: the
     * overdue read above is GENERATING only, the stamped rows are outside {@code activeUnbatched},
     * the partial unique index defers every later re-share of that key for ever, and no counter or
     * gauge in the service is about it. It is the silent loss the constitution's Principle VI and
     * defect P5 exist to remove, reached one step earlier than either of them looks.
     *
     * <p>So the net is widened to it, and the answer comes from the same three places: what
     * systemdocgenerator says about the payload, applied through the sink; and, where it says
     * nothing, this service's own RENDER_REQUEST_FAILED - the reason the run itself would have used
     * for a request it could not get accepted, and the one an operator reads as "ask for it again".
     *
     * <p><strong>"Says nothing" is one of two answers, not both of them.</strong> A query that finds
     * no payload under the id is systemdocgenerator having no record of the request at all, and that
     * is what RENDER_REQUEST_FAILED describes: nothing outside this service ever accepted it, so
     * nothing outside this service is attributed. A query that answers about the payload and names
     * neither a document nor a refusal is the opposite reading - the request arrived, the renderer
     * took it, and the renderer is the one that has not finished. That batch is GENERATION_TIMED_OUT
     * and names RECONCILER, exactly as an overdue GENERATING batch with the same empty answer is: it
     * is the same silence from the same system about a render it is holding, and the state this
     * service's own mark was lost from says nothing about whose silence it is.
     */
    @Nested
    @DisplayName("a batch whose render request was never recorded")
    class StillPending {

        private final RegisterBatch batch = stalePending(ASSEMBLED_AT);

        @Test
        void the_stale_pending_read_should_be_the_grace_period_back_from_now() {
            generatingSince();

            reconcile();

            final ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
            verify(batches).pendingSince(cutoff.capture());
            softly.assertThat(cutoff.getValue())
                    .as("the same grace the topic is given, measured from the assembly this batch "
                            + "has instead of the request it never recorded")
                    .isEqualTo(NOW.minus(GRACE));
        }

        @Test
        void a_stale_pending_batch_should_be_asked_about_by_the_payload_it_minted() {
            generatingSince();
            pendingSince(batch);
            saysNothingAbout(batch);

            reconcile();

            verify(renderer, times(1)).query(batch.payloadFileId(), CallerIdentity.SYSTEM);
        }

        @Test
        void a_document_found_for_a_stale_pending_batch_should_be_applied_through_the_sink() {
            generatingSince();
            pendingSince(batch);
            answers(batch, new DocumentStatus(DOCUMENT_FILE_ID, GENERATED_AT, null, null));

            reconcile();

            verify(sink).documentAvailable(batch.batchId(), batch.payloadFileId(), DOCUMENT_FILE_ID,
                    GENERATED_AT, CompletedBy.RECONCILER);
            verify(store, never()).markFailed(any(), any(), any(), any());
        }

        @Test
        void a_refusal_found_for_a_stale_pending_batch_should_be_applied_through_the_sink() {
            generatingSince();
            pendingSince(batch);
            answers(batch, new DocumentStatus(null, null, FAILED_AT, SDG_REASON));

            reconcile();

            verify(sink).generationFailed(batch.batchId(), batch.payloadFileId(), SDG_REASON,
                    FAILED_AT, CompletedBy.RECONCILER);
        }

        @Test
        void a_stale_pending_batch_the_renderer_knows_about_should_be_timed_out_not_never_requested() {
            generatingSince();
            pendingSince(batch);
            answers(batch, new DocumentStatus(null, null, null, null));

            reconcile();

            verify(store).markFailed(batch.batchId(), BatchFailureReason.GENERATION_TIMED_OUT,
                    null, CompletedBy.RECONCILER);
            verifyNoInteractions(sink);
        }

        @Test
        void a_stale_pending_batch_nobody_has_a_record_of_should_be_failed_render_request_failed() {
            generatingSince();
            pendingSince(batch);
            saysNothingAbout(batch);

            reconcile();

            verify(store).markFailed(batch.batchId(), BatchFailureReason.RENDER_REQUEST_FAILED,
                    null, null);
            verifyNoInteractions(sink);
        }

        @Test
        void a_completed_stale_pending_batch_should_be_counted_and_reported_as_reconciled() {
            generatingSince();
            pendingSince(batch);
            saysNothingAbout(batch);

            final int completed = reconcile();

            softly.assertThat(reconciled())
                    .as("a batch this service had to go and settle is a completion the run made "
                            + "rather than one the topic delivered, whichever state it was stuck in")
                    .isEqualTo(1);
            softly.assertThat(completed)
                    .as("and the run report carries the same number")
                    .isEqualTo(1);
        }

        @Test
        void a_query_that_failed_should_leave_a_stale_pending_batch_where_it_is() {
            generatingSince();
            pendingSince(batch);
            when(renderer.query(eq(batch.payloadFileId()), any()))
                    .thenThrow(new GenerationFailedException(FailureClassification.TRANSIENT,
                            BatchFailureReason.GENERATION_TIMED_OUT));

            final int completed = reconcile();

            verifyNoInteractions(sink);
            verify(store, never()).markFailed(any(), any(), any(), any());
            softly.assertThat(completed)
                    .as("systemdocgenerator being unreachable says nothing about whether it holds "
                            + "this payload, and failing the batch on the strength of that would "
                            + "throw away a document that may exist")
                    .isZero();
        }

        @Test
        void the_oldest_pending_age_should_be_gauged_from_the_stale_pending_read() {
            generatingSince();
            pendingSince(stalePending(NOW.minus(Duration.ofMinutes(70))), batch);
            when(renderer.query(any(), any())).thenReturn(Optional.empty());

            reconcile();

            softly.assertThat(oldestPendingAge())
                    .as("the only reading in the service that moves for a batch stuck before the "
                            + "renderer was ever told about it")
                    .isEqualTo(Duration.ofMinutes(70).toSeconds());
        }

        @Test
        void a_sweep_with_nothing_stuck_at_pending_should_bring_the_gauge_back_down() {
            generatingSince();

            reconcile();

            softly.assertThat(oldestPendingAge())
                    .as("a gauge that only ever moved up would need a batch to be lost before it "
                            + "could come down again")
                    .isZero();
        }
    }

    /**
     * What the reconciler is allowed to write down.
     *
     * <p>It handles exactly one value that is not an identifier or a bounded code: systemdocgenerator's
     * own words about a refusal. Those go to {@code sdg_reason}, where support can read them, and
     * nowhere near a log line that reaches an estate-wide index and is kept for a year - every
     * defendant on the register they are about is a child (constitution Principle VII).
     */
    @Nested
    @DisplayName("what the reconciler writes down")
    class WhatItWritesDown {

        @Test
        void the_renderers_words_should_never_reach_a_line_at_info_or_above() {
            final RegisterBatch batch = overdue();
            generatingSince(batch);
            answers(batch, new DocumentStatus(null, null, FAILED_AT, SDG_REASON));

            try (CapturedLog log = CapturedLog.capturing(GenerationReconciler.class)) {
                reconcile();

                softly.assertThat(atInfoOrAbove(log))
                        .as("another system's free text about a document whose every defendant is "
                                + "a child; it is carried to sdg_reason and read at DEBUG, and an "
                                + "INFO line is neither of those")
                        .noneMatch(line -> line.contains(PersonalDataMarkers.CHILD_NAME));
            }

            verify(sink).generationFailed(batch.batchId(), batch.payloadFileId(), SDG_REASON,
                    FAILED_AT, CompletedBy.RECONCILER);
        }
    }

    /**
     * When it runs, and why that cannot be "whenever the flag let a run happen".
     *
     * <p>Reconciliation is about batches this service already owns. Whether it may generate tonight
     * is a different question with a different answer, and hanging the safety net off the flag gate
     * costs a night in both directions: a batch requested at 18:00 is first asked about at 18:01,
     * inside its own grace period, and then not again until the next evening - so a lost public
     * event costs about twenty-four hours rather than the ten minutes the grace period configures;
     * and on a night the flag reads OFF or unreadable the run touches nothing at all, so a batch
     * left GENERATING by an earlier ON night is never asked about again.
     *
     * <p>Hence a schedule of its own, on the same single thread the run uses, with a lock of its
     * own: two replicas asking systemdocgenerator about one batch would apply one outcome twice,
     * and the second application is what {@code BatchStatus} refuses rather than absorbs.
     *
     * <p>The gauge belongs here for the same reason. {@code courtregister_oldest_generating_age} is
     * declared by T010 and set by nothing, so the one reading that says "a batch has been waiting
     * for its document since before anybody was worried" has never moved off zero.
     */
    @Nested
    @DisplayName("when it runs, and what it leaves on the dashboard")
    class ItsOwnSchedule {

        @Test
        void reconciliation_is_scheduled_independently_of_the_flag_gate()
                throws NoSuchMethodException {
            final Method scheduled =
                    GenerationReconciler.class.getDeclaredMethod("reconcileScheduled");
            final Scheduled schedule = scheduled.getAnnotation(Scheduled.class);
            final SchedulerLock lock = scheduled.getAnnotation(SchedulerLock.class);

            softly.assertThat(scheduled.getReturnType())
                    .as("void, and not the count: ShedLock's interceptor refuses to lock a method "
                            + "returning a primitive - LockingNotSupportedException, raised on "
                            + "every call including the run's own - and a schedule has nobody to "
                            + "return a count to anyway")
                    .isEqualTo(void.class);
            softly.assertThat(schedule)
                    .as("the run calls this too, so its report can name what it fetched - but a "
                            + "safety net that only runs when the flag said the service may "
                            + "generate is no net on the nights the flag says it may not")
                    .isNotNull();
            softly.assertThat(schedule == null ? null : schedule.fixedDelayString())
                    .as("a cadence of the grace period, so a batch is asked about within one "
                            + "grace period of becoming overdue instead of within one day")
                    .isEqualTo("${courtregister.generation.grace-period}");
            softly.assertThat(lock)
                    .as("two replicas asking systemdocgenerator about one batch would apply one "
                            + "outcome twice, and the second application is refused rather than "
                            + "absorbed")
                    .isNotNull();
            softly.assertThat(lock == null ? null : lock.name())
                    .as("its own lock and not the run's: a reconciliation waiting on the lock a "
                            + "sixty-minute run holds is a reconciliation that never happens")
                    .isNotBlank()
                    .isNotEqualTo(RegisterGenerationJob.LOCK_NAME);
        }

        /**
         * The run calls the counting method, and the counting method carries no lock.
         *
         * <p>Two entry points because they answer different callers. The schedule wants a locked,
         * {@code void} pass; the run wants the count, for the report line that says how many of
         * tonight's outcomes the topic failed to deliver - and it is already inside the run's own
         * lock, so a second one on the same call would be a lock taken against itself.
         */
        @Test
        void the_counting_entry_point_should_carry_no_lock_of_its_own()
                throws NoSuchMethodException {
            final Method reconcile = GenerationReconciler.class.getDeclaredMethod("reconcile");

            softly.assertThat(reconcile.getAnnotation(SchedulerLock.class))
                    .as("the run holds the generation lock already, and ShedLock cannot lock a "
                            + "method returning a count in any case")
                    .isNull();
            softly.assertThat(reconcile.getAnnotation(Scheduled.class))
                    .as("and it is not the schedule's entry point, or the pass would happen twice "
                            + "every interval")
                    .isNull();
        }

        /**
         * And the scheduled pass is the same pass, not a second implementation of one.
         *
         * <p>What the schedule fires has to reach the same three collaborators the run's call does,
         * or the safety net that runs every ten minutes would be a different net from the one every
         * suite above pins.
         */
        @Test
        void the_scheduled_pass_should_do_the_work_the_run_asks_for() {
            final RegisterBatch batch = overdue();
            generatingSince(batch);
            saysNothingAbout(batch);

            softly.assertThatCode(reconciler::reconcileScheduled)
                    .as("the schedule's entry point is the counting one with its answer dropped, "
                            + "not a second reconciler")
                    .doesNotThrowAnyException();

            verify(store).markFailed(batch.batchId(), BatchFailureReason.GENERATION_TIMED_OUT, null,
                    CompletedBy.RECONCILER);
        }

        @Test
        void the_oldest_generating_age_should_be_gauged_from_the_overdue_read() {
            generatingSince(generating(UUID.randomUUID(), NOW.minus(Duration.ofMinutes(40))),
                    overdue());
            when(renderer.query(any(), any())).thenReturn(Optional.empty());

            reconcile();

            softly.assertThat(oldestGeneratingAge())
                    .as("the reading a nightly flow cannot be understood without between runs: how "
                            + "long the batch that has been waiting longest has been waiting")
                    .isEqualTo(Duration.ofMinutes(40).toSeconds());
        }

        @Test
        void a_run_with_nothing_overdue_should_bring_the_gauge_back_down() {
            generatingSince();

            reconcile();

            softly.assertThat(oldestGeneratingAge())
                    .as("a gauge that only ever moved up would need a batch to fail before it "
                            + "could come down again")
                    .isZero();
        }

        @Test
        void the_oldest_generated_age_should_be_gauged_from_the_parked_generated_read() {
            generatingSince();
            generatedSince(parked(NOW.minus(Duration.ofMinutes(70))), parked(GENERATED_AT));

            reconcile();

            softly.assertThat(oldestGeneratedAge())
                    .as("the batch that holds its document and told nobody is the one state no "
                            + "other reading moves for: the generating gauge reads GENERATING and "
                            + "the pending gauge reads PENDING, so a notification that never "
                            + "happened would otherwise be invisible - which is defect fix P1's "
                            + "failure mode by another route")
                    .isEqualTo(Duration.ofMinutes(70).toSeconds());
        }

        @Test
        void a_pass_with_nothing_parked_at_generated_should_bring_that_gauge_back_down() {
            generatingSince();

            reconcile();

            softly.assertThat(oldestGeneratedAge())
                    .as("a batch that was notified the moment its document arrived leaves the read "
                            + "empty, and the reading has to come back down with it")
                    .isZero();
        }

        @Test
        void a_parked_batch_should_be_reported_and_not_settled_here() {
            generatingSince();
            generatedSince(parked(NOW.minus(Duration.ofMinutes(70))));

            final int completed = reconcile();

            verifyNoInteractions(sink);
            verify(store, never()).markFailed(any(), any(), any(), any());
            softly.assertThat(completed)
                    .as("this read is a reading and not an ending: the batch holds a document that "
                            + "exists, so it is the notifier's to finish - through resendFailed or "
                            + "notify-register - and failing it here would throw that document "
                            + "away")
                    .isZero();
        }
    }

    /**
     * How long the render took, for the one ending that does not go through the sink.
     *
     * <p>{@code courtregister_generation_latency} is closed wherever a batch's render is answered,
     * "however the outcome arrived" - and this class produces two of those endings itself. The
     * answers it fetches go to {@code DocumentOutcomeSink}, which times them and is
     * {@code DocumentOutcomeSinkTest}'s subject; the silences go to {@code RegisterStore.markFailed}
     * directly, deliberately, because a batch nothing can be learned about has no outcome to apply
     * - so this is where those are timed and this suite is what says so.
     *
     * <p>Both instants come off the row rather than from the pass's own clock, for the same reason
     * they do in the sink: the pod that asked for the render is not always the pod that gives up on
     * it, and a reading taken from anything but the two columns would be a different number on
     * every pod.
     */
    @Nested
    @DisplayName("how long a render the reconciler gave up on took")
    class HowLongARenderItGaveUpOnTook {

        @Test
        void a_batch_given_up_on_should_time_the_round_trip_off_its_own_row() {
            final RegisterBatch batch = overdue();
            generatingSince(batch);
            saysNothingAbout(batch);
            settlingInto(failedAfterBeingRequested(batch));

            reconcile();

            softly.assertThat(roundTripsTimed())
                    .as("GENERATION_TIMED_OUT is an outcome the batch really reached, and a render "
                            + "that was still unanswered for after twenty-five minutes is the "
                            + "longest reading a night can produce; leaving it out would make the "
                            + "series describe only the renders that went well")
                    .isEqualTo(1);
            softly.assertThat(roundTripSeconds())
                    .as("requested_at to the failed_at the store stamped, both read back off the "
                            + "row this pass had just failed")
                    .isEqualTo(Duration.between(REQUESTED_AT, FAILED_AT).toSeconds());
            softly.assertThat(roundTripLabels())
                    .as("unlabelled, so nothing about the court centre or the batch can reach the "
                            + "series")
                    .isEmpty();
        }

        /**
         * The stale PENDING batch, which is the production path for "no render request to measure
         * from": RENDER_REQUEST_FAILED is this service's verdict that the 202 never happened, so
         * {@code requested_at} was never stamped and there is no start instant. A nought recorded
         * here would say systemdocgenerator answered instantly.
         */
        @Test
        void a_batch_that_never_reached_the_renderer_should_time_nothing() {
            final RegisterBatch batch = stalePending(ASSEMBLED_AT);
            generatingSince();
            pendingSince(batch);
            saysNothingAbout(batch);
            settlingInto(failedWithoutEverBeingRequested(batch));

            reconcile();

            verify(store).markFailed(batch.batchId(), BatchFailureReason.RENDER_REQUEST_FAILED,
                    null, null);
            softly.assertThat(roundTripsTimed())
                    .as("the batch reached a terminal state and still has no round trip, because "
                            + "one end of it never happened")
                    .isEqualTo(ABSENT);
        }

        @Test
        void a_batch_still_waiting_for_its_answer_should_time_nothing() {
            final RegisterBatch batch = overdue();
            generatingSince(batch);
            when(renderer.query(eq(batch.payloadFileId()), any()))
                    .thenThrow(new GenerationFailedException(FailureClassification.TRANSIENT,
                            BatchFailureReason.GENERATION_TIMED_OUT));

            reconcile();

            softly.assertThat(roundTripsTimed())
                    .as("a batch that reached no outcome has no round trip to close; it keeps its "
                            + "grace and the reading waits with it")
                    .isEqualTo(ABSENT);
        }
    }

    /** What the row read back after a mark answers, which is what the round trip is taken from. */
    private void settlingInto(final RegisterBatch settled) {
        when(batches.findById(settled.batchId())).thenReturn(Optional.of(settled));
    }

    /** The row a GENERATION_TIMED_OUT ending leaves: requested, and stamped as failed. */
    private static RegisterBatch failedAfterBeingRequested(final RegisterBatch batch) {
        return new RegisterBatch(batch.batchId(), batch.courtCentreId(), "B01OU", "Court House",
                REGISTER_DATE, batch.fileName(), batch.payloadFileId(), null, BatchStatus.FAILED,
                BatchFailureReason.GENERATION_TIMED_OUT, null, true, CompletedBy.RECONCILER,
                ASSEMBLED_AT, REQUESTED_AT, null, null, FAILED_AT, 1, null, 0);
    }

    /** The row a RENDER_REQUEST_FAILED ending leaves: no request was ever recorded for it. */
    private static RegisterBatch failedWithoutEverBeingRequested(final RegisterBatch batch) {
        return new RegisterBatch(batch.batchId(), batch.courtCentreId(), "B01OU", "Court House",
                REGISTER_DATE, batch.fileName(), batch.payloadFileId(), null, BatchStatus.FAILED,
                BatchFailureReason.RENDER_REQUEST_FAILED, null, true, null, ASSEMBLED_AT, null,
                null, null, FAILED_AT, 0, null, 0);
    }

    private double roundTripsTimed() {
        final Timer timer = registry.find(GenerationMetrics.GENERATION_LATENCY).timer();
        return timer == null ? ABSENT : timer.count();
    }

    private double roundTripSeconds() {
        final Timer timer = registry.find(GenerationMetrics.GENERATION_LATENCY).timer();
        return timer == null ? ABSENT : timer.totalTime(TimeUnit.SECONDS);
    }

    private List<String> roundTripLabels() {
        final Timer timer = registry.find(GenerationMetrics.GENERATION_LATENCY).timer();
        return timer == null
                ? List.of("<the timer recorded nothing>")
                : timer.getId().getTags().stream().map(Tag::getKey).toList();
    }

    private double oldestGeneratingAge() {
        final Gauge gauge = registry.find(GenerationMetrics.OLDEST_GENERATING_AGE).gauge();
        return gauge == null ? ABSENT : gauge.value();
    }

    private double oldestPendingAge() {
        final Gauge gauge = registry.find(GenerationMetrics.OLDEST_PENDING_AGE).gauge();
        return gauge == null ? ABSENT : gauge.value();
    }

    private double oldestGeneratedAge() {
        final Gauge gauge = registry.find(GenerationMetrics.OLDEST_GENERATED_AGE).gauge();
        return gauge == null ? ABSENT : gauge.value();
    }
}
