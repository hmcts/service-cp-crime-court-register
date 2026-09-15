package uk.gov.hmcts.cp.courtregister.batch.cli;

import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import uk.gov.hmcts.cp.courtregister.application.ExceptionReportService;
import uk.gov.hmcts.cp.courtregister.application.ExceptionReportSink;
import uk.gov.hmcts.cp.courtregister.batch.RunCorrelation;
import uk.gov.hmcts.cp.courtregister.config.ReportProperties;
import uk.gov.hmcts.cp.courtregister.domain.DeliveryOutcome;
import uk.gov.hmcts.cp.courtregister.domain.DeliveryStatus;
import uk.gov.hmcts.cp.courtregister.domain.ExceptionEntry;
import uk.gov.hmcts.cp.courtregister.domain.ExceptionKind;
import uk.gov.hmcts.cp.courtregister.domain.ExceptionReport;
import uk.gov.hmcts.cp.courtregister.domain.ReportDeliveryReason;
import uk.gov.hmcts.cp.courtregister.domain.ReportRunOutcome;
import uk.gov.hmcts.cp.courtregister.domain.ReportSinkName;
import uk.gov.hmcts.cp.courtregister.domain.ReportWindow;
import uk.gov.hmcts.cp.courtregister.support.AdjustableClock;
import uk.gov.hmcts.cp.courtregister.support.PersonalDataMarkers;

/**
 * The sixth command: the same report the 07:00 run writes, over a window an operator names.
 *
 * <p>An incident does not wait for morning. The five commands that came before this one are the
 * surface support already reaches by {@code kubectl exec}, and this adds the one question they
 * could not answer - what has gone wrong, since when - without composing a second read path:
 * {@link ExceptionReportService} is the same service the schedule asks, so a table read at 02:00
 * and the morning's events cannot disagree about what an exception is.
 *
 * <p><strong>What this suite is really holding down is the argument.</strong> {@code --since} is as
 * likely to receive a half-remembered runbook step as an instant, and a command is reached by an
 * operator's own typing rather than by a delivery - so every refusal below is asserted twice over:
 * that it <em>is</em> a refusal ({@link CliMain#REFUSED}, nothing read and nothing written), and
 * that the token the operator typed is nowhere in what came back. That second half is
 * {@code config/TelemetryPrivacyTest}'s claim as well, over the log; here it is the terminal, which
 * is the surface that gets pasted into tickets.
 *
 * <p><strong>And the sinks, because a report is not free to send.</strong> The command delivers to
 * the log sink always and adds the e-mail sink only where {@code --email} was given and accepted: a
 * command that e-mailed support on every invocation would make an incident's third run an incident
 * of its own. The last line says which of the two were asked and how each went, in the job's own
 * words, so an on-demand report and a scheduled one are read the same way.
 */
@ExtendWith(SoftAssertionsExtension.class)
@DisplayName("the report an operator asks for")
class ReportExceptionsCliTest {

    /** The moment every window in this suite ends at, fixed so the arithmetic is readable. */
    private static final Instant NOW = Instant.parse("2026-09-15T09:30:00Z");

    /** The report's own schedule, which is what an absent window is measured back through. */
    private static final String CRON = "0 0 7 * * MON-FRI";

    /** The shipped entry cap, stated rather than defaulted: no case here is about truncation. */
    private static final int MAX_ENTRIES = 5000;

    private static final String ZONE = "Europe/London";

    /** The thirteen keys an exception line may carry, spelled as the events spell them. */
    private static final Set<String> EVENT_FIELD_NAMES = Set.of("kind", "source", "request_id",
            "hearing_id", "hearing_day", "batch_id", "notification_id", "court_centre_id",
            "register_date", "status", "attempts", "reason", "age_seconds");

    /** The request the two intake entries are about. */
    private static final UUID REQUEST_ID = UUID.fromString("4c8e1a70-9b2d-4f36-8a57-c1d0e9f3b284");

    private static final UUID HEARING_ID = UUID.fromString("9e2b4c60-1d38-4a75-9f04-6b3c8d1e5a72");

    private static final UUID BATCH_ID = UUID.fromString("11111111-2222-4333-8444-555555555555");

    private static final UUID NOTIFICATION_ID =
            UUID.fromString("66666666-7777-4888-8999-aaaaaaaaaaaa");

