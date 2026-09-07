package uk.gov.hmcts.cp.courtregister.batch.cli;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.courtregister.application.NotificationDisposition;
import uk.gov.hmcts.cp.courtregister.application.NotificationSummary;
import uk.gov.hmcts.cp.courtregister.application.RegisterNotifierService;

/**
 * {@code notify-register --batch B}.
 *
 * <p>Re-requests the recipients of one batch whose notification FAILED, and only those. A batch
 * that reached PARTIALLY_NOTIFIED has recipients who already have the register: a resend that
 * treated the batch as a unit would send a second copy of a document about children to a Youth
 * Offending Team that read the first one this morning, which is why the FAILED rows rather than the
 * batch are what this command works on.
 *
 * <p>It renders nothing and asks for nothing to be rendered. The batch already has its document -
 * that is what makes its recipients resendable - so the file it was rendered to is the file that is
 * attached again, by the same file-service identifier.
 *
 * <p>A batch with no FAILED recipients is a success that changed nothing, said so in the output
 * rather than in the exit code: there is nothing wrong with asking, and an operator working down a
 * list of batches should not have to tell a refusal from a batch that was already fine.
 *
 * <p><strong>What the call did is on the line as well as what the rows say.</strong> A resend can
 * find the batch already being told, lose the claim inside the cycle, or fail to account for one of
 * the batch's rows, and on all three the tally that comes back is the batch as it stood rather than
 * this call's work - so the line carries {@code disposition=<code>} and the exit code is taken from
 * it. The two that leave the batch unsettled are {@link CliMain#FAILED}, because this command is
 * the only thing that recovers such a batch and a step that read exit 0 would not run it again.
 *
 * <p>The batch is the one argument, and it is required: there is no default batch to resend and
 * there must not be one, because a command that guessed would e-mail a court centre nobody named.
 */
public class NotifyRegisterCli {

    /**
     * What the command takes, printed under a refusal and on request.
     *
     * <p>Package-visible because {@link CliMain} answers {@code --help} with it before it resolves
     * a single bean: a pod with the downstream half switched off holds none of this command's
     * collaborators, and what the command takes is still the answer to what was asked.
     */
    /* default */ static final String USAGE = "usage: " + CliMain.NOTIFY_REGISTER
            + " --" + Args.BATCH
            + " B (re-requests the FAILED recipients of one batch, and only those)";

    private static final Logger LOG = LoggerFactory.getLogger(NotifyRegisterCli.class);

    /** What this command could not finish, as the bounded reason the line carries. */
    private static final String NOT_RESENT = "resend-failed";

    /**
     * The resend, which is the notifier's own {@code resendFailed} and nothing this command
     * reimplements.
     *
     * <p>Asked rather than assembled here on purpose: which rows a resend attempts, under which
     * identities, and what the batch is then settled as is one rule the service already holds, and
     * a command that re-decided it would be a second answer to "has this team been told" that could
     * disagree with the schedule's.
     */
    private final RegisterNotifierService notifier;

    /** Where the tally is written, one line per call. */
    private final Consumer<String> output;

    /**
     * Creates the command over the resend and the operator's own stream.
     *
     * @param notifierService the notifier service, whose {@code resendFailed} is the whole of what
     *                        this command does
     * @param lines           where the tally is written, one line per call
     */
    public NotifyRegisterCli(final RegisterNotifierService notifierService,
            final Consumer<String> lines) {
        this.notifier = notifierService;
        this.output = lines;
    }

    /**
     * Re-requests the FAILED recipients of the batch the arguments name.
     *
     * @param args the arguments that followed {@code notify-register}
     * @return {@link CliMain#SUCCESS}, {@link CliMain#REFUSED} where the arguments were not usable,
     *         or {@link CliMain#FAILED}
     */
    // PMD.OnlyOneReturn: the four exits are the four things that can happen to an invocation, each
    // said where it is decided; one exit would carry a verdict past the resend that must not be
    // asked for once the arguments have been refused.
    @SuppressWarnings("PMD.OnlyOneReturn")
    public int run(final List<String> args) {
        final Args parsed;
        try {
            parsed = Args.parse(args);
        } catch (IllegalArgumentException notUsable) {
            return CliMain.unreadable(CliMain.NOTIFY_REGISTER, USAGE, notUsable, output);
        }
        if (parsed.askedForHelp()) {
            output.accept(USAGE);
            return CliMain.SUCCESS;
        }
        if (!parsed.permits(Set.of(Args.BATCH), Set.of())) {
            return refuse(CliMain.UNEXPECTED_ARGUMENT);
        }
        final String typed = parsed.options().get(Args.BATCH);
        if (typed == null) {
            return refuse(CliMain.MISSING_ARGUMENT);
        }
        final UUID batchId;
        try {
            batchId = UUID.fromString(typed);
        } catch (IllegalArgumentException notAnIdentity) {
            return CliMain.unreadable(CliMain.NOTIFY_REGISTER, USAGE, notAnIdentity, output);
        }
        return resend(batchId);
    }

