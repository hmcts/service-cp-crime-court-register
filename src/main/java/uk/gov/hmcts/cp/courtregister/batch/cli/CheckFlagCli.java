package uk.gov.hmcts.cp.courtregister.batch.cli;

import java.util.List;

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
 * <p>Collaborators - the flag reader - arrive with T065; this is the seam T063 is written against.
 */
public class CheckFlagCli {

    /**
     * Reads the flag and prints what it said.
     *
     * @param args the arguments that followed {@code check-flag}, of which there are none
     * @return {@link CliMain#SUCCESS} where the flag was read as on or off, {@link CliMain#FAILED}
     *         where it could not be read, or {@link CliMain#REFUSED} where an argument it does not
     *         take was given
     */
    public int run(final List<String> args) {
        throw new UnsupportedOperationException("T065");
    }
}
