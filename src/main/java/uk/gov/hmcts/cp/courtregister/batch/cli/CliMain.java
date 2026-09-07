package uk.gov.hmcts.cp.courtregister.batch.cli;

import java.time.Clock;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeansException;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import uk.gov.hmcts.cp.Application;
import uk.gov.hmcts.cp.courtregister.application.FeatureFlagReader;
import uk.gov.hmcts.cp.courtregister.application.RegisterGenerationService;
import uk.gov.hmcts.cp.courtregister.application.RegisterNotifierService;
import uk.gov.hmcts.cp.courtregister.application.RegisterStore;
import uk.gov.hmcts.cp.courtregister.batch.BatchAssembler;
import uk.gov.hmcts.cp.courtregister.batch.FeatureFlagGate;
import uk.gov.hmcts.cp.courtregister.config.CliModeConfig;
import uk.gov.hmcts.cp.courtregister.config.GenerationProperties;
import uk.gov.hmcts.cp.courtregister.persistence.RegisterBatchRepository;
import uk.gov.hmcts.cp.courtregister.persistence.RegisterNotificationRepository;

/**
 * The one entry point operations reach the service through, and the only one there is.
 *
 * <p>This service exposes no REST API, so regenerating a date, resending a batch's failed
 * recipients, listing what a date holds, superseding what was recorded before an instant and
 * reading the flag are all commands in the image rather than endpoints on the pod (FR-016,
 * constitution Principle III). {@code docker/startup.sh} dispatches here when its first argument is
 * one of the five names below and starts the application unchanged otherwise, so the tool runs with
 * the pod's own identity and network path and support needs no data-plane credential of its own
 * (research §13).
 *
 * <p>The context a command runs in is the deployed one with three things off:
 * {@code courtregister.cli=true} keeps the Service Bus consumer, the nightly scheduler and the
 * public-event listener container from starting, because a command that consumed a delivery or
 * fired the 18:00 run as a side effect would be a second cutover lever nobody asked for
 * ({@link uk.gov.hmcts.cp.courtregister.config.CliModeConfig}). Everything else - the generation
 * adapters above all - is live and configured exactly as the schedule has it, since a regeneration
 * that talked to a stub would be a night's registers nobody sent.
 *
 * <p>Dispatch is by name against a registry rather than by a chain of comparisons, so the names
 * this class knows and the names {@code startup.sh} recognises are one list stated once, and a
 * command added to one of them cannot be missing from the other.
 *
 * <p><strong>Three exit codes, and they mean different things.</strong> {@link #SUCCESS} is the
 * command did what it was asked; {@link #REFUSED} is the command declined to - the flag said off
 * and nobody passed {@code --ignore-flag}, or the arguments were not usable - and nothing was
 * changed by it; {@link #FAILED} is the command tried and could not. A runbook step that retried a
 * refusal as though it were a failure would be an operator overriding the cutover flag by accident,
 * which is why the two are not the same number.
 *
 * <p><strong>What a command prints is read by a person under pressure.</strong> Output is
 * line-oriented and stable, and carries bounded codes, counts and identifiers only: no defendant,
 * no register content, and recipient addresses masked wherever a command has cause to mention one
 * (constitution Principle VII). The stream is handed in rather than reached for, so a test reads
 * what an operator would see.
 */
public class CliMain {

    /** The command did what it was asked. */
    public static final int SUCCESS = 0;

    /**
     * The command declined, and changed nothing.
     *
     * <p>The flag did not say on and no override was passed, or the arguments were not usable.
     * Distinct from {@link #FAILED} because a refusal is the service working as intended and is not
     * something a runbook should retry.
     */
    public static final int REFUSED = 1;

    /** The command tried and could not finish. */
    public static final int FAILED = 2;

    /** Re-assembles and re-requests a register date, optionally one court house of it. */
    public static final String GENERATE_REGISTER = "generate-register";

    /** Re-requests the FAILED recipients of one batch, and only those. */
    public static final String NOTIFY_REGISTER = "notify-register";

    /** Lists a date's batches, or the records recorded while the flag was off. */
    public static final String LIST_BATCHES = "list-batches";