    private static final UUID COURT_CENTRE =
            UUID.fromString("2f6b8d10-4a3c-4e57-9b21-8c0d5e7f1a94");

    private static final LocalDate REGISTER_DATE = LocalDate.of(2026, 9, 14);

    /** The ages the three entries carry, oldest last so a sort has something to do. */
    private static final long YOUNGEST = 600L;

    private static final long MIDDLE = 4_820L;

    private static final long OLDEST = 61_240L;

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    private final ExceptionReportService reporting = mock(ExceptionReportService.class);

    private final ExceptionReportSink logSink = sinkNamed(ReportSinkName.LOG);

    private final ExceptionReportSink emailSink = sinkNamed(ReportSinkName.EMAIL);

    /** What an operator's terminal shows, one entry per line the command wrote. */
    private final List<String> printed = new ArrayList<>();

    private final Consumer<String> output = printed::add;

    @InjectSoftAssertions
    private SoftAssertions softly;

    /** A sink that answers its own name and nothing else, so the command's choice is readable. */
    private static ExceptionReportSink sinkNamed(final ReportSinkName name) {
        final ExceptionReportSink sink = mock(ExceptionReportSink.class);
        when(sink.name()).thenReturn(name);
        return sink;
    }

    /**
     * The shorthands the window may be given in, one row per unit.
     *
     * @return each shorthand beside the duration it stands for
     */
    static Stream<Arguments> theShorthands() {
        return Stream.of(
                arguments("2d", Duration.ofDays(2)),
                arguments("2h", Duration.ofHours(2)),
                arguments("30m", Duration.ofMinutes(30)),
                arguments("90s", Duration.ofSeconds(90)));
    }

    /** The settings a deployment ships with the e-mail output off, which is every one today. */
    private static ReportProperties withoutEmail() {
        return settings(false);
    }

    /** And the same with it on, which is the shape Phase 7 deploys behind the Notify template. */
    private static ReportProperties withEmail() {
        return settings(true);
    }

    private static ReportProperties settings(final boolean email) {
        return new ReportProperties(true, CRON, ZONE, false, Duration.ofMinutes(15),
                Duration.ofMinutes(30), Duration.ofMinutes(15), Duration.ofMinutes(30),
                MAX_ENTRIES,
                new ReportProperties.Email(email, "11111111-1111-1111-1111-111111111111",
                        List.of("courtregister-support@example.test")));
    }

    /**
     * The same settings with a schedule nothing can be measured back through.
     *
     * <p>A deployment cannot reach this - {@code PropertiesValidator} refuses a cron it cannot
     * parse at startup - and that is not what the case below is about. What it is about is which
     * of the two answers an operator gets when the default window cannot be computed: a window
     * nobody typed is not an argument they got wrong.
     */
    private static ReportProperties withoutAReadableSchedule() {
        return new ReportProperties(true, "every weekday morning at seven", ZONE, false,
                Duration.ofMinutes(15), Duration.ofMinutes(30), Duration.ofMinutes(15),
                Duration.ofMinutes(30), MAX_ENTRIES, new ReportProperties.Email(false,
                        "11111111-1111-1111-1111-111111111111",
                        List.of("courtregister-support@example.test")));
    }

    /** The command over the sinks a deployment holds. */
    private ReportExceptionsCli command(final ReportProperties settings,
            final ExceptionReportSink... onThisContext) {

        return command(settings, clock, onThisContext);
    }

    /** The same, over a clock a case moves by hand. */
    private ReportExceptionsCli command(final ReportProperties settings, final Clock over,
            final ExceptionReportSink... onThisContext) {

        return new ReportExceptionsCli(reporting, List.of(onThisContext), settings, over, output);
    }

    /** Three exceptions, one of each of three kinds, given to the service to answer with. */
    private static ExceptionReport aReportOf(final String runId, final ReportWindow window) {
        return ExceptionReport.whole(runId, window, NOW, List.of(
                new ExceptionEntry(ExceptionKind.REQUEST_LATE, "RESULTS", REQUEST_ID, HEARING_ID,
                        REGISTER_DATE, null, null, null, null, "RETRYING", 2, null, YOUNGEST),
                new ExceptionEntry(ExceptionKind.BATCH_FAILED, null, null, null, null, BATCH_ID,
                        null, COURT_CENTRE, REGISTER_DATE, "FAILED", null, "GENERATION_TIMED_OUT",
                        MIDDLE),
                new ExceptionEntry(ExceptionKind.NOTIFICATION_FAILED, null, null, null, null,
                        BATCH_ID, NOTIFICATION_ID, COURT_CENTRE, REGISTER_DATE, "FAILED", 3, "502",
                        OLDEST)));
    }

