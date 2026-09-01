package uk.gov.hmcts.cp.courtregister.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uk.gov.hmcts.cp.courtregister.config.JacksonConfig;
import uk.gov.hmcts.cp.courtregister.config.ProcessingMetrics;
import uk.gov.hmcts.cp.courtregister.domain.CallerIdentity;
import uk.gov.hmcts.cp.courtregister.domain.CompletionReason;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterDocument;
import uk.gov.hmcts.cp.courtregister.domain.DeadLetterReason;
import uk.gov.hmcts.cp.courtregister.domain.DeliveryIdentity;
import uk.gov.hmcts.cp.courtregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.courtregister.domain.FailureClassification;
import uk.gov.hmcts.cp.courtregister.domain.GuardDecision;
import uk.gov.hmcts.cp.courtregister.domain.NoRegisterReason;
import uk.gov.hmcts.cp.courtregister.domain.PayloadUnavailableException;
import uk.gov.hmcts.cp.courtregister.domain.ReasonCode;
import uk.gov.hmcts.cp.courtregister.domain.ReferenceDataUnavailableException;
import uk.gov.hmcts.cp.courtregister.domain.RunClaim;
import uk.gov.hmcts.cp.courtregister.domain.SubmissionFailedException;
import uk.gov.hmcts.cp.courtregister.domain.TransformationAnomaly;
import uk.gov.hmcts.cp.courtregister.domain.TransformationFailedException;
import uk.gov.hmcts.cp.courtregister.pipeline.Dates;
import uk.gov.hmcts.cp.courtregister.support.AdjustableClock;
import uk.gov.hmcts.cp.courtregister.support.CapturedLog;

/**
 * One request, from the payload fetch to the recorded outcome — the orchestration the legacy has no
 * test for at all.
 *
 * <p>{@code CourtRegisterOrchestrator/index.js} has no test file. Seventy-seven lines, five
 * activities, four silent guards and a catch-all that reports failure from a completed
 * orchestration, and nothing in the repository executes any of it. Every case in this file is
 * therefore written from the source rather than twinned, and four catalogued defects live in what it
 * asserts:
 *
 * <ul>
 *   <li><strong>C2</strong> — the catch-all at {@code :70-76} returns {@code {Success:false}} from an
 *       orchestration that <em>completed</em>, and reads {@code context.df.getInput()} inside the
 *       catch, which can itself throw. Nothing is recorded either way.</li>
 *   <li><strong>C6</strong> — a hearing that gathers no defendants flows on as an empty register.</li>
 *   <li><strong>C32</strong> — {@code if (hearingResultedObj)} at {@code :20} is false when the cache
 *       missed and the query fallback returned nothing, and the run stops there, silently, reporting
 *       success.</li>
 *   <li><strong>C33</strong> — those four guards and the bare {@code null} from
 *       {@code OutboundCourtRegister} all end in one undifferentiated {@code Success: true}, and two
 *       of them are this flow's commonest legitimate outcomes.</li>
 * </ul>
 *
 * <p>The suite drives the pipeline over test doubles for its four ports, because what is asserted
 * here is the order the stages run in, what each is handed, and what is recorded when one of them
 * refuses. The stages' own behaviour is asserted in their own suites.
 *
 * @see <a href="file:../../../../../../../../doc/DEFECT-FIXES.md">doc/DEFECT-FIXES.md</a> rows C2,
 *     C6, C7, C32 and C33
 */
@DisplayName("DistributionPipeline")
class DistributionPipelineTest {

    /** Returned when a meter is absent, so a missing count fails as an assertion. */
    private static final double ABSENT = -1;

    /** Long enough that no case in this file reaches its processing deadline. */
    private static final Duration RUN_DEADLINE = Duration.ofMinutes(5);

    private final ObjectMapper mapper = JacksonConfig.contractObjectMapper();

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ProcessingMetrics metrics = new ProcessingMetrics(registry);

    private final IdempotencyGuard guard = mock(IdempotencyGuard.class);
    private final HearingPayloadSource payloadSource = mock(HearingPayloadSource.class);
    private final GroupProceedingsPolicy groupProceedings = mock(GroupProceedingsPolicy.class);
    private final NowSubscriptionsSource subscriptionsSource = mock(NowSubscriptionsSource.class);
    private final RegisterTransformer transformer = mock(RegisterTransformer.class);
    private final RegisterSubmissionClient submissionClient = mock(RegisterSubmissionClient.class);

    private final DistributionCommand command = new DistributionCommand(
            "RESULTS",
            UUID.fromString("9f1b8e2a-5c34-4a7d-9b1e-2f6a0d3c5e71"),
            UUID.fromString("1828f356-f746-4f2d-932b-79ef2df95c80"),
            LocalDate.parse("2020-06-01"),
            Instant.parse("2020-06-01T10:00:00Z"),
            "Hearing_Resulted",
            java.util.Optional.of(UUID.fromString("6e2f0a1c-9d4b-4f38-8a52-1c7b3e5d9f04")));

    private final RunClaim claim = new RunClaim(
            command.source(), command.requestId(), "runner-1", UUID.randomUUID(), "msg-1");

    private final CourtRegisterDocument document = new CourtRegisterDocument(
            "2020-06-01T10:00:00Z",
            "2020-01-20T00:00:00Z",
            command.hearingId().toString(),
            "853b1ff8-fc2a-44d1-a621-0cd16419f54a",
            "court-register_2020-06-01_B01LY00_" + command.hearingId() + ".pdf",
            null,
            null,
            null);

