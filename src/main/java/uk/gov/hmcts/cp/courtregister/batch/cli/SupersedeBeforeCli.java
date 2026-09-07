package uk.gov.hmcts.cp.courtregister.batch.cli;

import java.util.List;

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
 * <p>Collaborators - the store - arrive with T065; this is the seam T063 is written against.
 */
public class SupersedeBeforeCli {

    /**
     * Supersedes the records recorded before the instant the arguments name.
     *
     * @param args the arguments that followed {@code supersede-before}
     * @return {@link CliMain#SUCCESS}, {@link CliMain#REFUSED} where the instant was missing or
     *         unusable, or {@link CliMain#FAILED}
     */
    public int run(final List<String> args) {
        throw new UnsupportedOperationException("T065");
    }
}
