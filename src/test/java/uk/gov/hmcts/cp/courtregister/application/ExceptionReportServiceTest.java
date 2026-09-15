package uk.gov.hmcts.cp.courtregister.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.lang.reflect.RecordComponent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.hmcts.cp.courtregister.config.ProcessingMetrics;
import uk.gov.hmcts.cp.courtregister.domain.BatchException;
import uk.gov.hmcts.cp.courtregister.domain.BatchFailureReason;
import uk.gov.hmcts.cp.courtregister.domain.BatchStatus;
import uk.gov.hmcts.cp.courtregister.domain.ExceptionEntry;
import uk.gov.hmcts.cp.courtregister.domain.ExceptionKind;
import uk.gov.hmcts.cp.courtregister.domain.ExceptionReport;
import uk.gov.hmcts.cp.courtregister.domain.FailedNotification;
import uk.gov.hmcts.cp.courtregister.domain.LastScheduledRun;
import uk.gov.hmcts.cp.courtregister.domain.NotificationStatus;
import uk.gov.hmcts.cp.courtregister.domain.ProcessedRequestSummary;
import uk.gov.hmcts.cp.courtregister.domain.RecordedRegisterSummary;
import uk.gov.hmcts.cp.courtregister.domain.ReportWindow;
import uk.gov.hmcts.cp.courtregister.domain.RequestStatus;
import uk.gov.hmcts.cp.courtregister.persistence.ProcessedRequestRepository;
import uk.gov.hmcts.cp.courtregister.persistence.RegisterBatchRepository;
import uk.gov.hmcts.cp.courtregister.persistence.RegisterNotificationRepository;