    private final JsonNode payload = mapper.readTree(
            "{\"hearing\":{\"id\":\"1828f356-f746-4f2d-932b-79ef2df95c80\"},"
                    + "\"sharedTime\":\"2020-06-01T10:00:00Z\"}");

    /** Reference data's answer for the register's day: an answer, carrying nobody. */
    private final JsonNode subscriptions = mapper.readTree("{\"nowSubscriptions\":[]}");

    @BeforeEach
    void theOrdinaryRun() {
        when(guard.admit(any(DistributionCommand.class), any(DeliveryIdentity.class)))
                .thenReturn(new GuardDecision.Run(claim));
        when(guard.recordCompletion(any(RunClaim.class), any(CompletionReason.class)))
                .thenReturn(new GuardDecision.Complete(ReasonCode.RUN_COMPLETED));
        when(guard.recordTransientFailure(any(RunClaim.class), any(ReasonCode.class)))
                .thenAnswer(call -> new GuardDecision.Abandon(call.getArgument(1)));
        when(guard.recordNonTransientFailure(any(RunClaim.class), any(ReasonCode.class)))
                .thenAnswer(call -> new GuardDecision.DeadLetter(
                        DeadLetterReason.NON_TRANSIENT, call.getArgument(1)));
        when(guard.recordExhaustion(any(RunClaim.class), any(ReasonCode.class)))
                .thenAnswer(call -> new GuardDecision.DeadLetter(
                        DeadLetterReason.EXHAUSTED, call.getArgument(1)));

        when(payloadSource.fetch(any(DistributionCommand.class))).thenReturn(payload);
        when(groupProceedings.suppresses(any(DistributionCommand.class), any(JsonNode.class)))
                .thenReturn(false);
        when(subscriptionsSource.subscriptionsOn(any(LocalDate.class), any(CallerIdentity.class)))
                .thenReturn(subscriptions);
        when(transformer.transform(any(DistributionCommand.class), any(JsonNode.class),
                    any(JsonNode.class), any()))
                .thenReturn(new TransformationResult.Register(document));
        when(submissionClient.submit(any(CourtRegisterDocument.class), any(CallerIdentity.class), any()))
                .thenReturn(new SubmissionReceipt(202));
    }

    /** N1 and N6: the order the stages run in, and what each of them is handed. */
    @Nested
    @DisplayName("the stage sequence")
    class StageSequence {

        @Test
        @DisplayName("fetches, decides, reads reference data, transforms, submits, records — in "
                + "that order")
        void runs_the_stages_in_order() {
            final GuardDecision decision = run();

            final InOrder stages = inOrder(payloadSource, groupProceedings, subscriptionsSource,
                    transformer, submissionClient, guard);
            stages.verify(payloadSource).fetch(command);
            stages.verify(groupProceedings).suppresses(eqCommand(), any(JsonNode.class));
            stages.verify(subscriptionsSource).subscriptionsOn(any(LocalDate.class),
                    any(CallerIdentity.class));
            stages.verify(transformer).transform(eqCommand(), any(JsonNode.class),
                    any(JsonNode.class), any());
            stages.verify(submissionClient).submit(any(CourtRegisterDocument.class),
                    any(CallerIdentity.class), any());
            stages.verify(guard).recordCompletion(claim, CompletionReason.SUBMITTED);

            assertThat(decision).isInstanceOf(GuardDecision.Complete.class);
        }

        @Test
        @DisplayName("hands the transformation the payload the fetch returned, unaltered")
        void hands_the_transformation_the_payload_the_fetch_returned() {
            // The legacy hands the same mutable hearing object to `SetCourtRegister` and then to
            // `OutboundCourtRegister`, and is saved from the consequences only by the Durable
            // Functions serialisation boundary between activities. Java passes references, so the
            // pipeline has to hand on what it fetched and the stages have to derive rather than edit.
            final JsonNode pristine = payload.deepCopy();
            final ArgumentCaptor<JsonNode> handedOn = ArgumentCaptor.forClass(JsonNode.class);

            run();

            verify(transformer).transform(eqCommand(), handedOn.capture(), any(JsonNode.class), any());
            assertThat(handedOn.getValue()).isEqualTo(pristine);
            assertThat(payload)
                    .as("the fetched tree belongs to the producer, not to this service")
                    .isEqualTo(pristine);
        }

        @Test
        @DisplayName("asks the group-proceedings policy about the hearing inside the envelope")
        void asks_the_policy_about_the_hearing_inside_the_envelope() {
            // The claim-check payload is `{hearing, sharedTime}`; the flag is on the hearing
            // (`CourtRegisterOrchestrator/index.js:21`). Handing the envelope instead would read an
            // absent field and never suppress anything.
            final ArgumentCaptor<JsonNode> asked = ArgumentCaptor.forClass(JsonNode.class);

            run();

            verify(groupProceedings).suppresses(eqCommand(), asked.capture());
            assertThat(asked.getValue()).isEqualTo(payload.get("hearing"));
        }

        @Test
        @DisplayName("submits the document the transformation produced, as the request's own user")
        void submits_the_document_the_transformation_produced() {
            final ArgumentCaptor<CourtRegisterDocument> submitted =
                    ArgumentCaptor.forClass(CourtRegisterDocument.class);
            final ArgumentCaptor<CallerIdentity> caller =
                    ArgumentCaptor.forClass(CallerIdentity.class);

            run();

            verify(submissionClient).submit(submitted.capture(), caller.capture(), any());
            assertThat(submitted.getValue()).isEqualTo(document);
            assertThat(caller.getValue()).isEqualTo(CallerIdentity.of(command));
        }

