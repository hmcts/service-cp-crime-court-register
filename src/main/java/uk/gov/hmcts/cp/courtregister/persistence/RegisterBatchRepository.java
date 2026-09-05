package uk.gov.hmcts.cp.courtregister.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import uk.gov.hmcts.cp.courtregister.domain.RegisterBatch;

/**
 * The {@code register_batch} table: one row per court centre per register day.
 *
 * <p>Separate from {@link ProcessedOutputRepository} because the two answer different questions. The
 * output rows say what was recorded for a hearing; this one says what was asked of the renderer for
 * a group of them, and it is the row a public event correlates back to. The progression leg had
 * neither, which is why a failed generation there left nothing behind to look at.
 *
 * <p><strong>Seam.</strong> Completed by T015; {@code RegisterStoreIT} (T008) and
 * {@code SchemaMigrationV2IT} (T007) guard it between them.
 */
public class RegisterBatchRepository {

    /** The task that replaces every refusal in this class with a statement. */
    private static final String PENDING_TASK =
            "T015 implements RegisterBatchRepository; RegisterStoreIT (T008) guards it";

    /**
     * Inserts a newly assembled batch.
     *
     * @param batch the batch, carrying the identity minted at assembly
     */
    public void insert(final RegisterBatch batch) {
        throw new UnsupportedOperationException(PENDING_TASK);
    }

    /**
     * Reads one batch by its identity.
     *
     * @param batchId the batch identity
     * @return the batch, or empty where this service recorded no such batch
     */
    public Optional<RegisterBatch> findById(final UUID batchId) {
        throw new UnsupportedOperationException(PENDING_TASK);
    }

    /**
     * Reads one batch by the payload it was rendered from.
     *
     * <p>The reconciler's read, and the listener's fallback: an outcome names the payload as well as
     * the correlation, so a batch is still findable when only one of the two is trustworthy.
     *
     * @param payloadFileId the file-service id the payload was stored under
     * @return the batch, or empty where no batch owns that payload
     */
    public Optional<RegisterBatch> findByPayloadFileId(final UUID payloadFileId) {
        throw new UnsupportedOperationException(PENDING_TASK);
    }

    /**
     * The batches that have been GENERATING since before the given instant.
     *
     * @param requestedBefore the far edge of the grace period
     * @return every batch whose outcome is overdue, oldest first
     */
    public List<RegisterBatch> generatingSince(final Instant requestedBefore) {
        throw new UnsupportedOperationException(PENDING_TASK);
    }

    /**
     * Writes a batch's new state, refusing a transition the state machine does not permit.
     *
     * @param batch the batch as it should now stand
     * @return how many rows the statement changed, which is the decision and never a read-back
     */
    public int update(final RegisterBatch batch) {
        throw new UnsupportedOperationException(PENDING_TASK);
    }
}
