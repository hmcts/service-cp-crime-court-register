package uk.gov.hmcts.cp.courtregister.batch.cli;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import uk.gov.hmcts.cp.courtregister.application.FeatureFlagReader;
import uk.gov.hmcts.cp.courtregister.domain.FlagDecision;

/**
 * {@code check-flag}.
 *
 * <p>Reads {@code CourtRegisterService} once, with no cache, and prints what it said: {@code ON},
 * {@code OFF}, or {@code UNREADABLE} with the bounded reason it could not be read. It takes no
 * arguments and changes nothing, which is what makes it the first thing a cutover or a rollback
 * asks - the answer this pod would get at 18:00, from this pod, through the same reader the
 * nightly run uses.
 *
 * <p><strong>ON and OFF are both exit 0; unreadable is exit 2.</strong> A flag that says off is an
 * answer and a stack before cutover is meant to give it, so a runbook step that read a refusal into
 * it would fail every night until the flag was flipped. A flag that could not be read is a
 * different thing altogether: the store is unreachable, the identity was refused, or the value is
 * not the shape this service knows - and the nightly run would skip on it (fail-closed), so the
 * command that asks has to fail too rather than report a quiet {@code OFF}. The reason is printed
 * with it because an absent endpoint, a refused identity and a slow store are three different
 * things to go and fix.
 *
 * <p>Nothing about the answer is interpreted here, and nothing is written down: the reading's own
 * bounded code is what is printed, so the store's words about itself cannot reach an operator's
 * terminal by interpolation any more than they can reach the log (constitution Principle VII).
 *
 * <p>Nothing the command may be handed narrows what it asks, so the arguments it permits are none
 * at all: {@code --help} aside, anything typed after the name is a mistyped invocation and is
 * refused before the store is reached.
 */
public class CheckFlagCli {

    /** What the command takes, which is nothing, and what it says when asked. */
    private static final String USAGE = "usage: " + CliMain.CHECK_FLAG
            + " (no arguments; prints flag=ON|OFF|UNREADABLE, exit 0 for ON and OFF, 2 for "
            + "UNREADABLE)";

    /** The one key the verdict is printed under, which a runbook step greps for. */
    private static final String FLAG = "flag=";

    /** What the verdict says where nobody could read the flag, the cause following it. */
    private static final String UNREADABLE = "UNREADABLE reason=";

    /** The one lever's reader, asked once and never remembered. */
    private final FeatureFlagReader reader;

    /** Where the answer is written, one line per call. */
    private final Consumer<String> output;

    /**
     * Creates the command over the reader the nightly run uses and the operator's own stream.
     *
     * <p>The same reader, deliberately: the question this command answers is what this pod would
     * get at 18:00, and a second reader configured differently would answer a different one.
     *
     * @param flagReader the one lever's reader, which never throws
     * @param lines      where the answer is written, one line per call
     */
    public CheckFlagCli(final FeatureFlagReader flagReader, final Consumer<String> lines) {
        this.reader = flagReader;
        this.output = lines;
    }

    /**
     * Reads the flag and prints what it said.
     *
     * <p>The verdict is written from the reading's own type rather than from its {@code code()},
     * because the two are read by different audiences: the code labels a counter and a log line,
     * and this line is grepped by a runbook step. The unreadable cause is the code, since that is
     * the bounded value the store's own words were reduced to on the way in.
     *
     * @param args the arguments that followed {@code check-flag}, of which there are none
     * @return {@link CliMain#SUCCESS} where the flag was read as on or off, {@link CliMain#FAILED}
     *         where it could not be read, or {@link CliMain#REFUSED} where an argument it does not
     *         take was given
     */
    // PMD.OnlyOneReturn: the three exits are the three answers, each said where it is decided; one
    // exit would carry a verdict past the read that must not happen once an argument is refused.
    @SuppressWarnings("PMD.OnlyOneReturn")
    public int run(final List<String> args) {
        final Args parsed;
        try {
            parsed = Args.parse(args);
        } catch (IllegalArgumentException notUsable) {
            return CliMain.unreadable(CliMain.CHECK_FLAG, USAGE, notUsable, output);
        }
        if (parsed.askedForHelp()) {
            output.accept(USAGE);
            return CliMain.SUCCESS;
        }
        if (!parsed.permits(Set.of(), Set.of())) {
            return refuse(CliMain.UNEXPECTED_ARGUMENT);
        }
        return answered(reader.read());
    }

    /**
     * Prints one reading and says what the process should exit on.
     *
     * @param reading what the store answered, which is never an exception
     * @return {@link CliMain#SUCCESS} for a flag that answered, {@link CliMain#FAILED} for one
     *         nobody could read
     */
    private int answered(final FlagDecision reading) {
        return switch (reading) {
            case FlagDecision.Enabled ignored -> printed("ON", CliMain.SUCCESS);
            case FlagDecision.Disabled ignored -> printed("OFF", CliMain.SUCCESS);
            case FlagDecision.Unreadable unreadable ->
                printed(UNREADABLE + unreadable.reason().code(), CliMain.FAILED);
        };
    }

    /**
     * Declines, under the bounded reason and this command's own usage.
     *
     * @param reason one of {@link CliMain}'s three argument reasons
     * @return {@link CliMain#REFUSED}
     */
    private int refuse(final String reason) {
        return CliMain.refusal(CliMain.CHECK_FLAG, USAGE, reason, output);
    }

    /**
     * Writes the one line and hands back the code it belongs to.
     *
     * @param verdict what the flag said, as the bounded word this command prints
     * @param code    the exit code that answer carries
     * @return the code
     */
    private int printed(final String verdict, final int code) {
        output.accept(FLAG + verdict);
        return code;
    }
}