    /** Arranges the service to answer the three exceptions, delivered as the caller says. */
    private void reportAnswered(final List<DeliveryOutcome> delivered) {
        when(reporting.build(any(ReportWindow.class), any())).thenAnswer(invocation ->
                aReportOf(invocation.getArgument(1), invocation.getArgument(0)));
        when(reporting.deliver(any(ExceptionReport.class), anyCollection())).thenReturn(delivered);
    }

    /** One sink's answer, as the service would fold it. */
    private static DeliveryOutcome tookIt(final ReportSinkName sink) {
        return new DeliveryOutcome(sink, DeliveryStatus.DELIVERED, ReportDeliveryReason.NONE, 1, 0);
    }

    private static DeliveryOutcome brokeOn(final ReportSinkName sink) {
        return new DeliveryOutcome(sink, DeliveryStatus.NOT_DELIVERED,
                ReportDeliveryReason.SEND_FAILED, 0, 1);
    }

    /** The window the service was asked to build over. */
    private ReportWindow windowAskedFor() {
        final ArgumentCaptor<ReportWindow> asked = ArgumentCaptor.forClass(ReportWindow.class);
        verify(reporting).build(asked.capture(), any());
        return asked.getValue();
    }

    /** The sinks the command chose to deliver to. */
    private Collection<ExceptionReportSink> sinksAskedOf() {
        @SuppressWarnings("unchecked")
        final ArgumentCaptor<Collection<ExceptionReportSink>> asked =
                ArgumentCaptor.forClass(Collection.class);
        verify(reporting).deliver(any(ExceptionReport.class), asked.capture());
        return asked.getValue();
    }

    /** The command's last line, which is always the run's own. */
    private String lastLine() {
        return printed.get(printed.size() - 1);
    }

    /** The lines that describe one exception, which are every line before the counts line. */
    private List<String> exceptionLines() {
        return printed.stream().filter(line -> line.startsWith("kind=")).toList();
    }

    /**
     * The table of data-model.md's {@code --since} rules, one case per row.
     */
    @Nested
    @DisplayName("the window an operator names")
    class TheWindow {

        @Test
        void an_iso_instant_should_be_the_windows_from() {
            reportAnswered(List.of(tookIt(ReportSinkName.LOG)));

            command(withoutEmail(), logSink).run(List.of("--since", "2026-09-14T06:00:00Z"));

            softly.assertThat(windowAskedFor().from())
                    .as("an instant is the window's start as it was typed: an operator who has a "
                            + "moment from a ticket is naming it, not describing a duration")
                    .isEqualTo(Instant.parse("2026-09-14T06:00:00Z"));
            softly.assertThat(windowAskedFor().to())
                    .as("and the window always ends now, read from the injected clock")
                    .isEqualTo(NOW);
        }

        @Test
        void an_iso_duration_should_be_subtracted_from_now() {
            reportAnswered(List.of(tookIt(ReportSinkName.LOG)));

            command(withoutEmail(), logSink).run(List.of("--since", "PT2H"));

            softly.assertThat(windowAskedFor().from())
                    .as("an ISO-8601 duration is how long ago, not when")
                    .isEqualTo(NOW.minus(Duration.ofHours(2)));
        }

        @ParameterizedTest(name = "--since {0}")
        @MethodSource("uk.gov.hmcts.cp.courtregister.batch.cli.ReportExceptionsCliTest"
                + "#theShorthands")
        void the_day_hour_minute_and_second_shorthands_should_be_subtracted_from_now(
                final String typed, final Duration stands) {

            reportAnswered(List.of(tookIt(ReportSinkName.LOG)));

            command(withoutEmail(), logSink).run(List.of("--since", typed));

            softly.assertThat(windowAskedFor().from())
                    .as("%s is what an operator types at 02:00, and the four units are the whole "
                            + "of the shorthand", typed)
                    .isEqualTo(NOW.minus(stands));
        }