/**
 * What the report is, asked of the service that composes it.
 *
 * <p>Eight reads go out and one report comes back, and almost everything worth getting wrong here
 * is about which rows belong in it and which moment each read is asked about. The three failed
 * kinds are bounded by the window, so a report is a statement about a period; the two late kinds
 * deliberately are not, because a request stuck since Friday is late on Monday morning whether or
 * not it arrived over the weekend, and a window filter would make the longest-running problem the
 * first one to vanish.
 *
 * <p><strong>The service computes no age.</strong> Each of the four projections carries the age its
 * own statement measured, in the database, from the column that stage is timed off; a service that
 * subtracted a stored timestamp from a JVM reading would be comparing two clocks, which V1's
 * single-time-authority rule forbids. So the cases below seed ages that no arithmetic over the
 * seeded timestamps would produce, and assert that those are the ages reported.
 *
 * <p>The repositories are plain mocks and the store is its port: this is the application layer, and
 * what the statements themselves return is the four {@code *ReportReadsIT} suites' claim. What is
 * asserted here is which read is made, what moment it is asked about, and what the answers fold
 * into.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("the exception report, built from the store")
class ExceptionReportServiceTest {

    /** A Tuesday morning, just after the 07:00 run would have fired. */
    private static final Instant NOW = Instant.parse("2026-09-15T06:00:00Z");

    /** The window that run covers: Monday's run to this one. */
    private static final Instant WINDOW_FROM = Instant.parse("2026-09-14T06:00:00Z");

    private static final ReportWindow WINDOW = new ReportWindow(WINDOW_FROM, NOW);

    private static final String RUN_ID = "run-4b19c7e0";

    private static final Duration REQUEST_TERMINAL_WITHIN = Duration.ofMinutes(30);

    private static final Duration BATCH_GENERATED_WITHIN = Duration.ofMinutes(10);

    private static final Duration NOTIFIED_WITHIN = Duration.ofMinutes(15);

    /** The generation half's schedule, which decides what the night left behind. */
    private static final String GENERATION_CRON = "0 0 18 * * MON-FRI";

    private static final String COURTS_ZONE = "Europe/London";

    private static final String SOURCE = "cpp-context-results";

    private static final UUID REQUEST_ID =
            UUID.fromString("7a1c4d90-3e62-4b58-9f07-2d4a8c1e6503");

    private static final UUID HEARING_ID =
            UUID.fromString("2e8b5f41-9c03-4d76-8a15-6b0e3f7c2d94");

    private static final UUID BATCH_ID =
            UUID.fromString("11111111-2222-4333-8444-555555555555");

    private static final UUID NOTIFICATION_ID =
            UUID.fromString("66666666-7777-4888-8999-aaaaaaaaaaaa");

    private static final UUID COURT_CENTRE =
            UUID.fromString("2f6b8d10-4a3c-4e57-9b21-8c0d5e7f1a94");

    private static final UUID OUTPUT_ID =
            UUID.fromString("0a1b2c3d-4e5f-4a6b-8c9d-1e2f3a4b5c6d");

    private static final LocalDate HEARING_DAY = LocalDate.of(2026, 9, 14);

    private static final LocalDate REGISTER_DATE = LocalDate.of(2026, 9, 14);

    /** Ages seeded as the statements answered them, deliberately unrelated to the timestamps. */
    private static final long REQUEST_FAILED_AGE = 4_000L;

    /**
     * Three days, which is the point of the case that reads it.
     *
     * <p>The late kinds are bounded by their threshold and not by the window, and the way that is
     * asserted is that the seeded row is older than the window is wide - so a row a window-bounded
     * read would have dropped is the one the report still carries. At 9 000 seconds it was inside
     * a twenty-four hour window and the assertion could not hold whatever the service did.
     */
    private static final long REQUEST_LATE_AGE = 259_200L;

    private static final long PENDING_AGE = 2_000L;

    private static final long GENERATING_AGE = 6_000L;

    private static final long GENERATED_AGE = 1_000L;

    private static final long BATCH_FAILED_AGE = 3_000L;

    private static final long NOTIFICATION_AGE = 500L;

    private static final long UNBATCHED_AGE = 12_000L;

    private static final int ATTEMPTS = 3;

    private static final int RESPONSE_CODE = 502;

    private final ProcessedRequestRepository requests = mock(ProcessedRequestRepository.class);

    private final RegisterBatchRepository batches = mock(RegisterBatchRepository.class);

    private final RegisterNotificationRepository notifications =
            mock(RegisterNotificationRepository.class);

    private final RegisterStore registers = mock(RegisterStore.class);

    private final ProcessingMetrics metrics = new ProcessingMetrics(new SimpleMeterRegistry());

    private ExceptionReportService service;

    /**
     * Nothing is wrong until a case seeds something.
     *
     * <p>No read is stubbed here on purpose: a mock answers an empty list for a list-returning
     * read already, and a blanket stub a case then narrowed would be an unused stubbing under
     * strict stubs - which is a suite failing for a reason that has nothing to do with the report.
     */
    @BeforeEach
    void nothingIsWrongUntilASeedSaysSo() {
        service = new ExceptionReportService(requests, batches, notifications, registers,
                REQUEST_TERMINAL_WITHIN, BATCH_GENERATED_WITHIN, NOTIFIED_WITHIN,
                GENERATION_CRON, COURTS_ZONE, metrics,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /**
     * The intake half's two kinds, and the partition that keeps a request out of both.
     */
    @Nested
    @DisplayName("what the processed log contributes")
    class TheIntakeHalf {

        @Test
        void a_failed_request_inside_the_window_is_one_request_failed_entry() {
            when(requests.failedSince(WINDOW_FROM)).thenReturn(List.of(parked()));

            final ExceptionReport report = service.build(WINDOW, RUN_ID);

            assertThat(report.entries())
                    .as("a request the pipeline parked is the intake half's exception, and the "
                            + "report exists to name it on the morning after")
                    .singleElement()
                    .satisfies(entry -> {
                        assertThat(entry.kind()).isEqualTo(ExceptionKind.REQUEST_FAILED);
                        assertThat(entry.source()).isEqualTo(SOURCE);
                        assertThat(entry.requestId()).isEqualTo(REQUEST_ID);
                        assertThat(entry.hearingId()).isEqualTo(HEARING_ID);
                        assertThat(entry.hearingDay()).isEqualTo(HEARING_DAY);
                        assertThat(entry.status()).isEqualTo(RequestStatus.FAILED.name());
                        assertThat(entry.attempts()).isEqualTo(ATTEMPTS);
                        assertThat(entry.reason()).isEqualTo("schema-violation");
                        assertThat(entry.ageSeconds()).isEqualTo(REQUEST_FAILED_AGE);
                        assertThat(entry.batchId()).isNull();
                        assertThat(entry.notificationId()).isNull();
                        assertThat(entry.courtCentreId()).isNull();
                        assertThat(entry.registerDate()).isNull();
                    });
        }

        @Test
        void an_unfinished_request_past_the_threshold_is_one_request_late_entry_whether_or_not_it_arrived_inside_the_window() {
            when(requests.nonTerminalOlderThan(any())).thenReturn(List.of(stuckSinceFriday()));

            final ExceptionReport report = service.build(WINDOW, RUN_ID);
            final ArgumentCaptor<Instant> askedAbout = ArgumentCaptor.forClass(Instant.class);
            verify(requests).nonTerminalOlderThan(askedAbout.capture());

            assertThat(askedAbout.getValue())
                    .as("the late kinds are bounded by the threshold and not by the window: a "
                            + "request stuck for three days is late this morning, and a window "
                            + "filter would make the longest-running problem the first to vanish")
                    .isEqualTo(NOW.minus(REQUEST_TERMINAL_WITHIN));
            assertThat(report.entries())
                    .singleElement()
                    .satisfies(entry -> {
                        assertThat(entry.kind()).isEqualTo(ExceptionKind.REQUEST_LATE);
                        assertThat(entry.status()).isEqualTo(RequestStatus.RETRYING.name());
                        assertThat(entry.ageSeconds()).isEqualTo(REQUEST_LATE_AGE);
                        assertThat(entry.ageSeconds())
                                .as("and it is older than the window is wide, which is exactly the "
                                        + "row a window-bounded read would have dropped")
                                .isGreaterThan(Duration.between(WINDOW_FROM, NOW).toSeconds());
                    });
        }

        @Test
        void a_request_is_reported_under_at_most_one_kind_per_run() {
            when(requests.failedSince(WINDOW_FROM)).thenReturn(List.of(parked()));

            final ExceptionReport report = service.build(WINDOW, RUN_ID);

            assertThat(report.entries())
                    .as("the two intake predicates partition on status - FAILED is terminal and "
                            + "RECEIVED and RETRYING are not - so a request that was late and has "
                            + "since failed is reported once, as the failure (FR-013)")
                    .extracting(ExceptionEntry::requestId)
                    .containsExactly(REQUEST_ID);
        }

        @Test
        void the_same_still_late_request_appears_in_two_consecutive_windows() {
            when(requests.nonTerminalOlderThan(any())).thenReturn(List.of(stuckSinceFriday()));

            final ExceptionReport monday = service.build(WINDOW, RUN_ID);
            final ExceptionReport tuesday = service.build(
                    new ReportWindow(NOW, NOW.plus(Duration.ofDays(1))), RUN_ID);

            assertThat(monday.entries())
                    .as("a report is a snapshot of a moment, not a ledger of new arrivals, so a "
                            + "problem that is still there is still reported")
                    .isEqualTo(tuesday.entries());
        }
    }

    /**
     * The three sources of one late batch, and the dead batches beside them.
     */
    @Nested
    @DisplayName("what the batches contribute")
    class TheDownstreamHalf {

        @Test
        void the_three_batch_sources_are_one_batch_late_kind() {
            when(batches.latePending(any())).thenReturn(List.of(late(BatchStatus.PENDING,
                    PENDING_AGE)));
            when(batches.lateGenerating(any())).thenReturn(List.of(late(BatchStatus.GENERATING,
                    GENERATING_AGE)));
            when(batches.lateGenerated(any())).thenReturn(List.of(late(BatchStatus.GENERATED,
                    GENERATED_AGE)));

            final ExceptionReport report = service.build(WINDOW, RUN_ID);

            assertThat(report.entries())
                    .as("three stages of one journey are three ways of being late, and one kind: "
                            + "what an operator needs is the batch and the stage it stopped at")
                    .extracting(ExceptionEntry::kind)
                    .containsOnly(ExceptionKind.BATCH_LATE)
                    .hasSize(3);
            assertThat(report.entries())
                    .extracting(ExceptionEntry::status)
                    .containsExactlyInAnyOrder(BatchStatus.PENDING.name(),
                            BatchStatus.GENERATING.name(), BatchStatus.GENERATED.name());
            assertThat(report.entries())
                    .as("the stage that is overdue is a bounded code, and the three are distinct")
                    .extracting(ExceptionEntry::reason)
                    .doesNotHaveDuplicates()
                    .doesNotContainNull();
        }

        @Test
        void each_late_batch_stage_is_asked_about_its_own_limit() {
            service.build(WINDOW, RUN_ID);

            final ArgumentCaptor<Instant> pending = ArgumentCaptor.forClass(Instant.class);
            final ArgumentCaptor<Instant> generating = ArgumentCaptor.forClass(Instant.class);
            final ArgumentCaptor<Instant> generated = ArgumentCaptor.forClass(Instant.class);
            verify(batches).latePending(pending.capture());
            verify(batches).lateGenerating(generating.capture());
            verify(batches).lateGenerated(generated.capture());

            assertThat(pending.getValue())
                    .as("a batch awaiting its render is held to the rendering limit")
                    .isEqualTo(NOW.minus(BATCH_GENERATED_WITHIN));
            assertThat(generating.getValue())
                    .as("and so is a batch whose render was asked for")
                    .isEqualTo(NOW.minus(BATCH_GENERATED_WITHIN));
            assertThat(generated.getValue())
                    .as("a rendered batch is held to the notification limit instead, which is a "
                            + "different question about a different stage")
                    .isEqualTo(NOW.minus(NOTIFIED_WITHIN));
        }

        @Test
        void a_batch_failed_inside_the_window_is_one_batch_failed_entry_carrying_its_bounded_reason() {
            when(batches.failedSince(WINDOW_FROM)).thenReturn(List.of(dead()));

            final ExceptionReport report = service.build(WINDOW, RUN_ID);

            assertThat(report.entries())
                    .as("a batch that reached its own terminal failure is the downstream half's "
                            + "parked request; a report that named the late ones and not the dead "
                            + "ones would report the symptom and hide the outcome")
                    .singleElement()
                    .satisfies(entry -> {
                        assertThat(entry.kind()).isEqualTo(ExceptionKind.BATCH_FAILED);
                        assertThat(entry.batchId()).isEqualTo(BATCH_ID);
                        assertThat(entry.courtCentreId()).isEqualTo(COURT_CENTRE);
                        assertThat(entry.registerDate()).isEqualTo(REGISTER_DATE);
                        assertThat(entry.status()).isEqualTo(BatchStatus.FAILED.name());
                        assertThat(entry.reason())
                                .isEqualTo(BatchFailureReason.GENERATION_FAILED.name());
                        assertThat(entry.ageSeconds()).isEqualTo(BATCH_FAILED_AGE);
                        assertThat(entry.attempts())
                                .as("a batch's lifetime tally is not what is wrong with it, and a "
                                        + "field that does not apply to a kind is absent")
                                .isNull();
                        assertThat(entry.source()).isNull();
                        assertThat(entry.requestId()).isNull();
                        assertThat(entry.notificationId()).isNull();
                    });
        }

        @Test
        void a_batch_failed_entry_never_carries_the_generators_own_words() {
            when(batches.failedSince(WINDOW_FROM)).thenReturn(List.of(dead()));

            final ExceptionReport report = service.build(WINDOW, RUN_ID);

            assertThat(BatchException.class.getRecordComponents())
                    .as("sdg_reason is systemdocgenerator's free text about a document whose every "
                            + "defendant is a child; the projection has no component for it, so "
                            + "there is nowhere for it to be put (Principle VII)")
                    .extracting(RecordComponent::getName)
                    .doesNotContain("sdgReason");
            assertThat(report.entries())
                    .singleElement()
                    .extracting(ExceptionEntry::reason)
                    .as("and what is reported is this service's own bounded code")
                    .isIn(names(BatchFailureReason.values()));
        }

        @Test
        void unbatched_registers_are_late_only_before_the_most_recent_scheduled_generation_run() {
            when(registers.recordedUnbatchedBefore(any())).thenReturn(List.of(leftBehind()));

            final ExceptionReport report = service.build(WINDOW, RUN_ID);
            final ArgumentCaptor<Instant> askedAbout = ArgumentCaptor.forClass(Instant.class);
            verify(registers).recordedUnbatchedBefore(askedAbout.capture());

            assertThat(askedAbout.getValue())
                    .as("a register is late when the last scheduled generation run left it where "
                            + "it was; one recorded since that run has not had its turn yet")
                    .isEqualTo(LastScheduledRun.before(GENERATION_CRON, COURTS_ZONE, NOW));
            assertThat(report.entries())
                    .singleElement()
                    .satisfies(entry -> {
                        assertThat(entry.kind()).isEqualTo(ExceptionKind.BATCH_LATE);
                        assertThat(entry.batchId())
                                .as("there is no batch: that is what is wrong with it")
                                .isNull();
                        assertThat(entry.hearingId()).isEqualTo(HEARING_ID);
                        assertThat(entry.courtCentreId()).isEqualTo(COURT_CENTRE);
                        assertThat(entry.ageSeconds()).isEqualTo(UNBATCHED_AGE);
                    });
        }

        @Test
        void a_register_recorded_while_the_flag_was_off_is_never_late() {
            service.build(WINDOW, RUN_ID);

            verify(registers).recordedUnbatchedBefore(any());
            verify(registers, never()).recordedWhileOff();
            verify(registers, never()).activeUnbatched();
            verifyNoMoreInteractions(registers);
        }
    }

    /**
     * The e-mails that were refused, named by their rows and by nothing else.
     */
    @Nested
    @DisplayName("what the notifications contribute")
    class TheNotifications {

        @Test
        void a_failed_notification_inside_the_window_names_its_batch_and_its_notification_and_no_address() {
            when(notifications.failedSince(WINDOW_FROM)).thenReturn(List.of(refused()));

            final ExceptionReport report = service.build(WINDOW, RUN_ID);

            assertThat(FailedNotification.class.getRecordComponents())
                    .as("no read this feature makes selects register_notification.email_address, "
                            + "so there is no value to mask and none to forget to mask")
                    .extracting(RecordComponent::getName)
                    .doesNotContain("emailAddress");
            assertThat(report.entries())
                    .singleElement()
                    .satisfies(entry -> {
                        assertThat(entry.kind()).isEqualTo(ExceptionKind.NOTIFICATION_FAILED);
                        assertThat(entry.notificationId()).isEqualTo(NOTIFICATION_ID);
                        assertThat(entry.batchId()).isEqualTo(BATCH_ID);
                        assertThat(entry.courtCentreId()).isEqualTo(COURT_CENTRE);
                        assertThat(entry.registerDate()).isEqualTo(REGISTER_DATE);
                        assertThat(entry.status()).isEqualTo(NotificationStatus.FAILED.name());
                        assertThat(entry.attempts()).isEqualTo(ATTEMPTS);
                        assertThat(entry.reason()).isEqualTo(String.valueOf(RESPONSE_CODE));
                        assertThat(entry.ageSeconds()).isEqualTo(NOTIFICATION_AGE);
                    });
        }
    }

    /**
     * What the report as a whole says, over all five kinds at once.
     */
    @Nested
    @DisplayName("the report the eight reads fold into")
    class TheReportItself {

        @Test
        void every_age_on_every_entry_is_the_one_the_statement_computed() {
            seedOneOfEveryKind();

            final ExceptionReport report = service.build(WINDOW, RUN_ID);

            assertThat(report.entries())
                    .as("each projection carries the age its own statement measured, from the "
                            + "column that stage is timed off; a service that subtracted a stored "
                            + "timestamp from a JVM reading would be comparing two clocks")
                    .extracting(ExceptionEntry::ageSeconds)
                    .containsExactlyInAnyOrder(UNBATCHED_AGE, REQUEST_LATE_AGE, GENERATING_AGE,
                            REQUEST_FAILED_AGE, BATCH_FAILED_AGE, PENDING_AGE, GENERATED_AGE,
                            NOTIFICATION_AGE);
        }

        @Test
        void entries_are_ordered_oldest_first_across_all_five_kinds() {
            seedOneOfEveryKind();

            final ExceptionReport report = service.build(WINDOW, RUN_ID);

            assertThat(report.entries())
                    .as("the worst problem is read first, on a screen and in a table, and one "
                            + "order across the five kinds is what makes that true of the report "
                            + "rather than of each read")
                    .extracting(ExceptionEntry::ageSeconds)
                    .isSortedAccordingTo(Comparator.reverseOrder());
        }

        @Test
        void an_empty_window_yields_five_zero_counts_and_no_entries() {
            final ExceptionReport report = service.build(WINDOW, RUN_ID);

            assertThat(report.entries())
                    .as("a quiet morning is a report, not a silence")
                    .isEmpty();
            assertThat(report.counts())
                    .as("five numbers always, so an empty morning is distinguishable from a "
                            + "morning the report did not run (FR-012)")
                    .containsOnlyKeys(ExceptionKind.values())
                    .containsValue(0)
                    .allSatisfy((kind, count) -> assertThat(count).isZero());
        }

        @Test
        void the_run_id_on_the_report_is_the_one_the_caller_passed() {
            final ExceptionReport report = service.build(WINDOW, "run-from-the-command");

            assertThat(report.runId())
                    .as("whoever opened the correlation passes it in, which is what keeps the "
                            + "application layer free of the MDC and makes FR-011 true on the "
                            + "command's path as well as the job's")
                    .isEqualTo("run-from-the-command");
            assertThat(report.window()).isEqualTo(WINDOW);
            assertThat(report.snapshotAt())
                    .as("and the snapshot is the moment the reads were taken")
                    .isEqualTo(NOW);
        }

        @Test
        void the_report_writes_nothing_back() {
            seedOneOfEveryKind();

            service.build(WINDOW, RUN_ID);

            verify(requests).failedSince(WINDOW_FROM);
            verify(requests).nonTerminalOlderThan(any());
            verify(batches).latePending(any());
            verify(batches).lateGenerating(any());
            verify(batches).lateGenerated(any());
            verify(batches).failedSince(WINDOW_FROM);
            verify(notifications).failedSince(WINDOW_FROM);
            verify(registers).recordedUnbatchedBefore(any());
            verifyNoMoreInteractions(requests, batches, notifications, registers);
        }
    }

    private void seedOneOfEveryKind() {
        when(requests.failedSince(WINDOW_FROM)).thenReturn(List.of(parked()));
        when(requests.nonTerminalOlderThan(any())).thenReturn(List.of(stuckSinceFriday()));
        when(batches.latePending(any()))
                .thenReturn(List.of(late(BatchStatus.PENDING, PENDING_AGE)));
        when(batches.lateGenerating(any()))
                .thenReturn(List.of(late(BatchStatus.GENERATING, GENERATING_AGE)));
        when(batches.lateGenerated(any()))
                .thenReturn(List.of(late(BatchStatus.GENERATED, GENERATED_AGE)));
        when(batches.failedSince(WINDOW_FROM)).thenReturn(List.of(dead()));
        when(notifications.failedSince(WINDOW_FROM)).thenReturn(List.of(refused()));
        when(registers.recordedUnbatchedBefore(any())).thenReturn(List.of(leftBehind()));
    }

    private static List<String> names(final Enum<?>[] values) {
        return Arrays.stream(values).map(Enum::name).toList();
    }

    private static ProcessedRequestSummary parked() {
        return new ProcessedRequestSummary(SOURCE, REQUEST_ID, HEARING_ID, HEARING_DAY,
                RequestStatus.FAILED, ATTEMPTS, "schema-violation",
                WINDOW_FROM, WINDOW_FROM.plus(Duration.ofMinutes(1)), REQUEST_FAILED_AGE);
    }

    private static ProcessedRequestSummary stuckSinceFriday() {
        return new ProcessedRequestSummary(SOURCE, REQUEST_ID, HEARING_ID, HEARING_DAY,
                RequestStatus.RETRYING, ATTEMPTS, null,
                WINDOW_FROM.minus(Duration.ofDays(3)), WINDOW_FROM.minus(Duration.ofDays(2)),
                REQUEST_LATE_AGE);
    }

    private static BatchException late(final BatchStatus status, final long ageSeconds) {
        return new BatchException(BATCH_ID, COURT_CENTRE, REGISTER_DATE, status, null,
                ATTEMPTS, ageSeconds);
    }

    private static BatchException dead() {
        return new BatchException(BATCH_ID, COURT_CENTRE, REGISTER_DATE, BatchStatus.FAILED,
                BatchFailureReason.GENERATION_FAILED, ATTEMPTS, BATCH_FAILED_AGE);
    }

    private static FailedNotification refused() {
        return new FailedNotification(NOTIFICATION_ID, BATCH_ID, COURT_CENTRE, REGISTER_DATE,
                NotificationStatus.FAILED, RESPONSE_CODE, ATTEMPTS, NOW.minus(NOTIFIED_WITHIN),
                NOTIFICATION_AGE);
    }

    private static RecordedRegisterSummary leftBehind() {
        return new RecordedRegisterSummary(OUTPUT_ID, HEARING_ID, COURT_CENTRE, REGISTER_DATE,
                WINDOW_FROM.minus(Duration.ofDays(1)), UNBATCHED_AGE);
    }
}