    /** Supersedes the records recorded before an instant, so a run stops picking them up. */
    public static final String SUPERSEDE_BEFORE = "supersede-before";

    /** Reads the one lever and says what it says. */
    public static final String CHECK_FLAG = "check-flag";

    /**
     * The five names, stated once.
     *
     * <p>{@code docker/startup.sh} recognises exactly these, and dispatch below accepts exactly
     * these: one list rather than two, so a command added to the image cannot be missing from the
     * script or the script's list from the registry.
     */
    public static final List<String> COMMANDS = List.of(GENERATE_REGISTER, NOTIFY_REGISTER,
            LIST_BATCHES, SUPERSEDE_BEFORE, CHECK_FLAG);

    /** A name this command does not take was given, whatever it was. */
    public static final String UNEXPECTED_ARGUMENT = "unexpected-argument";

    /** An argument this command cannot do without was not given. */
    public static final String MISSING_ARGUMENT = "missing-argument";

    /** An argument was given and its value is not one this command can use. */
    public static final String UNREADABLE_ARGUMENT = "unreadable-argument";

    private static final Logger LOG = LoggerFactory.getLogger(CliMain.class);

    /** What every command's report is keyed by, so a terminal's lines say which one wrote them. */
    private static final String COMMAND = "command=";

    /** The two verdicts a report line other than a command's own answer carries. */
    private static final String OUTCOME_REFUSED = " outcome=refused reason=";

    private static final String OUTCOME_FAILED = " outcome=failed reason=";

    /**
     * The property that turns off the three things a command must not bring up, passed as a
     * command-line property so that {@code application.yaml}'s {@code false} cannot win over it.
     */
    private static final String CLI_MODE = "--" + CliModeConfig.CLI_PROPERTY + "=true";

    /** What a command reports when this context could not be built at all. */
    private static final String NO_CONTEXT = "context-unavailable";

    /** What a command reports when the deployed context holds none of the beans it asks. */
    private static final String NOT_WIRED = "command-not-wired";

    /**
     * What the registry holds: one command, asked with the arguments that followed its name.
     *
     * <p>Shaped as the five command classes' own {@code run} so each can be registered as a method
     * reference, which is what keeps the registry a mapping of names to commands rather than a
     * second place a command's behaviour is described.
     */
    @FunctionalInterface
    public interface Command {

        /**
         * Runs the command over its own arguments.
         *
         * @param args the arguments that followed the command's name, in the order they were given
         * @return one of {@link CliMain#SUCCESS}, {@link CliMain#REFUSED} or {@link CliMain#FAILED}
         */
        int run(List<String> args);
    }

    /**
     * The process entry point: starts the CLI-mode context, runs the command and exits with its
     * code.
     *
     * <p>The name is checked before anything is started, so a typo costs a refusal rather than a
     * context: an operator who mistyped a command in a runbook step should get the list back at
     * once, not after the store, the file service and the flag have all been connected to.
     *
     * <p>The exit code is the whole interface between a command and the runbook step that ran it -
     * 0 did it, 1 declined, 2 could not - so it is set here rather than returned: a JVM that
     * returned from {@code main} exits 0 whatever the command answered.
     *
     * @param args the command name followed by its own arguments
     */
    public static void main(final String[] args) {
        System.exit(new CliMain().dispatch(args, System.out::println));
    }

