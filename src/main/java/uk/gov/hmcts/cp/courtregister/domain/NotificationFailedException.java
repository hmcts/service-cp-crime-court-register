package uk.gov.hmcts.cp.courtregister.domain;

import java.util.OptionalInt;

/**
 * Thrown when one recipient's {@code send-email-notification} did not succeed.
 *
 * <p>Per recipient and never per batch. A batch whose first recipient is refused still has the rest
 * to tell, and an exception that ended the batch would turn one bad address into a night's silence
 * for a whole court centre; the notifier catches this one per row and settles the batch NOTIFIED,
 * PARTIALLY_NOTIFIED or NOTIFIED_NOBODY on the tally.
 *
 * <p>The two facts a failed row records are its status and the status line, so those are the two
 * this carries. There is no bounded reason code beyond them because there is nowhere to write one:
 * {@code register_notification} has {@code status} and {@code response_code} and nothing else that
 * could hold another system's opinion, and the address in the row is already the thing support
 * needs.
 */
public class NotificationFailedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final FailureClassification failureClassification;
    private final Integer status;

    /**
     * Creates a failure with no status line to record.
     *
     * @param classification whether a resend could change the answer
     */
    public NotificationFailedException(final FailureClassification classification) {
        this(classification, null);
    }

    /**
     * Creates a failure carrying the status notificationnotify answered with.
     *
     * @param classification whether a resend could change the answer
     * @param responseCode   the status notificationnotify answered with, or {@code null} where
     *                       nothing answered at all
     */
    public NotificationFailedException(
            final FailureClassification classification, final Integer responseCode) {
        super(classification.name());
        this.failureClassification = classification;
        this.status = responseCode;
    }

    /**
     * Whether a resend could change the answer.
     *
     * @return the classification the throw site gave
     */
    public FailureClassification classification() {
        return failureClassification;
    }

    /**
     * The status notificationnotify answered with, where it answered.
     *
     * @return the status line, or empty where the attempt reached no verdict
     */
    public OptionalInt responseCode() {
        return status == null ? OptionalInt.empty() : OptionalInt.of(status);
    }
}