        @Test
        void an_absent_since_should_use_the_window_since_the_previous_scheduled_run() {
            reportAnswered(List.of(tookIt(ReportSinkName.LOG)));

            command(withoutEmail(), logSink).run(List.of());

            softly.assertThat(windowAskedFor())
                    .as("the bare command answers what the morning run would have answered: there "
                            + "is no window setting to fall back on, because a duration beside a "
                            + "schedule is one fact written twice")
                    .isEqualTo(ReportWindow.sinceLastScheduledRun(CRON, ZONE, NOW));
        }

        @Test
        void a_default_window_that_cannot_be_computed_is_the_reports_failure_not_the_operators() {
            final int code = command(withoutAReadableSchedule(), logSink).run(List.of());

            softly.assertThat(code)
                    .as("nobody typed a window, so nobody can be told their argument was "
                            + "unreadable: this is the command failing to build its report, which "
                            + "is exit 2 and a thing a runbook may retry")
                    .isEqualTo(CliMain.FAILED);
            softly.assertThat(printed)
                    .as("under the same bounded reason a read that would not answer carries, and "
                            + "never naming --since, which the operator never gave")
                    .anyMatch(line -> line.contains("outcome=failed"))
                    .noneMatch(line -> line.contains(CliMain.UNREADABLE_ARGUMENT))
                    .noneMatch(line -> line.contains("--" + Args.SINCE + " "));
            verifyNoInteractions(reporting, logSink, emailSink);
        }
    }

    /**
     * What the command declines rather than guesses, and what it says while declining.
     */
    @Nested
    @DisplayName("an argument it will not read")
    class Refusals {

        @ParameterizedTest(name = "--since {0}")
        @ValueSource(strings = {"PT0S", "-PT2H", "0h", "0d", "PT-30M"})
        void a_zero_or_negative_duration_should_be_refused(final String typed) {
            final int code = command(withoutEmail(), logSink).run(List.of("--since", typed));

            softly.assertThat(code)
                    .as("a window of no width reports nothing and looks exactly like a quiet "
                            + "night, and a negative one is a window that has not happened yet")
                    .isEqualTo(CliMain.REFUSED);
            softly.assertThat(printed)
                    .as("under the bounded reason the five commands already use for a value they "
                            + "could not read")
                    .contains("command=" + CliMain.REPORT_EXCEPTIONS + " outcome=refused reason="
                            + CliMain.UNREADABLE_ARGUMENT);
            verifyNoInteractions(reporting, logSink, emailSink);
        }

        @ParameterizedTest(name = "--since {0}")
        @ValueSource(strings = {"h", "d", "xh", "last-tuesday", "2 hours", "2H"})
        void a_shorthand_with_no_digits_should_be_refused(final String typed) {
            final int code = command(withoutEmail(), logSink).run(List.of("--since", typed));

            softly.assertThat(code)
                    .as("a unit with no number in front of it is a half-typed runbook step, and "
                            + "guessing which number was meant is the one thing this must not do")
                    .isEqualTo(CliMain.REFUSED);
            verifyNoInteractions(reporting, logSink, emailSink);
        }

        @Test
        void an_instant_in_the_future_should_be_refused() {
            final int code = command(withoutEmail(), logSink)
                    .run(List.of("--since", "2026-09-16T06:00:00Z"));

            softly.assertThat(code)
                    .as("a window that starts after it ends is not a window, and the report over "
                            + "it would be five zeroes an operator would read as an all-clear")
                    .isEqualTo(CliMain.REFUSED);
            verifyNoInteractions(reporting, logSink, emailSink);
        }

        @ParameterizedTest(name = "--since {0}")
        @ValueSource(strings = {"last-tuesday", "0h", "2026-09-16T06:00:00Z"})
        void the_refusal_should_name_since_and_never_quote_the_token(final String typed) {
            command(withoutEmail(), logSink).run(List.of("--since", typed));

            softly.assertThat(printed)
                    .as("an operator's terminal is pasted into tickets, and --since is as likely "
                            + "to receive a pasted credential as an instant")
                    .noneMatch(line -> line.contains(typed))
                    .anyMatch(line -> line.contains("--" + Args.SINCE));
        }

        @Test
        void an_instant_exactly_now_should_be_refused() {
            final int code =
                    command(withoutEmail(), logSink).run(List.of("--since", NOW.toString()));

            softly.assertThat(code)
                    .as("the boundary as the code has it: the window must open strictly before it "
                            + "closes, so now is refused rather than read as a window of no width "
                            + "- which would report nothing and look exactly like a quiet night")
                    .isEqualTo(CliMain.REFUSED);
            verifyNoInteractions(reporting, logSink, emailSink);
        }

