package uk.gov.hmcts.cp.courtregister.application;

/**
 * What a call to the notifying leg actually did, as against what the batch's rows add up to.
 *
 * <p>Two mechanisms can reach one generated batch at the same moment - the outcome sink on a
 * delivered {@code document-available}, and an operator's resend - and only one of them may post.
 * The one that does not needs a way to say so that a caller can act on and a metric can carry, and
 * a tally cannot say it: the counts a loser reads are the winner's work in progress, and a batch
 * state is where the batch stands rather than what this call decided.
 *
 * <p>Bounded, and deliberately two constants rather than a message. A caller branches on it, a
 * {@code reason} label is derived from it, and neither may ever carry a court centre, a batch
 * identity or a recipient (constitution Principle VII).
 */
public enum NotificationDisposition {

    /**
     * This call held the batch's notification claim, posted for whoever was owed an e-mail, and
     * settled the batch on the tally.
     */
    SETTLED,

    /**
     * Another notifier held the claim, so this call posted nothing and settled nothing.
     *
     * <p>Not a failure and not a refusal: the batch is being told by somebody else, and the caller
     * that meets this has nothing left to do. The tally that travels with it is the batch as it
     * stood when the claim was refused, which is the winner's work part-done.
     */
    ALREADY_NOTIFYING,

    /**
     * This call held the claim, began the cycle, and lost the claim part way through it.
     *
     * <p>The third answer, and a different event from {@link #ALREADY_NOTIFYING}: that one is a
     * notifier that never started, and this is one that started, told some of the batch's teams and
     * then found the batch was no longer its own. So some POSTs were really made and the rows and
     * the batch are part-written, by two notifiers rather than one.
     *
     * <p>The claim is renewed before every POST and before every write, so what this says is that a
     * renewal was refused: the lease ran out under this notifier - it is telling more recipients than
     * the lease covers, or notificationnotify is answering more slowly than the lease allows - and
     * something else has taken the batch over. The run stops there rather than writing over the work
     * of the notifier that now holds it, and the rows it did not settle are re-requested by a later
     * run under the identities they already hold.
     */
    CLAIM_LOST,

    /**
     * This call held the claim throughout and could not finish the cycle, so the batch is not
     * settled.
     *
     * <p>The fourth answer, and the one that is about this service's own store rather than about
     * two notifiers. A settlement made for a row this run read back or minted, and that the store
     * then does not hold, means the cycle cannot account for one of the batch's recipients: the
     * attempt is recorded nowhere, so the tally the batch would be settled from is a tally over
     * rows that no longer describe what was sent.
     *
     * <p>Settling anyway was the worse answer, and quietly. A batch whose only recipient row had
     * gone tallied to nought rows and reached NOTIFIED_NOBODY - the terminal state that says the
     * document was rendered and there was nobody to send it to, defect fix P1's own words, written
     * over a batch addressed to a Youth Offending Team all along and terminal, so no later resend
     * could revisit it. So the cycle stops, the claim is given back, and the batch stays where it
     * stands with whatever rows are settled: GENERATED is a state {@code notify-register --batch}
     * and the reconciler both recover, and {@code courtregister_oldest_generated_age} is the
     * reading that says a batch has been standing there.
     *
     * <p>The bounded reason it is counted under is {@code settlement-row-absent}, which is the
     * fault itself; this is what the call did about it.
     */
    INCOMPLETE
}
