package uk.gov.hmcts.cp.courtregister.batch.cli;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.courtregister.application.RegisterStore;

/**
 * {@code supersede-before --shared-before T}.
 *
 * <p>The rollback lever's other half. Records that were recorded before an instant are marked
 * superseded, so no run - the schedule's or an operator's - will ever batch them again. It is what
 * is used when the legacy has taken a period back over: the registers this service recorded for
 * those hearings are about to be sent by something else, and a document sent twice to a Youth
 * Offending Team is worse than one sent once by the older system.
 *
 * <p>Supersession rather than deletion, and never a rewrite: the rows stay, carrying what was
 * recorded and when, because the register store is the audit of what this service decided and a
 * rollback is exactly the moment that audit is needed. A superseded row is not an error and not a
 * duplicate; it is a register this service no longer claims.
 *
 * <p>The instant is required and is never defaulted to now. An operator who meant "everything
 * recorded before the cutover was rolled back" and got "everything recorded up to this second" has
 * superseded the hearings that arrived while they were typing.
 *
 * <p>The bound printed back is the instant this command parsed rather than the text it was handed,
 * so an operator can see which period they actually superseded and no string from outside this
 * service reaches the terminal on the way.
 */
public class SupersedeBeforeCli {

    /**
     * What the command takes, printed under a refusal and on request.
     *
     * <p>Package-visible because {@link CliMain} answers {@code --help} with it before it resolves
     * a single bean: a pod with the downstream half switched off holds none of this command's
     * collaborators, and what the command takes is still the answer to what was asked.
     */
    /* default */ static final String USAGE = "usage: " + CliMain.SUPERSEDE_BEFORE + " --"
            + Args.SHARED_BEFORE + " T (an ISO instant with a zone, e.g. 2026-09-04T17:00:00Z)";

    private static final Logger LOG = LoggerFactory.getLogger(SupersedeBeforeCli.class);

    /** What this command could not finish, as the bounded reason the line carries. */
    private static final String NOT_SUPERSEDED = "supersession-failed";

    /**
     * The register store, whose {@code supersedeSharedBefore} is the whole of what this command
     * does.
     *
     * <p>Which rows a period holds - RECORDED, unsuperseded, unbatched - is the port's predicate
     * and not this command's. All the command decides is the bound, and it decides it from what was
     * typed.
     */
    private final RegisterStore store;

    /** Where the count is written, one line per call. */
    private final Consumer<String> output;

    /**
     * Creates the command over the register store and the operator's own stream.
     *
     * @param registerStore the store whose {@code supersedeSharedBefore} this command asks
     * @param lines         where the count is written, one line per call
     */
    public SupersedeBeforeCli(final RegisterStore registerStore, final Consumer<String> lines) {
        this.store = registerStore;
        this.output = lines;
    }

    /**
     * Supersedes the records recorded before the instant the arguments name.
     *
     * @param args the arguments that followed {@code supersede-before}
     * @return {@link CliMain#SUCCESS}, {@link CliMain#REFUSED} where the instant was missing or
     *         unusable, or {@link CliMain#FAILED}
     */
    // PMD.OnlyOneReturn: the four exits are the four things that can happen to an invocation, each
    // said where it is decided; one exit would carry a verdict past the write that must not be
    // asked for once the bound has been refused.
    @SuppressWarnings("PMD.OnlyOneReturn")
    public int run(final List<String> args) {
        final Args parsed;
        try {
            parsed = Args.parse(args);
        } catch (IllegalArgumentException notUsable) {
            return CliMain.unreadable(CliMain.SUPERSEDE_BEFORE, USAGE, notUsable, output);
        }
        if (parsed.askedForHelp()) {
            output.accept(USAGE);
            return CliMain.SUCCESS;
        }
        if (!parsed.permits(Set.of(Args.SHARED_BEFORE), Set.of())) {
            return refuse(CliMain.UNEXPECTED_ARGUMENT);
        }
        final String typed = parsed.options().get(Args.SHARED_BEFORE);
        if (typed == null) {
            return refuse(CliMain.MISSING_ARGUMENT);
        }
        final Instant sharedBefore;
        try {
            sharedBefore = Instant.parse(typed);
        } catch (DateTimeParseException notAnInstant) {
            return CliMain.unreadable(CliMain.SUPERSEDE_BEFORE, USAGE, notAnInstant, output);
        }
        return supersede(sharedBefore);
    }

    /**
     * Supersedes the period and reports the count, or says the write could not be made.
     *
     * <p>A period that held nothing is a success said as a count: a rollback with nothing to do is
     * a rollback that is already complete, and a runbook step that failed on it would stop one.
     *
     * @param sharedBefore the exclusive bound the operator typed, as this command read it
     * @return {@link CliMain#SUCCESS} where the write was made, {@link CliMain#FAILED} where it was
     *         not
     */
    // PMD.AvoidCatchingGenericException: the store translates an outage into its own unchecked type
    // and a refused statement arrives as another; both mean the same thing here - the period was
    // not superseded - and a count printed over either would be a rollback reported as done.
    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn"})
    private int supersede(final Instant sharedBefore) {
        try {
            final int superseded = store.supersedeSharedBefore(sharedBefore);
            output.accept("superseded=" + superseded + " shared-before=" + sharedBefore);
            return CliMain.SUCCESS;
        } catch (RuntimeException notSuperseded) {
            LOG.error("The registers shared before {} could not be superseded, so this service "
                    + "still claims them. cause={}", sharedBefore,
                    notSuperseded.getClass().getName(), notSuperseded);
            return CliMain.failure(CliMain.SUPERSEDE_BEFORE, "shared-before=" + sharedBefore,
                    NOT_SUPERSEDED, output);
        }
    }

    /**
     * Declines, under the bounded reason and this command's own usage.
     *
     * @param reason one of {@link CliMain}'s three argument reasons
     * @return {@link CliMain#REFUSED}
     */
    private int refuse(final String reason) {
        return CliMain.refusal(CliMain.SUPERSEDE_BEFORE, USAGE, reason, output);
    }
}
