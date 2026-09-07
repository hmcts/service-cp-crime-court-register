package uk.gov.hmcts.cp.courtregister.application;

import uk.gov.hmcts.cp.courtregister.domain.BatchStatus;

/**
 * How a batch's notifications went, in the shape the batch row is settled from.
 *
 * <p>The tally and the verdict travel together because the verdict is not derivable from the tally
 * alone: nought accepted and nought failed is NOTIFIED_NOBODY where the batch had no recipients, and
 * it is not a state any count of failures can produce. That distinction is defect fix P1 - the
 * progression leg leaves a batch nobody subscribes to sitting generated for ever, waiting for an
 * event nobody publishes.
 *
 * <p><strong>And the disposition travels with both</strong>, because a summary that only carried a
 * tally could not tell the caller which of two notifiers it was. A batch is notified under a claim,
 * so a second notifier that met the claim posted nothing and settled nothing; what it can honestly
 * report is the rows as they stood when it was refused, which is the winner's work part-done and
 * not a verdict about the batch. {@link NotificationDisposition#SETTLED} is the ordinary answer and
 * the one the three-component constructor gives.
 *
 * @param accepted    how many recipients notificationnotify answered 202 for
 * @param failed      how many recipients ended FAILED and are resendable under their own identity
 * @param outcome     the terminal state the batch is settled in: NOTIFIED, PARTIALLY_NOTIFIED or
 *                    NOTIFIED_NOBODY. On an {@link NotificationDisposition#ALREADY_NOTIFYING}
 *                    answer it is instead where the batch stood when the claim was refused, which
 *                    this call did not put it in and did not write
 * @param disposition what this call did: settled the batch, or found another notifier holding it
 */
public record NotificationSummary(
        int accepted, int failed, BatchStatus outcome, NotificationDisposition disposition) {

    /**
     * The ordinary answer: a call that held the claim and settled the batch on its tally.
     *
     * @param accepted how many recipients notificationnotify answered 202 for
     * @param failed   how many recipients ended FAILED
     * @param outcome  the terminal state the batch is settled in
     */
    public NotificationSummary(final int accepted, final int failed, final BatchStatus outcome) {
        this(accepted, failed, outcome, NotificationDisposition.SETTLED);
    }

    /**
     * The answer of a notifier that found the batch already being told by another.
     *
     * <p>The tally is the rows as they stood at the moment the claim was refused and the state is
     * where the batch stood then: both are the winner's work in progress, which is why the
     * disposition and not the counts is what a caller branches on.
     *
     * @param accepted how many of the batch's rows were already accepted
     * @param failed   how many of them stood FAILED
     * @param standing where the batch stood when the claim was refused
     * @return the summary, carrying {@link NotificationDisposition#ALREADY_NOTIFYING}
     */
    public static NotificationSummary alreadyNotifying(
            final int accepted, final int failed, final BatchStatus standing) {
        return new NotificationSummary(
                accepted, failed, standing, NotificationDisposition.ALREADY_NOTIFYING);
    }

    /**
     * The answer of a notifier that held the claim, began the cycle and lost the claim inside it.
     *
     * <p>The tally is the rows as they stood when the renewal was refused and the state is where the
     * batch stood then, and neither is this call's work alone: this notifier told some of the teams,
     * and the notifier that took the batch over is telling the rest. Which is why the disposition
     * and not the counts is what a caller branches on.
     *
     * @param accepted how many of the batch's rows stood accepted when the claim was lost
     * @param failed   how many of them stood FAILED
     * @param standing where the batch stood when the claim was lost, which this call did not write
     * @return the summary, carrying {@link NotificationDisposition#CLAIM_LOST}
     */
    public static NotificationSummary claimLost(
            final int accepted, final int failed, final BatchStatus standing) {
        return new NotificationSummary(
                accepted, failed, standing, NotificationDisposition.CLAIM_LOST);
    }

    /**
     * Whether this call is the one that posted for the batch and settled it.
     *
     * @return true where this call held the claim
     */
    public boolean settled() {
        return disposition == NotificationDisposition.SETTLED;
    }
}
