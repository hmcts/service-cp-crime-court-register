package uk.gov.hmcts.cp.courtregister.batch.cli;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gov.hmcts.cp.courtregister.application.FeatureFlagReader;
import uk.gov.hmcts.cp.courtregister.application.RegisterGenerationService;
import uk.gov.hmcts.cp.courtregister.application.RegisterNotifierService;
import uk.gov.hmcts.cp.courtregister.application.RegisterStore;
import uk.gov.hmcts.cp.courtregister.batch.BatchAssembler;
import uk.gov.hmcts.cp.courtregister.batch.FeatureFlagGate;
import uk.gov.hmcts.cp.courtregister.config.GenerationProperties;
import uk.gov.hmcts.cp.courtregister.config.GenerationProperties.SourceMode;
import uk.gov.hmcts.cp.courtregister.persistence.RegisterBatchRepository;
import uk.gov.hmcts.cp.courtregister.persistence.RegisterNotificationRepository;
import uk.gov.hmcts.cp.courtregister.support.CapturedLog;

/**
 * The one entry point operations reach this service through: what it dispatches, and what it says.
 *
 * <p><strong>[A] characterisation.</strong> {@link CliMain} already behaves as every case here
 * says, and this suite states that behaviour rather than driving it: it passed on introduction and
 * no implementation commit follows it. It exists because the five command suites each hold their
 * own command down and none of them holds the thing in front of them down at all - the five names
 * an image carries, the refusal an unknown name is answered with, the code a command's answer
 * becomes, and the two ways a report line may be written. Those are the parts a runbook step
 * actually depends on, and until now a change to any of them would have gone red nowhere.
 *
 * <p><strong>The exit code is the whole interface between a command and the step that ran
 * it.</strong> 0 did it, 1 declined and changed nothing, 2 tried and could not, and the three are
 * asserted as those numbers rather than as the constants alone: the script exits 2 of its own
 * accord when it cannot find the jar to run a command from, and a runbook that retried a refusal
 * as though it were a failure would be an operator overriding the cutover flag by accident. A
 * command's own answer is therefore handed back unchanged, whichever of the three it is.
 *
 * <p><strong>A mistyped name costs a refusal, not a context.</strong> The name is checked before
 * anything is started, so the case below reads the list back without a store, a file service or a
 * flag ever having been connected to - and what was typed is not echoed, because an operator's
 * terminal is pasted into tickets and the list of what this image offers is the whole of what a
 * person who mistyped needs.
 *
 * <p><strong>What reaches the terminal is bounded, and the stack trace is the log's
 * business.</strong> The refusal, the parser refusal and the failure are the three shapes every
 * command reports through, so they are asserted here once: a bounded reason, the identity the
 * attempt was about, the command's own usage under a refusal - and never the thrower's words or a
 * stack trace, which the cases below find in the log instead (constitution Principle VII). The
 * stream is handed in rather than reached for, and the last group holds that down by reading what
 * {@link System#out} carried while a command wrote.
 *
 * <p>{@code CliDispatchIT} (T067) is the same claim end to end inside the built image, and
 * {@link ArgsTest} is the grammar underneath every {@code --help} case here.
 */
@ExtendWith(SoftAssertionsExtension.class)
@DisplayName("the operations entry point")
class CliMainTest {

    /** The first line of the usage every unknown name is answered with. */
    private static final String USAGE = "usage: startup.sh <command> [arguments]";

    /** A name close enough to a real one to be a plausible typo in a runbook step. */
    private static final String MISTYPED = "genrate-register";

    /** The register date a regeneration is asked for, as an operator types it. */
    private static final String TYPED_DATE = "2026-08-20";

    /** One batch, as the subject a failure line carries. */
    private static final UUID BATCH = UUID.fromString("6f1d0a4c-1c2b-4f8e-9a3d-70b2c5e41a01");

    /** How a shell {@code case} lists the names it dispatches on, and nothing else does. */
    private static final Pattern CASE_LABEL = Pattern.compile("^([a-z-]+\\|)+[a-z-]+\\)$");