        @Test
        @DisplayName("submits exactly once per document")
        void submits_exactly_once_per_document() {
            // Progression's `add-court-register` appends an event and a row per POST, so a second
            // submission inside one run is a second register for the hearing.
            run();

            verify(submissionClient, times(1)).submit(any(CourtRegisterDocument.class),
                    any(CallerIdentity.class), any());
        }

        @Test
        @DisplayName("records the run as submitted, and counts it under that reason")
        void records_the_run_as_submitted() {
            run();

            verify(guard).recordCompletion(claim, CompletionReason.SUBMITTED);
            assertThat(completions("submitted")).isEqualTo(1);
        }
    }

    /**
     * The reference-data read, which belongs here and not behind the transformation port.
     *
     * <p>These cases were {@code SubscriptionMatcherTest}'s until the transformation was made pure.
     * The matcher reached {@link NowSubscriptionsSource} itself, which put I/O inside the chain the
     * constitution requires to be pure — "no I/O, no clock, no randomness" (Principle V) — and made
     * the stage that decides who a register reaches untestable without a port double. The read moves
     * up here, between two pure stages; the assertions are the same ones, made at the seam that now
     * owns them.
     *
     * <p>What they pin is the whole of the read's contract: the day it is made for is the day the
     * results were shared (defect fix C12, consumed here), the identity it is made as is the request's
     * own, its answer reaches the transformation unaltered, and reference data declining to answer is
     * a retry rather than a register addressed to nobody.
     */
    @Nested
    @DisplayName("the reference-data read")
    class ReferenceDataRead {

        @Test
        @DisplayName("asks for the subscriptions in force on the day the results were shared")
        void asks_for_the_day_the_results_were_shared() {
            // The `on=` day comes from the register date, which is the share instant rather than a
            // London wall clock relabelled `Z` — so an evening share reads the set in force on the
            // day it was shared and not the next day's (C12).
            final ArgumentCaptor<LocalDate> day = ArgumentCaptor.forClass(LocalDate.class);

            run();

            verify(subscriptionsSource).subscriptionsOn(day.capture(), any(CallerIdentity.class));
            assertThat(day.getValue()).isEqualTo(LocalDate.parse("2020-06-01"));
        }

        @Test
        @DisplayName("makes the read as the user who shared the results")
        void makes_the_read_as_the_user_who_shared_the_results() {
            final ArgumentCaptor<CallerIdentity> caller =
                    ArgumentCaptor.forClass(CallerIdentity.class);

            run();

            verify(subscriptionsSource).subscriptionsOn(any(LocalDate.class), caller.capture());
            assertThat(caller.getValue()).isEqualTo(CallerIdentity.of(command));
        }

        @Test
        @DisplayName("hands the transformation the answer reference data gave, unaltered")
        void hands_the_transformation_the_answer_reference_data_gave() {
            final ArgumentCaptor<JsonNode> handedOn = ArgumentCaptor.forClass(JsonNode.class);

            run();

            verify(transformer).transform(eqCommand(), any(JsonNode.class), handedOn.capture(), any());
            assertThat(handedOn.getValue()).isEqualTo(subscriptions);
        }

        @Test
        @DisplayName("reads reference data once per run, whatever the register turns out to be")
        void reads_reference_data_once_per_run() {
            run();

            verify(subscriptionsSource, times(1)).subscriptionsOn(any(LocalDate.class),
                    any(CallerIdentity.class));
        }

        @Test
        @DisplayName("retries a register whose recipients reference data would not name")
        void retries_a_register_whose_recipients_reference_data_would_not_name() {
            // The half of the legacy's single case that has to stop being a completion. A register
            // whose recipients are unknown is retried, not published to nobody and recorded as this
            // flow's commonest legitimate outcome.
            when(subscriptionsSource.subscriptionsOn(any(LocalDate.class),
                    any(CallerIdentity.class)))
                    .thenThrow(new ReferenceDataUnavailableException("subscriptions-read-failed"));

            final GuardDecision decision = run();

            assertThat(decision).isEqualTo(
                    new GuardDecision.Abandon(ReasonCode.REFERENCE_DATA_UNAVAILABLE));
            verify(transformer, never()).transform(any(DistributionCommand.class),
                    any(JsonNode.class), any(JsonNode.class), any());
            verify(guard, never()).recordCompletion(any(RunClaim.class),
                    any(CompletionReason.class));
        }

        @Test
        @DisplayName("asks reference data nothing about a hearing the group-proceedings skip took")
        void asks_reference_data_nothing_about_a_suppressed_hearing() {
            when(payloadSource.fetch(any(DistributionCommand.class)))
                    .thenReturn(payloadFlagged("true"));

            pipelineOverTheRealPolicy().process(command, delivery());

            verify(subscriptionsSource, never()).subscriptionsOn(any(LocalDate.class),
                    any(CallerIdentity.class));
        }
    }

    /**
     * The run's own time budget, spent across every stage rather than checked once.
     *
     * <p>A claim is a lease, and the processing deadline is the promise a runner makes to stop
     * before that lease can be reclaimed. Checking it only after the payload fetch keeps the promise
     * for one stage and breaks it for the three that follow: a reference-data read, a transformation
     * and a POST can each take minutes, and a run that starts its POST after the deadline is a run
     * whose claim another delivery may already hold — and progression's {@code add-court-register}
     * <em>appends</em> a register rather than replacing one, so the second runner's POST is a second
     * register for the hearing, not an overwrite of the first.
     *
     * <p>So the budget is remaining time, read before every send and before every outcome write. A
     * run that has spent it stops, records the overrun as TRANSIENT, and hands the delivery back —
     * the redelivery has a whole fresh budget and nothing has been sent twice. The one write that is
     * never withheld is the completion of a register that <em>was</em> sent: the POST happened, and
     * a run that failed to record it would send it again.
     */
    @Nested
    @DisplayName("the run's time budget")
    class TimeBudget {

