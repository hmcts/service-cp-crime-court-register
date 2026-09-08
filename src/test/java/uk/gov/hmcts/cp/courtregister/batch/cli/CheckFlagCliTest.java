package uk.gov.hmcts.cp.courtregister.batch.cli;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import uk.gov.hmcts.cp.courtregister.application.FeatureFlagReader;
import uk.gov.hmcts.cp.courtregister.domain.FlagDecision;
import uk.gov.hmcts.cp.courtregister.domain.FlagDecision.UnreadableReason;

/**
 * {@code check-flag}: the first question a cutover or a rollback asks, and its three answers.
 *
 * <p>{@code AppConfigurationFlagReaderTest} says what the store's answers mean and
 * {@code FeatureFlagGateTest} says what a nightly run does about them. This says what an operator
 * standing at a terminal is told, which is a third thing and the one a runbook step is written
 * against: the command exists so that "is this stack cut over?" can be answered from the pod that
 * would act on the answer, through the same reader the 18:00 run uses, before anybody generates
 * anything (FR-016, research §13).
 *
 * <p><strong>The exit code carries the answer's status, not the answer.</strong> ON and OFF are
 * both {@link CliMain#SUCCESS}: a stack before cutover is meant to say off, and a runbook step that
 * read a refusal into it would fail every night until somebody flipped the flag. A flag that could
 * not be read is {@link CliMain#FAILED}, because that is the reading on which the nightly run skips
 * fail-closed and the store itself is the thing that needs looking at - reporting it as a quiet
 * {@code OFF} would tell an operator the cutover had not happened yet when what happened is that
 * nobody could say. {@link CliMain#REFUSED} is left for the one thing that is neither: arguments
 * this command does not take.
 *
 * <p><strong>The line is exact, because a runbook greps it.</strong> Every case here asserts the
 * whole line rather than a fragment of it, so a later implementer who reformats the output has to
 * change these expectations deliberately; and the unreadable line carries the cause's own bounded
 * code, so an absent endpoint, a refused identity and a slow store stay three different things to
 * go and fix without any of the store's words about itself reaching the terminal (constitution
 * Principle VII).
 *
 * <p>The reading is taken once per invocation and never remembered: a flag that was on when the
 * last command ran says nothing about a rollback that happened since, which is the same rule the
 * gate keeps and for the same reason.
 */
@ExtendWith(SoftAssertionsExtension.class)
@DisplayName("check-flag")
class CheckFlagCliTest {

    /** Quoted by every case, so the red run names the seam it is waiting on. */
    private static final String PENDING = "T065 implements check-flag; this is its red run";

    /** Returned where the seam refused, so an exit code that was never produced fails as one. */
    private static final int NOT_RUN = -1;

    /** The flag was read and this service is the implementation that generates. */
    private static final String ON_LINE = "flag=ON";

    /** The flag was read and the legacy is. */
    private static final String OFF_LINE = "flag=OFF";

    /** Nobody could say, and the bounded cause is printed beside the verdict. */
    private static final String UNREADABLE_LINE = "flag=UNREADABLE reason=";

    private final FeatureFlagReader reader = mock(FeatureFlagReader.class);
    private final List<String> lines = new ArrayList<>();
    private final Consumer<String> output = lines::add;
    private final CheckFlagCli cli = new CheckFlagCli(reader, output);

    @InjectSoftAssertions
    private SoftAssertions softly;

    /**
     * Puts one reading in front of the command and runs it.
     *
     * <p>The call is made inside {@code assertThatCode(...).doesNotThrowAnyException()} so the
     * seam's refusal is recorded as an assertion rather than ending the case, and the exit code it
     * did not produce is then asserted on as {@link #NOT_RUN} - the red run is those assertions and
     * the green run is the same ones unchanged.
     *
     * @param reading what the store answers this time
     * @param args    what an operator typed after the command's name
     * @return the exit code, or {@link #NOT_RUN} where the seam refused
     */
    private int run(final FlagDecision reading, final String... args) {
        when(reader.read()).thenReturn(reading);
        return run(args);
    }

