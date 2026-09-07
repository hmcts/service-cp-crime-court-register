package uk.gov.hmcts.cp.courtregister.batch.cli;

import java.util.List;
import java.util.function.Consumer;
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
 * <p>The notifier service and the stream are held here and read by T065; this is the seam T063 is
 * written against.
 */
// PMD.UnusedPrivateField: the collaborators the body T065 lands reads. They are constructor
// arguments now rather than then so that T063's cases can put a tally and a stream in front of the
// command and assert what an operator would see.
@SuppressWarnings("PMD.UnusedPrivateField")
public class NotifyRegisterCli {

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
    public int run(final List<String> args) {
        throw new UnsupportedOperationException("T065");
    }
}