        @Test
        void an_option_the_command_does_not_accept_should_be_refused_with_the_usage_line() {
            final int code = command(withoutEmail(), logSink)
                    .run(List.of("--" + Args.BATCH, BATCH_ID.toString()));

            softly.assertThat(code)
                    .as("user story 3 scenario 4: another command's argument is a mistyped "
                            + "invocation, answered exactly as the five existing commands answer "
                            + "one")
                    .isEqualTo(CliMain.REFUSED);
            softly.assertThat(printed)
                    .as("the bounded reason and, under it, what this command takes - so an "
                            + "operator mid-incident does not have to go and find the runbook")
                    .containsExactly("command=" + CliMain.REPORT_EXCEPTIONS
                            + " outcome=refused reason=" + CliMain.UNEXPECTED_ARGUMENT,
                            ReportExceptionsCli.USAGE);
            verifyNoInteractions(reporting, logSink, emailSink);
        }

        @Test
        void email_should_be_refused_as_declined_when_the_email_output_is_disabled() {
            final int code =
                    command(withoutEmail(), logSink).run(List.of("--since", "2h", "--email"));

            softly.assertThat(code)
                    .as("exit 1, the code the five existing commands use for a refusal: the "
                            + "command declined and changed nothing, which is not a failure to "
                            + "retry")
                    .isEqualTo(CliMain.REFUSED);
            softly.assertThat(printed)
                    .as("and that is the whole of the terminal: the refusal naming the setting, "
                            + "because the answer to it is a deployment change rather than a "
                            + "different invocation, and the usage line under it - no table, no "
                            + "counts line and no run line, because nothing was read")
                    .containsExactly("command=" + CliMain.REPORT_EXCEPTIONS
                            + " outcome=refused reason=email-output-disabled"
                            + " setting=courtregister.report.email.enabled",
                            ReportExceptionsCli.USAGE);
            verifyNoInteractions(reporting, logSink, emailSink);
        }

        @Test
        void email_asked_where_the_output_is_on_and_no_sink_is_wired_should_name_the_sink() {
            final int code =
                    command(withEmail(), logSink).run(List.of("--since", "2h", "--email"));

            softly.assertThat(code)
                    .as("the branch Phase 7's conditional bean makes unreachable on a deployed "
                            + "context and nothing in this class's own type makes impossible: the "
                            + "sinks are handed in, so a context that switched the output on "
                            + "without wiring one declines rather than quietly running log-only")
                    .isEqualTo(CliMain.REFUSED);
            softly.assertThat(printed.getFirst())
                    .as("and it names the sink rather than the setting: the setting is on and "
                            + "correct, so an operator sent to change it would be sent to the one "
                            + "place there is nothing to change")
                    .contains("reason=email-output-not-wired")
                    .contains("sink=email")
                    .doesNotContain("setting=");
            verifyNoInteractions(reporting, emailSink);
        }

        @Test
        void asking_what_the_command_takes_should_print_the_usage_and_read_nothing() {
            final int code = command(withoutEmail(), logSink).run(List.of("--help"));

            softly.assertThat(code)
                    .as("asking what a command takes is a thing the command did, not a refusal")
                    .isEqualTo(CliMain.SUCCESS);
            softly.assertThat(printed).containsExactly(ReportExceptionsCli.USAGE);
            verifyNoInteractions(reporting, logSink, emailSink);
        }
    }

    /**
     * The table, the counts and the run's own last line.
     */
    @Nested
    @DisplayName("what it writes")
    class TheTable {

        @Test
        void one_bounded_line_per_exception_should_be_written_oldest_first() {
            reportAnswered(List.of(tookIt(ReportSinkName.LOG)));

            command(withoutEmail(), logSink).run(List.of("--since", "2h"));

            softly.assertThat(exceptionLines())
                    .as("one line per exception, in the order the report holds them, which is the "
                            + "order the service sorted them into")
                    .hasSize(3);
            softly.assertThat(exceptionLines().getFirst())
                    .as("and the first is the first entry the report carries, so a table read at "
                            + "02:00 and the morning's events cannot order the same night "
                            + "differently")
                    .startsWith("kind=" + ExceptionKind.REQUEST_LATE);
        }