    /** This service's own package, whose every line the stack-trace case reads. */
    private static final String SERVICE_PACKAGE = "uk.gov.hmcts.cp.courtregister";

    /** Stood in for an exit code an invocation threw instead of answering with. */
    private static final int NOT_ANSWERED = -1;

    /** What an operator's terminal shows, one entry per line a command wrote. */
    private final List<String> printed = new ArrayList<>();

    private final Consumer<String> output = printed::add;

    /** The commands that were run, in the order they were run. */
    private final List<String> asked = new ArrayList<>();

    /** What each command was handed after its own name. */
    private final Map<String, List<String>> handed = new LinkedHashMap<>();

    private final CliMain cli = new CliMain();

    @InjectSoftAssertions
    private SoftAssertions softly;

    /**
     * The five names, each registered to a command that records what it was asked and answers the
     * given code.
     *
     * @param code what every command in this registry answers with
     * @return the registry one invocation may reach
     */
    private Map<String, CliMain.Command> registryAnswering(final int code) {
        final Map<String, CliMain.Command> registry = new LinkedHashMap<>();
        CliMain.COMMANDS.forEach(name -> registry.put(name, args -> recorded(name, args, code)));
        return registry;
    }

    /**
     * The five names, each registered to a command that throws rather than answering.
     *
     * @param failure what the command throws when it is run
     * @return the registry one invocation may reach
     */
    private static Map<String, CliMain.Command> registryThrowing(final RuntimeException failure) {
        final Map<String, CliMain.Command> registry = new LinkedHashMap<>();
        CliMain.COMMANDS.forEach(name -> registry.put(name, args -> {
            throw failure;
        }));
        return registry;
    }

    private int recorded(final String name, final List<String> args, final int code) {
        asked.add(name);
        handed.put(name, args);
        return code;
    }

    /**
     * The names {@code docker/startup.sh} dispatches on, read out of the script itself.
     *
     * @return the labels of its one {@code case}, split on the shell's own alternation
     * @throws IOException where the script cannot be read
     */
    private static List<String> namesTheScriptDispatchesOn() throws IOException {
        return Files.readAllLines(Path.of("docker", "startup.sh"), StandardCharsets.UTF_8).stream()
                .map(String::strip)
                .filter(line -> CASE_LABEL.matcher(line).matches())
                .flatMap(line -> Stream.of(line.substring(0, line.length() - 1).split("\\|")))
                .toList();
    }

    /**
     * The five names it dispatches by, which are also the five the image's script recognises.
     */
    @Nested
    @DisplayName("the five names, stated once")
    class Names {

        @Test
        void the_registry_should_carry_exactly_the_five_names_operations_has() {
            softly.assertThat(CliMain.COMMANDS)
                    .as("the names are a published interface: they are typed into runbook steps "
                            + "and matched by the image's own script, so one being renamed or "
                            + "dropped is a change to what support can do at 18:30")
                    .containsExactly("generate-register", "notify-register", "list-batches",
                            "supersede-before", "check-flag");
            softly.assertThat(CliMain.COMMANDS)
                    .as("and each is reachable as the constant the commands report themselves by")
                    .containsExactly(CliMain.GENERATE_REGISTER, CliMain.NOTIFY_REGISTER,
                            CliMain.LIST_BATCHES, CliMain.SUPERSEDE_BEFORE, CliMain.CHECK_FLAG);
        }

        @Test
        void the_script_that_dispatches_should_recognise_the_same_five_and_no_others()
                throws IOException {

            softly.assertThat(namesTheScriptDispatchesOn())
                    .as("one list rather than two: a name the script does not recognise starts the "
                            + "application instead of running a command, and a name this class "
                            + "does not know is answered with a refusal - so a sixth command "
                            + "missing from either side is a command that silently does nothing")
                    .containsExactlyInAnyOrderElementsOf(CliMain.COMMANDS);
        }