        /** Past the five-minute deadline every case in this file is built around. */
        private static final Duration OVER_BUDGET = Duration.ofMinutes(6);

        @Test
        @DisplayName("asks reference data nothing once the payload fetch has spent the budget")
        void asks_reference_data_nothing_once_the_fetch_has_spent_the_budget() {
            final AdjustableClock clock = movingClock();
            when(payloadSource.fetch(any(DistributionCommand.class))).thenAnswer(call -> {
                clock.advance(OVER_BUDGET);
                return payload;
            });

            final GuardDecision decision = pipelineOn(clock).process(command, delivery());

            assertThat(decision).isEqualTo(
                    new GuardDecision.Abandon(ReasonCode.PROCESSING_DEADLINE_EXCEEDED));
            verify(subscriptionsSource, never()).subscriptionsOn(any(LocalDate.class),
                    any(CallerIdentity.class));
        }

        @Test
        @DisplayName("transforms nothing once the reference-data read has spent the budget")
        void transforms_nothing_once_the_reference_data_read_has_spent_the_budget() {
            final AdjustableClock clock = movingClock();
            when(subscriptionsSource.subscriptionsOn(any(LocalDate.class),
                    any(CallerIdentity.class))).thenAnswer(call -> {
                        clock.advance(OVER_BUDGET);
                        return subscriptions;
                    });

            final GuardDecision decision = pipelineOn(clock).process(command, delivery());

            assertThat(decision).isEqualTo(
                    new GuardDecision.Abandon(ReasonCode.PROCESSING_DEADLINE_EXCEEDED));
            verify(transformer, never()).transform(any(DistributionCommand.class),
                    any(JsonNode.class), any(JsonNode.class), any());
        }

        @Test
        @DisplayName("does not start a submission after the safe deadline has passed")
        void does_not_start_a_submission_after_the_deadline() {
            // The send is the stage the budget exists for: progression appends a register per POST,
            // so a run that starts one while a second runner may already hold its claim is how one
            // hearing acquires two registers.
            final AdjustableClock clock = movingClock();
            when(transformer.transform(any(DistributionCommand.class), any(JsonNode.class),
                    any(JsonNode.class), any())).thenAnswer(call -> {
                        clock.advance(OVER_BUDGET);
                        return new TransformationResult.Register(document);
                    });

            final GuardDecision decision = pipelineOn(clock).process(command, delivery());

            assertThat(decision).isEqualTo(
                    new GuardDecision.Abandon(ReasonCode.PROCESSING_DEADLINE_EXCEEDED));
            verify(submissionClient, never()).submit(any(CourtRegisterDocument.class),
                    any(CallerIdentity.class), any());
            verify(guard, never()).recordCompletion(any(RunClaim.class),
                    any(CompletionReason.class));
        }

        @Test
        @DisplayName("does not write a completion after the safe deadline has passed")
        void does_not_write_a_completion_after_the_deadline() {
            final AdjustableClock clock = movingClock();
            when(transformer.transform(any(DistributionCommand.class), any(JsonNode.class),
                    any(JsonNode.class), any())).thenAnswer(call -> {
                        clock.advance(OVER_BUDGET);
                        return new TransformationResult.NoRegister(
                                NoRegisterReason.NO_SUBSCRIPTIONS);
                    });

            final GuardDecision decision = pipelineOn(clock).process(command, delivery());

            assertThat(decision).isEqualTo(
                    new GuardDecision.Abandon(ReasonCode.PROCESSING_DEADLINE_EXCEEDED));
            verify(guard, never()).recordCompletion(any(RunClaim.class),
                    any(CompletionReason.class));
        }

        @Test
        @DisplayName("records the register it did send, even where the send ran past the deadline")
        void records_a_register_whose_send_ran_past_the_deadline() {
            // The one write the budget must never withhold. The POST has happened; a run that
            // declined to record it would be redelivered and would send the register a second time.
            final AdjustableClock clock = movingClock();
            when(submissionClient.submit(any(CourtRegisterDocument.class),
                    any(CallerIdentity.class), any())).thenAnswer(call -> {
                        clock.advance(OVER_BUDGET);
                        return new SubmissionReceipt(202);
                    });

            final GuardDecision decision = pipelineOn(clock).process(command, delivery());

            assertThat(decision).isInstanceOf(GuardDecision.Complete.class);
            verify(guard).recordCompletion(claim, CompletionReason.SUBMITTED);
        }

        @Test
        @DisplayName("parks an overrun on the last permitted delivery rather than losing it")
        void parks_an_overrun_on_the_last_permitted_delivery() {
            final AdjustableClock clock = movingClock();
            when(transformer.transform(any(DistributionCommand.class), any(JsonNode.class),
                    any(JsonNode.class), any())).thenAnswer(call -> {
                        clock.advance(OVER_BUDGET);
                        return new TransformationResult.Register(document);
                    });

            final GuardDecision decision = pipelineOn(clock).process(command, lastDelivery());

            assertThat(decision).isEqualTo(new GuardDecision.DeadLetter(
                    DeadLetterReason.EXHAUSTED, ReasonCode.PROCESSING_DEADLINE_EXCEEDED));
        }
    }