    /**
     * Runs the command with no reading configured, for the cases that must not reach the reader.
     *
     * @param args what an operator typed after the command's name
     * @return the exit code, or {@link #NOT_RUN} where the seam refused
     */
    private int run(final String... args) {
        final AtomicInteger code = new AtomicInteger(NOT_RUN);
        softly.assertThatCode(() -> code.set(cli.run(List.of(args))))
                .as(PENDING)
                .doesNotThrowAnyException();
        return code.get();
    }

    /**
     * The flag was read, and the answer is one an operator can act on.
     */
    @Nested
    @DisplayName("a flag that answered")
    class Answered {

        @Test
        void a_flag_read_as_on_should_exit_zero_and_say_so() {
            final int code = run(FlagDecision.ON);

            softly.assertThat(code)
                    .as("the flag answered, which is the whole of what this command was asked")
                    .isEqualTo(CliMain.SUCCESS);
            softly.assertThat(lines)
                    .as("one line, exact, because a runbook step greps it")
                    .containsExactly(ON_LINE);
        }

        @Test
        void a_flag_read_as_off_should_exit_zero_because_off_is_an_answer() {
            final int code = run(FlagDecision.OFF);

            softly.assertThat(code)
                    .as("a stack before cutover is meant to say off; a command that refused on it "
                            + "would fail every runbook step until somebody flipped the flag")
                    .isEqualTo(CliMain.SUCCESS);
            softly.assertThat(lines)
                    .as("and it says off rather than saying nothing, which is what tells the "
                            + "operator the store was reached at all")
                    .containsExactly(OFF_LINE);
        }
    }

    /**
     * Nobody could say whose turn it was, which is the reading the nightly run skips on.
     */
    @Nested
    @DisplayName("a flag that could not be read")
    class Unreadable {

        @ParameterizedTest
        @EnumSource(UnreadableReason.class)
        void an_unreadable_flag_should_exit_two_under_its_own_bounded_cause(
                final UnreadableReason cause) {

            final int code = run(new FlagDecision.Unreadable(cause));

            softly.assertThat(code)
                    .as("the nightly run would skip on this reading fail-closed, so the command "
                            + "that asks has to fail rather than report a quiet off: an operator "
                            + "told 'off' would believe the cutover simply had not happened yet")
                    .isEqualTo(CliMain.FAILED);
            softly.assertThat(lines)
                    .as("the cause is printed with the verdict, because an absent endpoint, a "
                            + "refused identity and a slow store are three different things to go "
                            + "and fix")
                    .containsExactly(UNREADABLE_LINE + cause.code());
        }

        @Test
        void an_unreadable_flag_should_not_be_reported_as_an_off_one() {
            run(new FlagDecision.Unreadable(UnreadableReason.ACCESS_DENIED));

            softly.assertThat(lines)
                    .as("an outage this pod rode out fail-closed is not the cutover working, and a "
                            + "line that said off would say it was")
                    .doesNotContain(OFF_LINE);
        }
    }

    /**
     * What is typed after the name, of which this command takes nothing.
     */
    @Nested
    @DisplayName("the arguments it takes")
    class Arguments {

        @Test
        void an_argument_this_command_does_not_take_should_be_refused_without_a_read() {
            final int code = run("--date", "2026-09-04");

            softly.assertThat(code)
                    .as("refused rather than failed: nothing was attempted and nothing is wrong "
                            + "with the flag, so a runbook must not retry this as an outage")
                    .isEqualTo(CliMain.REFUSED);
            softly.assertThat(lines)
                    .as("and no verdict is printed, because none was reached")
                    .noneMatch(line -> line.startsWith("flag="));
            verify(reader, never()).read();
        }

        @Test
        void asking_for_help_should_exit_zero_and_read_nothing() {
            final int code = run("--help");

            softly.assertThat(code)
                    .as("the one argument every command takes, and the one that does nothing; "
                            + "CliDispatchIT exits 0 on it inside the built image")
                    .isEqualTo(CliMain.SUCCESS);
            softly.assertThat(lines)
                    .as("usage says which command it is about, so an operator with five of them "
                            + "in a runbook can tell the answers apart")
                    .anyMatch(line -> line.contains(CliMain.CHECK_FLAG));
            verify(reader, never()).read();
        }
    }

    /**
     * When the flag is asked, and what may be printed once it has answered.
     */
    @Nested
    @DisplayName("reading the flag")
    class Reading {

