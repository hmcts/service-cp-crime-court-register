package uk.gov.hmcts.cp.courtregister.batch.cli;

import java.util.List;

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
 * <p>Collaborators - the store and the notifier service - arrive with T065; this is the seam T063
 * is written against.
 */
public class NotifyRegisterCli {

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