    /**
     * Defect C33. Four separate legacy guards, one undifferentiated success. Two of these four are
     * the commonest results this service has, so telling them apart is what makes a pipeline that
     * has quietly stopped working distinguishable from a quiet week.
     */
    @Nested
    @DisplayName("the four no-op outcomes (C33)")
    class NoOpOutcomes {

        @ParameterizedTest
        @EnumSource(NoRegisterReason.class)
        @DisplayName("no op outcomes are distinguishable")
        void no_op_outcomes_are_distinguishable(final NoRegisterReason reason) {
            when(transformer.transform(any(DistributionCommand.class), any(JsonNode.class),
                    any(JsonNode.class), any()))
                    .thenReturn(new TransformationResult.NoRegister(reason));

            final GuardDecision decision = run();

            assertThat(decision).isInstanceOf(GuardDecision.Complete.class);
            verify(guard).recordCompletion(claim, reason.completion());
            assertThat(completions(reason.completion().value())).isEqualTo(1);
            assertThat(completions("submitted")).isEqualTo(ABSENT);
        }

        @ParameterizedTest
        @EnumSource(NoRegisterReason.class)
        @DisplayName("sends nothing at all when there is nothing to send")
        void sends_nothing_when_there_is_nothing_to_send(final NoRegisterReason reason) {
            when(transformer.transform(any(DistributionCommand.class), any(JsonNode.class),
                    any(JsonNode.class), any()))
                    .thenReturn(new TransformationResult.NoRegister(reason));

            run();

            verify(submissionClient, never()).submit(any(CourtRegisterDocument.class),
                    any(CallerIdentity.class), any());
        }

        @Test
        @DisplayName("records a hearing that gathered nobody as no-defendants (C6)")
        void records_a_hearing_that_gathered_nobody_as_no_defendants() {
            when(transformer.transform(any(DistributionCommand.class), any(JsonNode.class),
                    any(JsonNode.class), any()))
                    .thenReturn(new TransformationResult.NoRegister(
                            NoRegisterReason.NO_DEFENDANTS));

            run();

            verify(guard).recordCompletion(claim, CompletionReason.NO_DEFENDANTS);
        }
    }

    /**
     * What a run skipped on its way to an answer — the counting half of fixes C19, C20 and C27.
     *
     * <p>Each of those three fixes keeps a register the legacy loses, and each of them says, in its
     * own row, that the skip is counted rather than silent. The mappers have counted through an
     * injected consumer since they were written; nothing in the running service was holding one, so
     * in a deployed pod the count went nowhere and the fixes were as quiet as the defects.
     *
     * <p>What a run does with what it counted depends on how it ended, and the difference is not a
     * detail: a submitted register has a {@code processed_output} row to carry the counts into, and
     * a run that produced no register has no row at all — there is nothing to write against. Both
     * move the metric, and the run that has nowhere to persist them says so once, in bounded codes.
     */
    @Nested
    @DisplayName("what a run skipped (C19, C20, C27)")
    class WhatARunSkipped {

        @Test
        @DisplayName("the counts a sent register survived travel with it, to be written before it "
                + "is sent")
        void the_counts_travel_with_the_register() {
            transformCounting(new TransformationResult.Register(document),
                    TransformationAnomaly.LETTER_DELIVERY_DROPPED,
                    TransformationAnomaly.LETTER_DELIVERY_DROPPED,
                    TransformationAnomaly.UNRESOLVABLE_YOUTH_DEFENDANT);

            run();

            final ArgumentCaptor<Map<TransformationAnomaly, Integer>> counted =
                    ArgumentCaptor.captor();
            verify(submissionClient).submit(
                    org.mockito.ArgumentMatchers.eq(document), any(CallerIdentity.class),
                    counted.capture());
            assertThat(counted.getValue()).containsExactlyInAnyOrderEntriesOf(Map.of(
                    TransformationAnomaly.LETTER_DELIVERY_DROPPED, 2,
                    TransformationAnomaly.UNRESOLVABLE_YOUTH_DEFENDANT, 1));
        }

        @Test
        @DisplayName("and are counted on the anomaly metric, once per occurrence")
        void the_counts_reach_the_metric() {
            transformCounting(new TransformationResult.Register(document),
                    TransformationAnomaly.LETTER_DELIVERY_DROPPED,
                    TransformationAnomaly.LETTER_DELIVERY_DROPPED,
                    TransformationAnomaly.UNRESOLVABLE_YOUTH_DEFENDANT);

            run();

            assertThat(anomalies("letter-delivery-dropped")).isEqualTo(2);
            assertThat(anomalies("unresolvable-youth-defendant")).isEqualTo(1);
        }

        @Test
        @DisplayName("a run that produced no register counts them and says so once, in codes")
        void a_declining_run_counts_them_and_says_so_once() {
            transformCounting(new TransformationResult.NoRegister(
                            NoRegisterReason.NO_SUBSCRIPTIONS),
                    TransformationAnomaly.LETTER_DELIVERY_DROPPED,
                    TransformationAnomaly.LETTER_DELIVERY_DROPPED);

            try (CapturedLog log = CapturedLog.capturing(DistributionPipeline.class)) {
                run();

                assertThat(anomalies("letter-delivery-dropped")).isEqualTo(2);
                assertThat(warnings(log)).singleElement().satisfies(warning -> assertThat(warning)
                        .contains("letter-delivery-dropped:2")
                        .contains(command.hearingId().toString())
                        .doesNotContain("recipient"));
            }
        }

