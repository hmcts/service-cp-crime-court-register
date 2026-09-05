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
 * @param accepted how many recipients notificationnotify answered 202 for
 * @param failed   how many recipients ended FAILED and are resendable under their own identity
 * @param outcome  the terminal state the batch is settled in: NOTIFIED, PARTIALLY_NOTIFIED or
 *                 NOTIFIED_NOBODY
 */
public record NotificationSummary(int accepted, int failed, BatchStatus outcome) {
}
