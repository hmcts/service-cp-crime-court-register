package uk.gov.hmcts.cp.courtregister.application;

import java.util.UUID;

/**
 * One generated batch, from its recipients to the e-mails they are told by.
 *
 * <p>The order is the discipline the rest of the service keeps: the batch's recipients are the
 * de-duplicated union across its records ({@code batch/RecipientSet}, defect fix P4), a
 * {@code register_notification} row is minted PENDING for each of them with the
 * {@code notificationId} its POST will be made under, and only then is anything sent. An id used in
 * a call and written down afterwards is an e-mail this service cannot show it asked for, and a retry
 * under a fresh id is a second e-mail to the same Youth Offending Team (research §10).
 *
 * <p>Each recipient is settled on its own: ACCEPTED where notificationnotify answered 202, FAILED
 * with the status otherwise, and the next recipient is asked either way, because one team's refusal
 * says nothing about another team's e-mail. The batch is then settled from the tally alone - all
 * accepted is NOTIFIED, some failed is PARTIALLY_NOTIFIED, and no recipients at all is
 * NOTIFIED_NOBODY.
 *
 * <p><strong>Defect fix P1: no recipients is a terminal state, not a wait.</strong> The progression
 * leg leaves a batch nobody subscribes to sitting generated for ever, waiting on an event nobody
 * publishes. NOTIFIED_NOBODY says plainly that the document was rendered and there was nobody to
 * send it to, and it is counted rather than inferred from an absence.
 *
 * <p>{@link #resendFailed} is the second half of that honesty: a PARTIALLY_NOTIFIED batch keeps its
 * FAILED rows under the identities they were first attempted with, so a resend re-requests those
 * rows and no others - the teams that were told are not told twice - and the batch reaches NOTIFIED
 * when the last of them is accepted.
 *
 * <p>Every line this service writes carries ids and bounded codes. A recipient's address and name
 * never reach a log at INFO or above and never a metric label (constitution Principle VII); they
 * live in the notification row, which is the only place that may hold them.
 *
 * <p><strong>Seam only.</strong> The service lands with T059; until then both methods throw, so that
 * {@code RegisterNotifierServiceTest} records a failing assertion rather than a compile error.
 */
public class RegisterNotifierService {

    /**
     * Tells every recipient of one generated batch, and settles the batch on the tally.
     *
     * @param batchId the batch whose document has been generated
     * @return how many recipients were accepted, how many failed, and the terminal state the batch
     *     is settled in
     */
    public NotificationSummary notify(final UUID batchId) {
        throw new UnsupportedOperationException("T059");
    }

    /**
     * Re-requests only the recipients whose e-mail ended FAILED, under the identities they already
     * hold.
     *
     * @param batchId the batch to resend for
     * @return the tally over the whole batch as it now stands, and the terminal state that produces
     */
    public NotificationSummary resendFailed(final UUID batchId) {
        throw new UnsupportedOperationException("T059");
    }
}