        @Test
        @DisplayName("and nothing is submitted for it, so nothing is written against a row that "
                + "does not exist")
        void a_declining_run_submits_nothing() {
            transformCounting(new TransformationResult.NoRegister(
                            NoRegisterReason.NO_SUBSCRIPTIONS),
                    TransformationAnomaly.LETTER_DELIVERY_DROPPED);

            run();

            verify(submissionClient, never()).submit(any(CourtRegisterDocument.class),
                    any(CallerIdentity.class), any());
        }

        @Test
        @DisplayName("a run that skipped nothing carries nothing and warns about nothing")
        void a_run_that_skipped_nothing_says_nothing() {
            try (CapturedLog log = CapturedLog.capturing(DistributionPipeline.class)) {
                run();

                verify(submissionClient).submit(
                        org.mockito.ArgumentMatchers.eq(document), any(CallerIdentity.class),
                        org.mockito.ArgumentMatchers.eq(Map.of()));
                assertThat(warnings(log)).isEmpty();
                assertThat(anomalies("letter-delivery-dropped")).isEqualTo(ABSENT);
            }
        }

        @Test
        @DisplayName("each run counts its own, and never the run before it")
        void each_run_counts_its_own() {
            transformCounting(new TransformationResult.Register(document),
                    TransformationAnomaly.LETTER_DELIVERY_DROPPED);

            run();
            run();

            final ArgumentCaptor<Map<TransformationAnomaly, Integer>> counted =
                    ArgumentCaptor.captor();
            verify(submissionClient, times(2)).submit(
                    org.mockito.ArgumentMatchers.eq(document), any(CallerIdentity.class),
                    counted.capture());
            assertThat(counted.getAllValues()).allSatisfy(counts -> assertThat(counts)
                    .containsExactlyInAnyOrderEntriesOf(
                            Map.of(TransformationAnomaly.LETTER_DELIVERY_DROPPED, 1)));
        }
    }

    /**
     * Defect C7's second half. The suppression is a business rule and ports unchanged; that it is
     * recorded does not — the legacy skips the register and reports {@code Success: true} with
     * nothing to say which of the five things happened.
     *
     * <p>These two cases run over the <em>real</em> policy, because what they assert is the wiring:
     * that the pipeline consults it, that a suppression becomes a named completion, and that a
     * non-boolean flag does not suppress anything on the way through.
     */
    @Nested
    @DisplayName("group proceedings (C7)")
    class GroupProceedings {

        @Test
        @DisplayName("group proceedings skip is recorded")
        void group_proceedings_skip_is_recorded() {
            when(payloadSource.fetch(any(DistributionCommand.class)))
                    .thenReturn(payloadFlagged("true"));

            final GuardDecision decision = pipelineOverTheRealPolicy().process(command, delivery());

            assertThat(decision).isInstanceOf(GuardDecision.Complete.class);
            verify(guard).recordCompletion(claim, CompletionReason.GROUP_PROCEEDINGS);
            assertThat(completions("group-proceedings")).isEqualTo(1);
            verify(transformer, never()).transform(any(DistributionCommand.class),
                    any(JsonNode.class), any(JsonNode.class), any());
            verify(submissionClient, never()).submit(any(CourtRegisterDocument.class),
                    any(CallerIdentity.class), any());
        }

        @Test
        @DisplayName("only boolean true suppresses the register")
        void only_boolean_true_suppresses_the_register() {
            // The legacy's `isGroupProceedings == null || == false` suppresses the register for the
            // string "true" and for every other truthy value; the run then reports success with
            // nothing produced and nothing recorded. Here the register is built and submitted.
            when(payloadSource.fetch(any(DistributionCommand.class)))
                    .thenReturn(payloadFlagged("\"true\""));

            pipelineOverTheRealPolicy().process(command, delivery());

            verify(guard).recordCompletion(claim, CompletionReason.SUBMITTED);
            verify(submissionClient).submit(
                    org.mockito.ArgumentMatchers.eq(document),
                    org.mockito.ArgumentMatchers.eq(CallerIdentity.of(command)), any());
        }
    }

    /**
     * Defect C2, and the failure taxonomy the legacy has none of. Every path out of a run ends in a
     * recorded terminal state, and which one it is depends on whether another delivery could change
     * the answer.
     */
    @Nested
    @DisplayName("failures (C2, C32)")
    class Failures {

        @Test
        @DisplayName("every failure ends in a recorded terminal state")
        void every_failure_ends_in_a_recorded_terminal_state() {
            // The claim of the whole file, asserted across every way a run can fail at once. The
            // legacy's catch-all reports failure from a *completed* orchestration and records
            // nothing, and reads `context.df.getInput()` inside the catch, which can throw again and
            // lose even the log line.
            final ArgumentCaptor<ReasonCode> recorded = ArgumentCaptor.forClass(ReasonCode.class);

            for (final RuntimeException failure : everyWayARunCanFail()) {
                when(transformer.transform(any(DistributionCommand.class), any(JsonNode.class),
                    any(JsonNode.class), any()))
                        .thenThrow(failure);

                final GuardDecision decision = run();

                assertThat(decision)
                        .as("a %s left the delivery unsettled", failure.getClass().getSimpleName())
                        .isNotInstanceOf(GuardDecision.Run.class)
                        .isNotNull();
            }

            verify(guard, never()).recordCompletion(any(RunClaim.class),
                    any(CompletionReason.class));
            verify(guard, times(everyWayARunCanFail().length)).recordTransientFailure(
                    any(RunClaim.class), recorded.capture());
            assertThat(recorded.getAllValues())
                    .as("each failure is recorded under its own bounded reason, not a catch-all")
                    .containsExactly(
                            ReasonCode.REFERENCE_DATA_UNAVAILABLE, ReasonCode.UNEXPECTED_FAILURE);
        }