    /**
     * Asks the notifier for this batch's owed recipients and reports the tally it answered.
     *
     * <p>A batch this service never assembled, and a store that went away while the resend was
     * being attempted, are the same answer from here: the attempt reached no tally, which is
     * {@link CliMain#FAILED} and not a refusal. The batch is named so an operator working down a
     * ticket's list knows which one to come back to, and the far end's own sentence about it is on
     * the log line rather than on their terminal.
     *
     * @param batchId the batch an operator carried in from a support ticket
     * @return {@link CliMain#SUCCESS} where a tally was taken, {@link CliMain#FAILED} where none
     *         was
     */
    // PMD.AvoidCatchingGenericException: the notifier refuses an unknown batch through
    // IllegalStateException and the store translates an outage into its own unchecked type; both
    // mean the same thing here - no tally was taken - and a narrower catch would leave one of them
    // reaching an operator as a stack trace.
    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn"})
    private int resend(final UUID batchId) {
        try {
            final NotificationSummary settled = notifier.resendFailed(batchId);
            output.accept("batch=" + batchId + " accepted=" + settled.accepted()
                    + " failed=" + settled.failed() + " state=" + settled.outcome()
                    + " disposition=" + settled.disposition().code());
            return verdict(batchId, settled.disposition());
        } catch (RuntimeException notResent) {
            LOG.error("The recipients owed by batch {} could not be re-requested, so the batch is "
                    + "left as it stands. cause={}", batchId, notResent.getClass().getName(),
                    notResent);
            return CliMain.failure(CliMain.NOTIFY_REGISTER, "batch=" + batchId, NOT_RESENT, output);
        }
    }

    /**
     * The exit code one of the four answers deserves, which the tally cannot decide.
     *
     * <p>A caller branches on the disposition and not on the counts
     * ({@link uk.gov.hmcts.cp.courtregister.application.NotificationSummary}), and the exit code is
     * this command's whole interface with the step that ran it. SETTLED is the ordinary success and
     * ALREADY_NOTIFYING is a success too: the batch is being told by the outcome sink, this call
     * posted nothing, and there is nothing left for the operator to do. The other two are
     * {@link CliMain#FAILED} - a claim lost inside the cycle and a recipient the store could not
     * account for both leave the batch unsettled, which is the one thing a runbook step is meant to
     * retry, and nothing but this command recovers it.
     *
     * @param batchId     the batch the attempt was about, named on the failure line
     * @param disposition what the call actually did
     * @return {@link CliMain#SUCCESS} or {@link CliMain#FAILED}, under the disposition's own code
     */
    private int verdict(final UUID batchId, final NotificationDisposition disposition) {
        return switch (disposition) {
            case SETTLED, ALREADY_NOTIFYING -> CliMain.SUCCESS;
            case CLAIM_LOST, INCOMPLETE -> {
                LOG.warn("The recipients owed by batch {} were not settled by this resend, so the "
                        + "batch stands where it is. disposition={}", batchId, disposition.code());
                yield CliMain.failure(CliMain.NOTIFY_REGISTER, "batch=" + batchId,
                        disposition.code(), output);
            }
        };
    }

    /**
     * Declines, under the bounded reason and this command's own usage.
     *
     * @param reason one of {@link CliMain}'s three argument reasons
     * @return {@link CliMain#REFUSED}
     */
    private int refuse(final String reason) {
        return CliMain.refusal(CliMain.NOTIFY_REGISTER, USAGE, reason, output);
    }
}
