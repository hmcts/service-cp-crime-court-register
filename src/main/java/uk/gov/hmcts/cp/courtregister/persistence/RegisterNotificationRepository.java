package uk.gov.hmcts.cp.courtregister.persistence;

import java.util.List;
import java.util.UUID;
import uk.gov.hmcts.cp.courtregister.domain.RegisterNotification;

/**
 * The {@code register_notification} table: one row per distinct recipient of a batch.
 *
 * <p>The row is written PENDING before the POST and settled after it, so what was attempted is on
 * record whether or not it was answered - the same discipline 001 applies to
 * {@code processed_output.request_digest}, and for the same reason.
 *
 * <p><strong>Seam.</strong> Completed by T015; {@code RegisterNotifierServiceTest} and
 * {@code SchemaMigrationV2IT} (T007) guard it between them.
 */
public class RegisterNotificationRepository {

    /** The task that replaces every refusal in this class with a statement. */
    private static final String PENDING_TASK =
            "T015 implements RegisterNotificationRepository; SchemaMigrationV2IT (T007) guards it";

    /**
     * Mints one recipient's row before its POST is made.
     *
     * @param notification the row, carrying the identity the POST goes out under
     */
    public void insert(final RegisterNotification notification) {
        throw new UnsupportedOperationException(PENDING_TASK);
    }

    /**
     * Every recipient row of a batch, whatever state it is in.
     *
     * @param batchId the batch
     * @return its notification rows
     */
    public List<RegisterNotification> findByBatchId(final UUID batchId) {
        throw new UnsupportedOperationException(PENDING_TASK);
    }

    /**
     * The recipient rows of a batch that ended FAILED, which are the ones a resend attempts.
     *
     * @param batchId the batch
     * @return its failed notification rows, each under the identity it was first attempted with
     */
    public List<RegisterNotification> findFailedByBatchId(final UUID batchId) {
        throw new UnsupportedOperationException(PENDING_TASK);
    }

    /**
     * Settles one recipient's row on what notificationnotify answered.
     *
     * @param notification the row as it should now stand
     * @return how many rows the statement changed, which is the decision and never a read-back
     */
    public int update(final RegisterNotification notification) {
        throw new UnsupportedOperationException(PENDING_TASK);
    }
}