        @Test
        @DisplayName("retries a payload the cache and the fallback could not supply (C32)")
        void retries_a_payload_the_cache_and_fallback_could_not_supply() {
            // `getPrefixHearing` returns `undefined` on an empty query-API body and `null` on an
            // error, and `if (hearingResultedObj)` is false for both — so the run stops there,
            // records nothing and reports success. A register that was never buildable stops
            // masquerading as a delivered one.
            when(payloadSource.fetch(any(DistributionCommand.class)))
                    .thenThrow(new PayloadUnavailableException(ReasonCode.PAYLOAD_UNAVAILABLE));

            final GuardDecision decision = run();

            assertThat(decision).isEqualTo(
                    new GuardDecision.Abandon(ReasonCode.PAYLOAD_UNAVAILABLE));
            verify(guard).recordTransientFailure(claim, ReasonCode.PAYLOAD_UNAVAILABLE);
            verify(guard, never()).recordCompletion(any(RunClaim.class),
                    any(CompletionReason.class));
        }

        @Test
        @DisplayName("does not transform a hearing it could not read")
        void does_not_transform_a_hearing_it_could_not_read() {
            when(payloadSource.fetch(any(DistributionCommand.class)))
                    .thenThrow(new PayloadUnavailableException(ReasonCode.PAYLOAD_UNAVAILABLE));

            run();

            verify(transformer, never()).transform(any(DistributionCommand.class),
                    any(JsonNode.class), any(JsonNode.class), any());
        }

        @Test
        @DisplayName("parks a transformation that cannot produce a register, never completes it")
        void parks_a_transformation_that_cannot_produce_a_register() {
            // The BS-02 chain, deliberately constructed: `RegisterFragmentService`'s catch throws a
            // second time, `SetCourtRegister` swallows it, the orchestrator's `:33` guard skips the
            // rest, and the orchestration returns `Success: true`. Here it is a dead-letter with a
            // bounded reason.
            when(transformer.transform(any(DistributionCommand.class), any(JsonNode.class),
                    any(JsonNode.class), any()))
                    .thenThrow(new TransformationFailedException("ordered-date-unreadable"));

            final GuardDecision decision = run();

            assertThat(decision).isEqualTo(new GuardDecision.DeadLetter(
                    DeadLetterReason.NON_TRANSIENT, ReasonCode.TRANSFORMATION_FAILED));
            verify(guard, never()).recordCompletion(any(RunClaim.class),
                    any(CompletionReason.class));
        }

        @Test
        @DisplayName("retries a submission that may succeed next time")
        void retries_a_submission_that_may_succeed_next_time() {
            when(submissionClient.submit(any(CourtRegisterDocument.class),
                    any(CallerIdentity.class), any()))
                    .thenThrow(new SubmissionFailedException(
                            FailureClassification.TRANSIENT, ReasonCode.SUBMISSION_TRANSIENT));

            assertThat(run()).isEqualTo(
                    new GuardDecision.Abandon(ReasonCode.SUBMISSION_TRANSIENT));
        }

        @Test
        @DisplayName("parks a submission progression refused, without spending the deliveries")
        void parks_a_submission_progression_refused() {
            when(submissionClient.submit(any(CourtRegisterDocument.class),
                    any(CallerIdentity.class), any()))
                    .thenThrow(new SubmissionFailedException(
                            FailureClassification.NON_TRANSIENT, ReasonCode.SUBMISSION_REJECTED));

            assertThat(run()).isEqualTo(new GuardDecision.DeadLetter(
                    DeadLetterReason.NON_TRANSIENT, ReasonCode.SUBMISSION_REJECTED));
        }

        @Test
        @DisplayName("parks a transient submission failure on the last permitted delivery")
        void parks_a_transient_submission_failure_on_the_last_delivery() {
            when(submissionClient.submit(any(CourtRegisterDocument.class),
                    any(CallerIdentity.class), any()))
                    .thenThrow(new SubmissionFailedException(
                            FailureClassification.TRANSIENT, ReasonCode.SUBMISSION_TRANSIENT));

            final GuardDecision decision = pipeline().process(command, lastDelivery());

            assertThat(decision).isEqualTo(new GuardDecision.DeadLetter(
                    DeadLetterReason.EXHAUSTED, ReasonCode.SUBMISSION_TRANSIENT));
        }

        @Test
        @DisplayName("never records a completion for a run that failed after the register was built")
        void never_records_a_completion_after_a_failed_submission() {
            // The exact shape of C1: the POST failed and the legacy reports the run as a success,
            // so a lost register and a delivered one are the same row.
            when(submissionClient.submit(any(CourtRegisterDocument.class),
                    any(CallerIdentity.class), any()))
                    .thenThrow(new SubmissionFailedException(
                            FailureClassification.NON_TRANSIENT, ReasonCode.SUBMISSION_REJECTED));

            final GuardDecision decision = run();

            assertThat(decision).isEqualTo(new GuardDecision.DeadLetter(
                    DeadLetterReason.NON_TRANSIENT, ReasonCode.SUBMISSION_REJECTED));
            verify(guard, never()).recordCompletion(any(RunClaim.class),
                    any(CompletionReason.class));
            assertThat(completions("submitted")).isEqualTo(ABSENT);
        }
    }

