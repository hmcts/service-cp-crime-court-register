package uk.gov.hmcts.cp.courtregister.batch.cli;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

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
     * @param args the command name followed by its own arguments
     */
    public static void main(final String[] args) {
        throw new UnsupportedOperationException("T065");
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
    public int run(final String[] args, final Map<String, Command> registry,
            final Consumer<String> output) {
        throw new UnsupportedOperationException("T065");
    }
}