        @Test
        void the_flag_should_be_read_once_per_invocation_and_never_remembered() {
            run(FlagDecision.ON);
            run(FlagDecision.ON);

            verify(reader, times(2)).read();
        }

        @Test
        void every_value_printed_should_be_a_bounded_code() {
            final List<String> printed = new ArrayList<>();

            printed.addAll(valuesOf(run(FlagDecision.ON)));
            printed.addAll(valuesOf(run(FlagDecision.OFF)));
            printed.addAll(valuesOf(run(new FlagDecision.Unreadable(UnreadableReason.MALFORMED))));

            softly.assertThat(printed)
                    .as("bounded codes and nothing else: the store's own words about itself are "
                            + "somebody else's text, and a terminal is no more a place for them "
                            + "than the log index is")
                    .isSubsetOf(vocabulary());
        }

        /**
         * The values the last invocation printed, which is everything after a {@code =}.
         *
         * @param code the exit code, taken so a red run fails on the reading as well as the values
         * @return every value the lines carried
         */
        private List<String> valuesOf(final int code) {
            softly.assertThat(code)
                    .as("a command that printed nothing at all would satisfy a subset assertion "
                            + "trivially")
                    .isNotEqualTo(NOT_RUN);
            final List<String> values = lines.stream()
                    .flatMap(line -> Stream.of(line.split(" ")))
                    .map(pair -> pair.substring(pair.indexOf('=') + 1))
                    .toList();
            lines.clear();
            return values;
        }

        private List<String> vocabulary() {
            return Stream.concat(
                            Stream.of("ON", "OFF", "UNREADABLE"),
                            Stream.of(UnreadableReason.values()).map(UnreadableReason::code))
                    .toList();
        }
    }

    /**
     * The flag was read and the destination refused the one line saying so.
     *
     * <p><strong>[A] characterisation, and the fifth command's answer to a question the other four
     * were defective on.</strong> The review gate found the report's refusal being caught by four
     * commands' own broad catches and reported as work that had failed. This command has no such
     * catch to get past: the reader it asks never throws, the only {@code catch} it has is the
     * parser's own {@link IllegalArgumentException}, and a destination that refuses the verdict
     * therefore leaves the command already. So this case passed on introduction and no
     * implementation follows it - what it states is the property the other four were changed to
     * have, over the command that has it by construction, so that a later {@code catch} added here
     * cannot quietly take it away.
     *
     * <p>Which matters as much here as anywhere: {@code check-flag} is the first step of a cutover
     * and a rollback, and an operator told the flag could not be read - when it was read, and said
     * ON - is an operator who stops a cutover that was ready to go.
     */
    @Nested
    @DisplayName("a report the destination refused")
    class AReportRefused {

        /** How many lines the destination takes, this command's whole report being one line. */
        private static final int TAKES_NOTHING = 0;

        /** What the boundary says of a line it could not write, in this service's own words. */
        private static final String NOT_WRITTEN =
                "a command's report line could not be written to standard output";

        /** Every line the report tried to write, the refused one included. */
        private final List<String> attempted = new ArrayList<>();

        /** The destination at the far end of a pipe nobody is reading any more. */
        private final Consumer<String> refuses = line -> {
            attempted.add(line);
            if (attempted.size() > TAKES_NOTHING) {
                throw new ReportNotWritten(NOT_WRITTEN, new IOException("Broken pipe"));
            }
        };

        @Test
        void a_refused_report_should_reach_the_caller_over_a_flag_that_was_read() {
            when(reader.read()).thenReturn(FlagDecision.ON);
            final CheckFlagCli refused = new CheckFlagCli(reader, refuses);

            softly.assertThatThrownBy(() -> refused.run(List.of()))
                    .as("the refusal reaches the caller, which is the one place it can be answered "
                            + "without a terminal: the destination has already refused a line, so "
                            + "a verdict written there fails the same way")
                    .isInstanceOf(ReportNotWritten.class);
            softly.assertThat(attempted)
                    .as("the verdict, and no second line saying the flag could not be read: it "
                            + "was read, and an operator told otherwise stops a cutover that was "
                            + "ready to go")
                    .containsExactly(ON_LINE);
            verify(reader).read();
            verifyNoMoreInteractions(reader);
        }
    }
}