    /**
     * Every way a run can fail that the pipeline must turn into a recorded outcome. Listed rather
     * than parameterised so the one assertion can be made across all of them at once.
     *
     * @return one failure of each kind
     */
    private RuntimeException[] everyWayARunCanFail() {
        return new RuntimeException[] {
            new ReferenceDataUnavailableException("subscriptions-read-failed"),
            new IllegalStateException("a failure nothing anticipated"),
        };
    }

    /**
     * The pipeline over its four ports and a policy that suppresses nothing.
     *
     * @return the pipeline
     */
    private DistributionPipeline pipeline() {
        return new DistributionPipeline(guard, payloadSource, groupProceedings, subscriptionsSource,
                new Dates(), transformer, submissionClient, metrics, fixedClock(), RUN_DEADLINE);
    }

    /**
     * The same pipeline over the real group-proceedings policy, for the two cases whose claim is
     * about the wiring rather than about the pipeline's own branching.
     *
     * @return the pipeline
     */
    private DistributionPipeline pipelineOverTheRealPolicy() {
        return new DistributionPipeline(guard, payloadSource, new GroupProceedingsPolicy(metrics),
                subscriptionsSource, new Dates(), transformer, submissionClient, metrics,
                fixedClock(), RUN_DEADLINE);
    }

    /**
     * Runs one ordinary delivery.
     *
     * @return what the pipeline decided the delivery is worth settling as
     */
    private GuardDecision run() {
        return pipeline().process(command, delivery());
    }

    /**
     * A delivery with retries still to come.
     *
     * @return the delivery
     */
    private DeliveryIdentity delivery() {
        return new DeliveryIdentity("msg-1", "runner-1", false);
    }

    /**
     * The last delivery the queue permits.
     *
     * @return the delivery
     */
    private DeliveryIdentity lastDelivery() {
        return new DeliveryIdentity("msg-1", "runner-1", true);
    }

    /**
     * A claim-check payload whose hearing carries the given raw group-proceedings value.
     *
     * @param flag the raw JSON value
     * @return the payload
     */
    private JsonNode payloadFlagged(final String flag) {
        return mapper.readTree(("{\"hearing\":{\"id\":\"1828f356-f746-4f2d-932b-79ef2df95c80\","
                + "\"isGroupProceedings\":%s},\"sharedTime\":\"2020-06-01T10:00:00Z\"}")
                .formatted(flag));
    }

    /**
     * A clock that does not move, so no case in this file reaches its processing deadline by
     * accident.
     *
     * @return the clock
     */
    private Clock fixedClock() {
        return Clock.fixed(Instant.parse("2020-06-01T10:00:05Z"), ZoneOffset.UTC);
    }

    /**
     * A clock a stage moves by hand, so a run can be made to overrun exactly where a case wants it
     * to. Real waiting would make the boundary untestable and the suite slow.
     *
     * @return the clock
     */
    private AdjustableClock movingClock() {
        return AdjustableClock.startingAt(Instant.parse("2020-06-01T10:00:05Z"));
    }

    /**
     * The pipeline over its ports and a clock a case can move.
     *
     * @param clock the clock
     * @return the pipeline
     */
    private DistributionPipeline pipelineOn(final Clock clock) {
        return new DistributionPipeline(guard, payloadSource, groupProceedings, subscriptionsSource,
                new Dates(), transformer, submissionClient, metrics, clock, RUN_DEADLINE);
    }

    /**
     * The command matcher, spelled once so the stage-order assertions read as prose.
     *
     * @return a matcher for the command under test
     */
    private DistributionCommand eqCommand() {
        return org.mockito.ArgumentMatchers.eq(command);
    }

    /**
     * A transformation that counts the given anomalies on the sink it is handed, then answers.
     *
     * <p>Which is what the real chain does: the mappers beneath it count as they walk the hearing,
     * and the answer comes back afterwards. A stub that only answered could not tell whether the
     * pipeline gave the stages anywhere to count.
     *
     * @param result  what the transformation answers
     * @param counted what it counts on the way there
     */
    private void transformCounting(
            final TransformationResult result, final TransformationAnomaly... counted) {

        when(transformer.transform(any(DistributionCommand.class), any(JsonNode.class),
                any(JsonNode.class), any()))
                .thenAnswer(call -> {
                    final Consumer<TransformationAnomaly> sink = call.getArgument(3);
                    for (final TransformationAnomaly anomaly : counted) {
                        sink.accept(anomaly);
                    }
                    return result;
                });
    }

    /**
     * Every warning the pipeline wrote.
     *
     * @param log the captured log
     * @return the formatted warnings
     */
    private static List<String> warnings(final CapturedLog log) {
        return log.events().stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    /**
     * How many anomalies have been counted under a reason.
     *
     * @param reason the bounded reason code
     * @return the count, or {@link #ABSENT} where the series does not exist
     */
    private double anomalies(final String reason) {
        final Counter counter = registry.find(ProcessingMetrics.TRANSFORMATION_ANOMALIES)
                .tag(ProcessingMetrics.REASON_TAG, reason)
                .counter();
        return counter == null ? ABSENT : counter.count();
    }

    /**
     * How many completions have been counted under a reason.
     *
     * @param reason the bounded reason code
     * @return the count, or {@link #ABSENT} where the series does not exist
     */
    private double completions(final String reason) {
        final Counter counter = registry.find(ProcessingMetrics.COMPLETIONS)
                .tag(ProcessingMetrics.REASON_TAG, reason)
                .counter();
        return counter == null ? ABSENT : counter.count();
    }
}