        @Test
        void every_line_should_use_the_event_field_names() {
            reportAnswered(List.of(tookIt(ReportSinkName.LOG)));

            command(withoutEmail(), logSink).run(List.of("--since", "2h"));

            final Set<String> keys = exceptionLines().stream()
                    .flatMap(line -> Stream.of(line.split(" ")))
                    .map(pair -> pair.substring(0, pair.indexOf('=')))
                    .collect(Collectors.toUnmodifiableSet());

            softly.assertThat(keys)
                    .as("a key that differs between the table and the index is a key somebody "
                            + "greps for and does not find")
                    .isSubsetOf(EVENT_FIELD_NAMES)
                    .contains("request_id", "batch_id", "notification_id", "court_centre_id",
                            "register_date", "age_seconds");
            softly.assertThat(exceptionLines())
                    .as("and a field the kind does not carry is absent rather than empty, exactly "
                            + "as the event omits it")
                    .anyMatch(line -> !line.contains("batch_id="));
        }

        @Test
        void the_counts_line_should_carry_the_five_counts_and_the_window() {
            reportAnswered(List.of(tookIt(ReportSinkName.LOG)));

            command(withoutEmail(), logSink).run(List.of("--since", "2h"));

            softly.assertThat(printed)
                    .as("five numbers, zeroes included, so an empty morning is distinguishable "
                            + "from a morning the report did not run - and the window they are "
                            + "over, because a count with no window is a number about nothing")
                    .contains("counts request_failed=0 request_late=1 batch_late=0 batch_failed=1"
                            + " notification_failed=1"
                            + " window_from=" + NOW.minus(Duration.ofHours(2))
                            + " window_to=" + NOW);
        }

        @Test
        void an_empty_window_should_print_one_line_saying_so() {
            when(reporting.build(any(ReportWindow.class), any())).thenAnswer(invocation ->
                    ExceptionReport.whole(invocation.getArgument(1),
                            invocation.getArgument(0), NOW, List.of()));
            when(reporting.deliver(any(ExceptionReport.class), anyCollection()))
                    .thenReturn(List.of(tookIt(ReportSinkName.LOG)));

            command(withoutEmail(), logSink).run(List.of("--since", "2h"));

            softly.assertThat(exceptionLines())
                    .as("nothing to list is not nothing to say")
                    .isEmpty();
            softly.assertThat(printed.getFirst())
                    .as("one line saying so, so a window that covers nothing is read as an "
                            + "answer rather than as a command that did not run")
                    .isEqualTo("exceptions=none");
            softly.assertThat(printed)
                    .as("and all three lines are still written: the saying-so line, the five "
                            + "zeroes with the window they are over, and the run's own line - a "
                            + "quiet window is the shape that most looks like a command that never "
                            + "ran, so it is the one that has to say the most")
                    .hasSize(3);
            softly.assertThat(printed.get(1))
                    .startsWith("counts request_failed=0")
                    .contains("notification_failed=0");
            softly.assertThat(lastLine())
                    .contains("event=exception_report_run")
                    .contains("entries=0");
        }

        @Test
        void no_line_should_carry_a_recipient_address_at_all() {
            reportAnswered(List.of(tookIt(ReportSinkName.LOG)));

            command(withoutEmail(), logSink).run(List.of("--since", "2h"));

            softly.assertThat(printed)
                    .as("not masked, absent: no read this feature makes selects an address, so a "
                            + "refused send is named by its notification id, its batch and its "
                            + "response code and there is nothing to mask")
                    .noneMatch(line -> line.contains("@"))
                    .noneMatch(line -> line.contains(PersonalDataMarkers.RECIPIENT_EMAIL));
        }

        @Test
        void the_command_should_open_its_own_run_correlation_and_pass_the_id_into_build() {
            reportAnswered(List.of(tookIt(ReportSinkName.LOG)));

            command(withoutEmail(), logSink).run(List.of("--since", "2h"));

            final ArgumentCaptor<String> runId = ArgumentCaptor.forClass(String.class);
            verify(reporting).build(any(ReportWindow.class), runId.capture());

            softly.assertThat(runId.getValue())
                    .as("FR-011: an on-demand report's lines and events carry a run id exactly as "
                            + "the 07:00 run's do, and the service takes it as an argument so that "
                            + "the application layer never touches an MDC")
                    .isNotBlank();
            softly.assertThat(lastLine())
                    .as("and the run's own line names the same one")
                    .contains("run_id=" + runId.getValue());
            softly.assertThat(RunCorrelation.current())
                    .as("and only the call that put one there takes it away again: a JVM that ran "
                            + "one command and exits does not need the removal, but the scope is "
                            + "the same scope the pooled scheduler threads use")
                    .isNull();
        }