        @Test
        void the_registry_should_hold_no_name_twice_and_none_that_is_not_a_command() {
            softly.assertThat(CliMain.COMMANDS)
                    .as("dispatch is by name against this list, so a duplicate would be a name "
                            + "with two meanings")
                    .doesNotHaveDuplicates()
                    .allMatch(name -> name.matches("[a-z]+(-[a-z]+)+"),
                            "a name a shell case label and a runbook step can both carry");
        }
    }

    /**
     * A name this image does not carry, and the list a person who mistyped is given back.
     */
    @Nested
    @DisplayName("a name it does not know")
    class UnknownName {

        @Test
        void a_name_this_image_does_not_carry_should_be_refused_with_the_five_names() {
            final int code = cli.run(new String[] {MISTYPED}, registryAnswering(CliMain.SUCCESS),
                    output);

            softly.assertThat(code)
                    .as("refused rather than failed: nothing was attempted, so a runbook step must "
                            + "not retry it as an outage")
                    .isEqualTo(CliMain.REFUSED);
            softly.assertThat(printed)
                    .as("the list of what this image offers is the whole of what a person who "
                            + "mistyped a command needs")
                    .containsExactlyElementsOf(usageLines());
            softly.assertThat(asked)
                    .as("and no command ran, which is what makes the refusal safe to retype")
                    .isEmpty();
        }

        @Test
        void no_command_name_at_all_should_be_refused_with_the_five_names() {
            final int code = cli.run(new String[0], registryAnswering(CliMain.SUCCESS), output);

            softly.assertThat(code)
                    .as("a step that ran the tool with its argument unset asked for nothing, and "
                            + "the answer is the list rather than a guess at which command was "
                            + "meant")
                    .isEqualTo(CliMain.REFUSED);
            softly.assertThat(printed)
                    .as("the same five names, said the same way")
                    .containsExactlyElementsOf(usageLines());
        }

        @Test
        void a_missing_argument_array_should_be_refused_the_same_way() {
            final int code = cli.run((String[]) null, registryAnswering(CliMain.SUCCESS), output);

            softly.assertThat(code)
                    .as("nothing at all is not a command either, and a refusal is the answer that "
                            + "changes nothing")
                    .isEqualTo(CliMain.REFUSED);
            softly.assertThat(printed)
                    .as("and it is answered rather than thrown on, because the exit code is the "
                            + "whole interface and 1 is not what a JVM gives an uncaught throwable")
                    .containsExactlyElementsOf(usageLines());
        }

        @Test
        void what_was_typed_should_not_be_echoed_back() {
            cli.run(new String[] {MISTYPED, "--date", TYPED_DATE}, registryAnswering(
                    CliMain.SUCCESS), output);

            softly.assertThat(printed)
                    .as("a string from outside this service, on a terminal that is pasted into "
                            + "tickets: what was typed is not echoed, and the arguments beside it "
                            + "are not either")
                    .noneMatch(line -> line.contains(MISTYPED) || line.contains(TYPED_DATE));
        }

        @Test
        void a_mistyped_name_should_be_answered_before_a_context_is_asked_for() {
            final int code = cli.dispatch(new String[] {MISTYPED}, output);

            softly.assertThat(code)
                    .as("the name is checked before anything is started: an operator who mistyped "
                            + "a command in a runbook step gets the list back at once, not after "
                            + "the store, the file service and the flag have all been connected to")
                    .isEqualTo(CliMain.REFUSED);
            softly.assertThat(printed)
                    .as("and the answer is the usage rather than this service's own context "
                            + "having been built and refused")
                    .containsExactlyElementsOf(usageLines());
        }

        @Test
        void no_command_name_at_all_should_be_answered_by_the_process_entry_point_too() {
            final int code = dispatched();

            softly.assertThat(code)
                    .as("`startup.sh` only reaches this with a name, but a person debugging a "
                            + "runbook step reaches it by hand and with the variable unset: the "
                            + "answer is the refusal and the five names, said once here and once "
                            + "in run(), because a stack trace out of main ends the process on 1 - "
                            + "the code that means declined - having printed no list at all")
                    .isEqualTo(CliMain.REFUSED);
            softly.assertThat(printed)
                    .as("and the list is what a person with nothing to go on needs")
                    .containsExactlyElementsOf(usageLines());
        }