    /**
     * Runs one invocation against a context of this service's own, and closes it either way.
     *
     * <p>Everything the deployed pod has, with {@link CliModeConfig#CLI_PROPERTY} on: the same
     * settings, the same startup refusals, the same live generation adapters, and no consumer, no
     * scheduler and no listener container. Not a web context - a command runs beside a pod that is
     * already serving the actuator on the port, and a second server would fail to bind rather than
     * do anything useful.
     *
     * <p>The operator's own arguments are deliberately <em>not</em> handed to Spring. They are the
     * command's, not the environment's, and a context that read {@code --batch} as a property would
     * be configured by whatever a support call happened to be about.
     *
     * @param args   the command name followed by its own arguments
     * @param output where the command's lines are written, one line per call
     * @return the exit code the process should end on
     */
    // PMD.AvoidCatchingGenericException: a context that will not start throws whatever the bean
    // that refused threw, and every one of them means the same thing here - this command could not
    // be run - so the operator is told that rather than shown a stack trace on their terminal.
    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn"})
    public int dispatch(final String[] args, final Consumer<String> output) {
        final String name = args == null || args.length == 0 ? null : args[0];
        if (!COMMANDS.contains(name)) {
            usage(output);
            return REFUSED;
        }
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(
                Application.class).web(WebApplicationType.NONE).run(CLI_MODE)) {
            return run(args, registryOf(context, output), output);
        } catch (RuntimeException notStarted) {
            LOG.error("The {} command could not be run because this service's own context would "
                    + "not start, so nothing was read, assembled or sent. cause={}", name,
                    notStarted.getClass().getName(), notStarted);
            return failure(name, "", NO_CONTEXT, output);
        }
    }

    /**
     * Dispatches one invocation and answers with the code the process should exit on.
     *
     * <p>Separate from {@link #main(String[])} so that the dispatch - an unknown name, a missing
     * name, the code a command answered with - is exercised without starting a context or ending a
     * JVM.
     *
     * @param args     the command name followed by its own arguments
     * @param registry the commands this invocation may reach, by the names above
     * @param output   where the command's lines are written, one line per call
     * @return the exit code, which is {@link #REFUSED} where the name is missing or unknown
     */
    // PMD.OnlyOneReturn: an unknown name is answered where it is recognised as one, and funnelling
    // it through the dispatch below would mean looking a null command up in the registry.
    @SuppressWarnings("PMD.OnlyOneReturn")
    public int run(final String[] args, final Map<String, Command> registry,
            final Consumer<String> output) {

        final Command command = args == null || args.length == 0 ? null : registry.get(args[0]);
        if (command == null) {
            usage(output);
            return REFUSED;
        }
        return command.run(List.of(Arrays.copyOfRange(args, 1, args.length)));
    }

    /**
     * A refusal, said the same way by every command.
     *
     * <p>{@link #REFUSED} and nothing changed: the reason is one of this class's three bounded
     * codes, and the command's own usage follows it so an operator mid-incident does not have to go
     * and find a runbook to see what it takes.
     *
     * @param command the command that declined, by its own name
     * @param usage   what it takes, printed under the refusal
     * @param reason  one of {@link #UNEXPECTED_ARGUMENT}, {@link #MISSING_ARGUMENT} or
     *                {@link #UNREADABLE_ARGUMENT}
     * @param output  where the two lines are written
     * @return {@link #REFUSED}
     */
    public static int refusal(final String command, final String usage, final String reason,
            final Consumer<String> output) {

        LOG.warn("The {} command was refused and changed nothing: its arguments were not usable. "
                + "reason={}", command, reason);
        output.accept(COMMAND + command + OUTCOME_REFUSED + reason);
        output.accept(usage);
        return REFUSED;
    }

    /**
     * A refusal over arguments the parser itself could not read.
     *
     * <p>Separate from {@link #refusal} only in that the parser's own refusal is written to the log
     * with it: what an operator typed is not the terminal's business twice over, and the throwable
     * is the only record of which token could not be read.
     *
     * @param command   the command that declined, by its own name
     * @param usage     what it takes, printed under the refusal
     * @param notUsable what the parser refused on
     * @param output    where the two lines are written
     * @return {@link #REFUSED}
     */
    public static int unreadable(final String command, final String usage,
            final RuntimeException notUsable, final Consumer<String> output) {

        LOG.warn("The {} command could not read what was typed after its name, so it changed "
                + "nothing. cause={}", command, notUsable.getClass().getName(), notUsable);
        return refusal(command, usage, UNREADABLE_ARGUMENT, output);
    }

    /**
     * A failure, said the same way by every command.
     *
     * <p>{@link #FAILED} rather than {@link #REFUSED}, because this is the one a runbook may retry.
     * The line carries the command, whatever identity the attempt was about and a bounded reason -
     * never the store's or the far end's own words, which are on the log line beside the throwable
     * and nowhere near an operator's terminal (constitution Principle VII).
     *
     * @param command the command that could not finish
     * @param subject the identity the attempt was about, as one {@code key=value} pair, or empty
     *                where there is none
     * @param reason  the bounded reason it could not
     * @param output  where the line is written
     * @return {@link #FAILED}
     */
    public static int failure(final String command, final String subject, final String reason,
            final Consumer<String> output) {

        final String about = subject.isEmpty() ? "" : " " + subject;
        output.accept(COMMAND + command + about + OUTCOME_FAILED + reason);
        return FAILED;
    }

    /**
     * The five names an operator may have meant, and nothing about the one they typed.
     *
     * <p>What was typed is not echoed. It is a string from outside this service, an operator's
     * terminal is pasted into tickets, and the list of what this image offers is the whole of what
     * a person who mistyped a command needs.
     *
     * @param output where the lines are written
     */
    private static void usage(final Consumer<String> output) {
        output.accept("usage: startup.sh <command> [arguments]");
        COMMANDS.forEach(command -> output.accept("  " + command));
    }

    /**
     * The five commands over this context's own beans, each resolved when it is run.
     *
     * <p>Resolved at the moment of running rather than while the registry is built, so that a
     * context which holds one command's collaborators and not another's can still run the one it
     * has: {@code check-flag} is the first step of a cutover and asks only the flag reader, and a
     * registry that had required the generation beans to exist would have refused it on the very
     * stack it is asked about.
     *
     * @param context the CLI-mode context
     * @param output  where every command's lines are written
     * @return the five commands, by name
     */
    private static Map<String, Command> registryOf(final ConfigurableApplicationContext context,
            final Consumer<String> output) {

        return Map.of(
                GENERATE_REGISTER, args -> wired(GENERATE_REGISTER, output,
                        () -> new GenerateRegisterCli(context.getBean(FeatureFlagGate.class),
                                context.getBean(RegisterStore.class),
                                context.getBean(BatchAssembler.class),
                                context.getBean(RegisterGenerationService.class),
                                context.getBean(GenerationProperties.class),
                                context.getBean(Clock.class), output).run(args)),
                NOTIFY_REGISTER, args -> wired(NOTIFY_REGISTER, output,
                        () -> new NotifyRegisterCli(
                                context.getBean(RegisterNotifierService.class), output).run(args)),
                LIST_BATCHES, args -> wired(LIST_BATCHES, output,
                        () -> new ListBatchesCli(context.getBean(RegisterBatchRepository.class),
                                context.getBean(RegisterNotificationRepository.class),
                                context.getBean(RegisterStore.class), output).run(args)),
                SUPERSEDE_BEFORE, args -> wired(SUPERSEDE_BEFORE, output,
                        () -> new SupersedeBeforeCli(context.getBean(RegisterStore.class), output)
                                .run(args)),
                CHECK_FLAG, args -> wired(CHECK_FLAG, output,
                        () -> new CheckFlagCli(context.getBean(FeatureFlagReader.class), output)
                                .run(args)));
    }

    /**
     * Runs one command, or says that this context does not hold it.
     *
     * <p>A deployment with the downstream half switched off has no batches to regenerate and no
     * flag reader to ask, and the honest answer to a command against it is {@link #FAILED} under a
     * bounded reason: a refusal would say the arguments were wrong, and a stack trace about a bean
     * definition would say nothing an operator can act on.
     *
     * @param command the command being run, by its own name
     * @param output  where the line is written where it could not be
     * @param run     the command, over the beans it asks for as it is built
     * @return whatever the command answered, or {@link #FAILED}
     */
    // PMD.OnlyOneReturn: the command's own answer and "not wired here" are two different verdicts,
    // and the second is only knowable in the catch.
    @SuppressWarnings("PMD.OnlyOneReturn")
    private static int wired(final String command, final Consumer<String> output,
            final IntSupplier run) {
        try {
            return run.getAsInt();
        } catch (BeansException notOnThisContext) {
            LOG.error("The {} command is not wired on this context, so nothing was read, assembled "
                    + "or sent: the downstream half is not deployed here. cause={}", command,
                    notOnThisContext.getClass().getName(), notOnThisContext);
            return failure(command, "", NOT_WIRED, output);
        }
    }
}