        @Test
        void the_last_line_should_be_the_runs_own_and_written_after_every_sink_has_returned() {
            final List<String> whenAsked = new ArrayList<>();
            when(reporting.build(any(ReportWindow.class), any())).thenAnswer(invocation ->
                    aReportOf(invocation.getArgument(1), invocation.getArgument(0)));
            when(reporting.deliver(any(ExceptionReport.class), anyCollection()))
                    .thenAnswer(invocation -> {
                        whenAsked.addAll(printed);
                        return List.of(tookIt(ReportSinkName.LOG), tookIt(ReportSinkName.EMAIL));
                    });

            command(withEmail(), logSink, emailSink).run(List.of("--since", "2h", "--email"));

            softly.assertThat(whenAsked)
                    .as("read at the moment the sinks were asked: a command that wrote the line "
                            + "first would be reporting a delivery nobody had observed, and the "
                            + "morning that mattered would be the morning a sink was refusing")
                    .noneMatch(line -> line.contains("event=exception_report_run"));
            softly.assertThat(lastLine())
                    .as("the equivalent of the job's exception_report_run line: the same four "
                            + "things about its own delivery, written last because delivered_log "
                            + "and delivered_email are claims about deliveries that have happened")
                    .startsWith("event=exception_report_run run_id=")
                    .contains(" window_from=" + NOW.minus(Duration.ofHours(2)))
                    .contains(" window_to=" + NOW)
                    .contains(" entries=3")
                    .contains(" delivered_log=ok")
                    .contains(" delivered_email=ok")
                    .contains(" outcome=delivered");
        }

        /**
         * The command's last line says the same thing the 07:00 run's does, in the same word.
         */
        @Test
        void the_last_line_should_carry_the_truncated_count() {
            when(reporting.build(any(ReportWindow.class), any())).thenAnswer(invocation ->
                    new ExceptionReport(invocation.getArgument(1), invocation.getArgument(0),
                            NOW, List.of(), 7, Map.of()));
            when(reporting.deliver(any(ExceptionReport.class), anyCollection()))
                    .thenReturn(List.of(tookIt(ReportSinkName.LOG)));

            command(withoutEmail(), logSink).run(List.of("--since", "2h"));

            softly.assertThat(lastLine())
                    .as("an operator reading a truncated table has to be told it was truncated on "
                            + "the same line that tells them how much of it they are looking at")
                    .contains(" truncated=7");
        }

        @Test
        void the_last_line_should_carry_duration_ms_from_the_clock() {
            final AdjustableClock moving = AdjustableClock.startingAt(NOW);
            when(reporting.build(any(ReportWindow.class), any())).thenAnswer(invocation ->
                    aReportOf(invocation.getArgument(1), invocation.getArgument(0)));
            when(reporting.deliver(any(ExceptionReport.class), anyCollection()))
                    .thenAnswer(invocation -> {
                        moving.advance(Duration.ofMillis(250));
                        return List.of(tookIt(ReportSinkName.LOG));
                    });

            command(withoutEmail(), moving, logSink).run(List.of("--since", "2h"));

            softly.assertThat(lastLine())
                    .as("measured on the clock this command was handed, between opening the "
                            + "correlation and writing this line, exactly as the 07:00 run "
                            + "measures its own: a duration read off a second, un-injected clock "
                            + "is a field no case can state a value for")
                    .contains(" duration_ms=250");
        }

        @Test
        void a_context_with_no_log_sink_should_exit_could_not() {
            when(reporting.build(any(ReportWindow.class), any())).thenAnswer(invocation ->
                    aReportOf(invocation.getArgument(1), invocation.getArgument(0)));
            when(reporting.deliver(any(ExceptionReport.class), anyCollection()))
                    .thenReturn(List.of());

            final int code = command(withoutEmail()).run(List.of("--since", "2h"));

            softly.assertThat(code)
                    .as("a command that told nobody anything has not succeeded, whatever it "
                            + "printed: the table is a terminal and the sinks are the record, and "
                            + "a context holding neither sink is a deployment to fix")
                    .isEqualTo(CliMain.FAILED);
            softly.assertThat(lastLine())
                    .as("and the run's own line says so under the same three-state fold the "
                            + "morning run is counted by")
                    .contains(" delivered_log=disabled")
                    .contains(" outcome=failed");
        }