        @Test
        void a_missing_argument_array_should_be_answered_by_it_the_same_way() {
            final int code = dispatched((String[]) null);

            softly.assertThat(code)
                    .as("nothing at all is not a command, and the refusal is what changes nothing")
                    .isEqualTo(CliMain.REFUSED);
            softly.assertThat(printed)
                    .as("said the same way as every other name this image does not carry")
                    .containsExactlyElementsOf(usageLines());
        }

        /**
         * Dispatches an invocation that names no command, and answers with the code it ended on.
         *
         * <p>The call is made inside {@code assertThatCode(...).doesNotThrowAnyException()} so that
         * an invocation which threw is recorded as an assertion rather than ending the case, and
         * the code it never produced is then asserted on as {@link #NOT_ANSWERED}.
         *
         * @param args the invocation, which in both cases here names nothing
         * @return the exit code, or {@link #NOT_ANSWERED} where it threw instead
         */
        private int dispatched(final String... args) {
            final AtomicInteger code = new AtomicInteger(NOT_ANSWERED);
            softly.assertThatCode(() -> code.set(cli.dispatch(args, output)))
                    .as("the exit code is the whole interface between a command and the step that "
                            + "ran it, so an invocation this one cannot serve is answered rather "
                            + "than thrown on")
                    .doesNotThrowAnyException();
            return code.get();
        }

        /**
         * The usage an unknown name is answered with, stated once.
         *
         * @return the header and the five names, in the order they are printed
         */
        private List<String> usageLines() {
            return Stream.concat(Stream.of(USAGE), CliMain.COMMANDS.stream().map(name -> "  "
                    + name)).toList();
        }
    }

    /**
     * What a command answered, and what the invocation did with it.
     */
    @Nested
    @DisplayName("the code a command answered with")
    class Codes {

        @ParameterizedTest
        @ValueSource(ints = {CliMain.SUCCESS, CliMain.REFUSED, CliMain.FAILED})
        void a_command_s_own_code_should_be_handed_back_unchanged(final int answer) {
            final int code = cli.run(new String[] {CliMain.CHECK_FLAG}, registryAnswering(answer),
                    output);

            softly.assertThat(code)
                    .as("the command decided, and this class carries the decision: a dispatch that "
                            + "normalised a refusal or a failure into a success would tell a "
                            + "runbook step that a night's registers had gone out")
                    .isEqualTo(answer);
        }

        @Test
        void the_three_codes_should_stay_the_three_numbers_a_runbook_step_reads() {
            softly.assertThat(CliMain.SUCCESS)
                    .as("0 is the only code a shell reads as success, and the script execs the JVM "
                            + "so the process ends on exactly this number")
                    .isZero();
            softly.assertThat(CliMain.REFUSED)
                    .as("1 declined and changed nothing, which a runbook must never retry")
                    .isOne();
            softly.assertThat(CliMain.FAILED)
                    .as("2 tried and could not, which is the one it may - and the same 2 the "
                            + "script exits when it cannot find the jar to run the command from")
                    .isEqualTo(2);
        }

        @Test
        void only_the_command_that_was_named_should_run() {
            cli.run(new String[] {CliMain.LIST_BATCHES}, registryAnswering(CliMain.SUCCESS),
                    output);

            softly.assertThat(asked)
                    .as("one name, one command: an invocation that reached a second would have "
                            + "read, assembled or sent something nobody asked for")
                    .containsExactly(CliMain.LIST_BATCHES);
        }

        @Test
        void the_arguments_after_the_name_should_be_handed_over_in_the_order_they_were_given() {
            cli.run(new String[] {CliMain.GENERATE_REGISTER, "--date", TYPED_DATE, "--ignore-flag"},
                    registryAnswering(CliMain.SUCCESS), output);

            softly.assertThat(handed.get(CliMain.GENERATE_REGISTER))
                    .as("the command's own arguments, in the order they were typed, and without "
                            + "the name it was dispatched on - which is not one of them")
                    .containsExactly("--date", TYPED_DATE, "--ignore-flag");
        }