        @Test
        void an_email_sink_that_is_not_here_should_be_disabled_in_the_words_the_job_uses() {
            reportAnswered(List.of(tookIt(ReportSinkName.LOG)));

            command(withEmail(), logSink).run(List.of("--since", "2h"));

            softly.assertThat(lastLine())
                    .as("the output is switched on in the settings and no sink was contributed, "
                            + "which is the shape the two copies of this word disagreed about: the "
                            + "job says disabled about a sink that is not on the context, and a "
                            + "command that said skipped would be describing a decision nobody "
                            + "made")
                    .contains(" delivered_email=disabled")
                    .doesNotContain(" delivered_email=skipped");
        }

        @Test
        void delivered_email_should_be_skipped_without_the_flag_and_disabled_where_it_is_off() {
            reportAnswered(List.of(tookIt(ReportSinkName.LOG)));

            command(withEmail(), logSink, emailSink).run(List.of("--since", "2h"));
            final String nobodyAsked = lastLine();
            printed.clear();

            command(withoutEmail(), logSink).run(List.of("--since", "2h"));

            softly.assertThat(nobodyAsked)
                    .as("nobody asked: the output exists and this invocation did not want it")
                    .contains(" delivered_email=skipped");
            softly.assertThat(lastLine())
                    .as("and nobody could: there is no e-mail output on this deployment at all, "
                            + "which is a different operational fact")
                    .contains(" delivered_email=disabled");
        }

        @Test
        void without_email_only_the_log_sink_should_be_delivered_to() {
            reportAnswered(List.of(tookIt(ReportSinkName.LOG)));

            command(withEmail(), logSink, emailSink).run(List.of("--since", "2h"));

            softly.assertThat(sinksAskedOf())
                    .as("a command that e-mailed support on every invocation would make an "
                            + "incident's third run an incident of its own")
                    .containsExactly(logSink);
        }

        @Test
        void with_email_the_email_sink_should_be_added() {
            reportAnswered(List.of(tookIt(ReportSinkName.LOG), tookIt(ReportSinkName.EMAIL)));

            command(withEmail(), logSink, emailSink).run(List.of("--since", "2h", "--email"));

            softly.assertThat(sinksAskedOf())
                    .as("the log sink always, and the e-mail sink where it was asked for and the "
                            + "output is on")
                    .containsExactly(logSink, emailSink);
        }

        @Test
        void a_sink_that_failed_should_exit_could_not() {
            reportAnswered(List.of(tookIt(ReportSinkName.LOG), brokeOn(ReportSinkName.EMAIL)));

            final int code = command(withEmail(), logSink, emailSink)
                    .run(List.of("--since", "2h", "--email"));

            softly.assertThat(code)
                    .as("exit 2, which is the same fact outcome=partial states on the last line, "
                            + "so a shell learns it without reading a line: the report reached one "
                            + "of its two audiences")
                    .isEqualTo(CliMain.FAILED);
            softly.assertThat(lastLine())
                    .contains(" delivered_log=ok")
                    .contains(" delivered_email=failed")
                    .contains(" outcome="
                            + ReportRunOutcome.PARTIAL.name().toLowerCase(Locale.ROOT));
        }

        @Test
        void a_report_that_could_not_be_built_should_be_reported_as_a_failure() {
            when(reporting.build(any(ReportWindow.class), any()))
                    .thenThrow(new IllegalStateException("the store could not be reached"));

            final int code = command(withoutEmail(), logSink).run(List.of("--since", "2h"));

            softly.assertThat(code)
                    .as("a read that would not answer is a command that tried and could not, and "
                            + "an operator gets the bounded line rather than a stack trace out of "
                            + "main and the JVM's own 1")
                    .isEqualTo(CliMain.FAILED);
            softly.assertThat(printed)
                    .as("under a bounded reason, and never the store's own words")
                    .anyMatch(line -> line.contains("outcome=failed"))
                    .noneMatch(line -> line.contains("the store could not be reached"));
        }
    }
}