        @Test
        void a_command_asked_with_nothing_after_its_name_should_be_handed_an_empty_list() {
            cli.run(new String[] {CliMain.CHECK_FLAG}, registryAnswering(CliMain.SUCCESS), output);

            softly.assertThat(handed.get(CliMain.CHECK_FLAG))
                    .as("check-flag takes nothing, and an empty list is what it parses: a null "
                            + "here would be an exception in every command's first line")
                    .isEmpty();
        }
    }

    /**
     * A command that threw rather than answering, and what an operator is told about it.
     */
    @Nested
    @DisplayName("a command that could not answer")
    class Unexpected {

        @Test
        void an_unexpected_throw_should_not_become_a_success() {
            final IllegalStateException blewUp = new IllegalStateException("no route to host");

            softly.assertThatThrownBy(() -> cli.run(new String[] {CliMain.NOTIFY_REGISTER},
                            registryThrowing(blewUp), output))
                    .as("the dispatch does not answer for a command that threw: it is caught one "
                            + "level up, where the whole invocation - context and command alike - "
                            + "is reported as FAILED under a bounded reason. The one thing neither "
                            + "level may do is turn a command that did nothing into an exit 0")
                    .isSameAs(blewUp);
            softly.assertThat(printed)
                    .as("and nothing was printed on the way out, so no half-written report says "
                            + "something happened")
                    .isEmpty();
        }

        @Test
        void the_failure_a_throw_is_reported_as_should_carry_a_bounded_reason_and_no_stack_trace() {
            final int code = CliMain.failure(CliMain.GENERATE_REGISTER, "batch=" + BATCH,
                    "generation-failed", output);

            softly.assertThat(code)
                    .as("failed rather than refused, because this is the one a runbook may retry")
                    .isEqualTo(CliMain.FAILED);
            softly.assertThat(printed)
                    .as("the command, the identity the attempt was about and a bounded reason - "
                            + "one line, and every value in it something a person can act on")
                    .containsExactly("command=generate-register batch=" + BATCH
                            + " outcome=failed reason=generation-failed");
        }

        @Test
        void a_failure_about_nothing_in_particular_should_not_leave_a_gap_where_a_subject_was() {
            CliMain.failure(CliMain.CHECK_FLAG, "", "context-unavailable", output);

            softly.assertThat(printed)
                    .as("a line a runbook step greps is one shape whether the attempt was about an "
                            + "identity or not, so an absent subject is absent rather than blank")
                    .containsExactly(
                            "command=check-flag outcome=failed reason=context-unavailable");
        }
    }

    /**
     * How every command says it declined, which is the same way.
     */
    @Nested
    @DisplayName("how a command declines")
    class Declining {

        /** One command's usage, as the command itself would pass it. */
        private static final String COMMAND_USAGE = "usage: check-flag (no arguments)";

        @Test
        void a_refusal_should_say_which_command_declined_why_and_what_it_takes() {
            final int code = CliMain.refusal(CliMain.CHECK_FLAG, COMMAND_USAGE,
                    CliMain.UNEXPECTED_ARGUMENT, output);

            softly.assertThat(code)
                    .as("declined and changed nothing")
                    .isEqualTo(CliMain.REFUSED);
            softly.assertThat(printed)
                    .as("the reason above the usage, so an operator mid-incident does not have to "
                            + "go and find a runbook to see what the command takes")
                    .containsExactly(
                            "command=check-flag outcome=refused reason=unexpected-argument",
                            COMMAND_USAGE);
        }

        @Test
        void a_parser_refusal_should_be_said_the_same_way_under_its_own_reason() {
            final int code = CliMain.unreadable(CliMain.CHECK_FLAG, COMMAND_USAGE,
                    new IllegalArgumentException("--date was given more than once"), output);

            softly.assertThat(code)
                    .as("the parser could not read what was typed, which is still a refusal: "
                            + "nothing was read, assembled or sent")
                    .isEqualTo(CliMain.REFUSED);
            softly.assertThat(printed)
                    .as("one bounded reason for every unreadable token, so the three argument "
                            + "reasons stay a vocabulary rather than a message")
                    .containsExactly(
                            "command=check-flag outcome=refused reason=unreadable-argument",
                            COMMAND_USAGE);
        }

        @Test
        void the_parser_s_own_words_should_reach_the_log_and_not_the_terminal() {
            final IllegalArgumentException notUsable =
                    new IllegalArgumentException("an argument is written --name, and this one is "
                            + "not: yot.coordinator@example.gov.uk");

            try (CapturedLog log = CapturedLog.everythingAndAllOf(SERVICE_PACKAGE)) {
                CliMain.unreadable(CliMain.NOTIFY_REGISTER, COMMAND_USAGE, notUsable, output);

                softly.assertThat(printed)
                        .as("what an operator typed is not the terminal's business twice over, and "
                                + "a token they mistyped may be anything at all - an address among "
                                + "them (constitution Principle VII)")
                        .noneMatch(line -> line.contains("example.gov.uk"));
                softly.assertThat(printed)
                        .as("and no stack trace: the terminal carries codes, counts and "
                                + "identifiers, and a trace on it is a line an operator has to "
                                + "scroll past to find the verdict")
                        .noneMatch(line -> line.contains("java.lang") || line.contains("\tat "));
                softly.assertThat(log.renderings())
                        .as("the throwable is the only record of which token could not be read, so "
                                + "it is written down rather than swallowed - in the log, where a "
                                + "diagnosis reads it")
                        .anyMatch(line -> line.contains(notUsable.getClass().getName()));
            }
        }

        @Test
        void the_three_argument_reasons_should_be_bounded_codes_and_three_different_ones() {
            final List<String> reasons = List.of(CliMain.UNEXPECTED_ARGUMENT,
                    CliMain.MISSING_ARGUMENT, CliMain.UNREADABLE_ARGUMENT);

            softly.assertThat(reasons)
                    .as("a report line is grepped and a metric is labelled by these, so each is a "
                            + "bounded code rather than a sentence")
                    .allMatch(reason -> reason.matches("[a-z]+(-[a-z]+)+"))
                    .doesNotHaveDuplicates();
        }
    }

    /**
     * The one argument every command takes, asked of all five of them at once.
     */
    @Nested
    @DisplayName("asking a command what it takes")
    class AskingForHelp {

        private final FeatureFlagGate gate = mock(FeatureFlagGate.class);
        private final RegisterStore store = mock(RegisterStore.class);
        private final BatchAssembler assembler = mock(BatchAssembler.class);
        private final RegisterGenerationService generation =
                mock(RegisterGenerationService.class);
        private final RegisterNotifierService notifier = mock(RegisterNotifierService.class);
        private final RegisterBatchRepository batches = mock(RegisterBatchRepository.class);
        private final RegisterNotificationRepository notifications =
                mock(RegisterNotificationRepository.class);
        private final FeatureFlagReader reader = mock(FeatureFlagReader.class);

        /**
         * The five commands over doubled collaborators, by the names the registry knows them by.
         *
         * <p>Built the way {@code CliMain}'s own registry builds them - one command per name, over
         * whatever this context holds - so a name added to {@link CliMain#COMMANDS} without a
         * command behind it fails the first case below rather than being quietly untested.
         *
         * @return the five commands, by name
         */
        private Map<String, CliMain.Command> commands() {
            final Map<String, CliMain.Command> registry = new LinkedHashMap<>();
            registry.put(CliMain.GENERATE_REGISTER, new GenerateRegisterCli(gate, store, assembler,
                    generation, settings(), Clock.fixed(Instant.parse("2026-08-21T07:00:00Z"),
                    ZoneOffset.UTC), output)::run);
            registry.put(CliMain.NOTIFY_REGISTER, new NotifyRegisterCli(notifier, output)::run);
            registry.put(CliMain.LIST_BATCHES,
                    new ListBatchesCli(batches, notifications, store, output)::run);
            registry.put(CliMain.SUPERSEDE_BEFORE, new SupersedeBeforeCli(store, output)::run);
            registry.put(CliMain.CHECK_FLAG, new CheckFlagCli(reader, output)::run);
            return registry;
        }

        /**
         * The settings a deployed command works to, which are the ones {@code application.yaml}
         * ships.
         *
         * @return generation enabled, at the court's hour, in the court's zone
         */
        private static GenerationProperties settings() {
            return new GenerationProperties(true, "0 0 18 * * MON-FRI", "Europe/London", false,
                    Duration.ofMinutes(60), Duration.ofMinutes(70), Duration.ofMinutes(10),
                    GenerationProperties.COMPLETION_EVENT, SourceMode.LIVE, SourceMode.LIVE,
                    SourceMode.LIVE, SourceMode.LIVE);
        }

        @Test
        void every_command_should_answer_help_with_its_own_usage_line_and_exit_zero() {
            final Map<String, CliMain.Command> registry = commands();

            softly.assertThat(registry.keySet())
                    .as("all five, so the loop below is about the image's whole surface")
                    .containsExactlyElementsOf(CliMain.COMMANDS);
            CliMain.COMMANDS.forEach(name -> {
                printed.clear();
                final int code = cli.run(new String[] {name, "--help"}, registry, output);

                softly.assertThat(code)
                        .as("the one argument every command takes, and the one that does nothing: "
                                + "T067 exits 0 on generate-register --help inside the built image")
                        .isEqualTo(CliMain.SUCCESS);
                softly.assertThat(printed)
                        .as("usage says which command it is about, so an operator with five of "
                                + "them in a runbook can tell the answers apart")
                        .anyMatch(line -> line.startsWith("usage: " + name));
            });
        }

        @Test
        void asking_what_a_command_takes_should_reach_none_of_its_collaborators() {
            final Map<String, CliMain.Command> registry = commands();

            CliMain.COMMANDS.forEach(name -> cli.run(new String[] {name, "--help"}, registry,
                    output));

            verifyNoInteractions(gate, store, assembler, generation, notifier, batches,
                    notifications, reader);
        }
    }

    /**
     * Where a command's lines go, which is the one stream it was handed.
     */
    @Nested
    @DisplayName("where the lines go")
    class WhereTheLinesGo {

        private final ByteArrayOutputStream captured = new ByteArrayOutputStream();
        private final PrintStream capturing = new PrintStream(captured, true,
                StandardCharsets.UTF_8);
        private PrintStream restored;

        @BeforeEach
        void takeTheProcessStreamOver() {
            restored = System.out;
            System.setOut(capturing);
        }

        @AfterEach
        void giveItBack() {
            System.setOut(restored);
            capturing.close();
        }

        @Test
        void a_report_line_should_reach_the_consumer_it_was_handed_and_nowhere_else() {
            cli.run(new String[] {MISTYPED}, registryAnswering(CliMain.SUCCESS), output);
            CliMain.refusal(CliMain.CHECK_FLAG, "usage: check-flag (no arguments)",
                    CliMain.UNEXPECTED_ARGUMENT, output);
            CliMain.failure(CliMain.CHECK_FLAG, "", "context-unavailable", output);

            final String stream = captured.toString(StandardCharsets.UTF_8);
            softly.assertThat(printed)
                    .as("every line the three shapes wrote reached the consumer")
                    .hasSizeGreaterThan(1);
            softly.assertThat(printed)
                    .as("and none of them reached the process stream: it is handed in rather than "
                            + "reached for, so a test reads exactly what an operator would see and "
                            + "a command cannot write anywhere else. main is the one place "
                            + "System.out is named at all")
                    .noneMatch(stream::contains);
        }
    }
}
