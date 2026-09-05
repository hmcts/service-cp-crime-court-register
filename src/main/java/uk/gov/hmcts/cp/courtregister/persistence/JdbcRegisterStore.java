package uk.gov.hmcts.cp.courtregister.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import uk.gov.hmcts.cp.courtregister.application.NotificationSummary;
import uk.gov.hmcts.cp.courtregister.application.RecordOutcome;
import uk.gov.hmcts.cp.courtregister.application.RegisterStore;
import uk.gov.hmcts.cp.courtregister.domain.BatchFailureReason;
import uk.gov.hmcts.cp.courtregister.domain.CourtCentreDay;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterDocument;
import uk.gov.hmcts.cp.courtregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.courtregister.domain.RecordedFlagState;
import uk.gov.hmcts.cp.courtregister.domain.RegisterBatch;
import uk.gov.hmcts.cp.courtregister.domain.RegisterRecord;

/**
 * The register store over this service's own Postgres.
 *
 * <p>The adapter that owns the write-time supersession and the batch-scoped {@code mark} statements,
 * written in the same idiom as the rest of {@code persistence}: hand-written SQL, one method per
 * statement, and the affected-row count is the decision.
 *
 * <p><strong>Seam.</strong> Every method below is completed by T015, whose green run is
 * {@code RegisterStoreIT} (T008) - including the P3 pin,
 * {@code generation_flips_only_the_batchs_own_rows}. Until then each one refuses rather than
 * returning something a caller could mistake for an empty store.
 */
public class JdbcRegisterStore implements RegisterStore {

    /** The task that replaces every refusal in this class with a statement. */
    private static final String PENDING_TASK =
            "T015 implements JdbcRegisterStore; RegisterStoreIT (T008) guards it";

    @Override
    public RecordOutcome record(final DistributionCommand command,
            final CourtRegisterDocument document, final String defendantType,
            final RecordedFlagState flagState) {
        throw new UnsupportedOperationException(PENDING_TASK);
    }

    @Override
    public List<RegisterRecord> activeUnbatched() {
        throw new UnsupportedOperationException(PENDING_TASK);
    }

    @Override
    public RegisterBatch assemble(final CourtCentreDay key, final List<RegisterRecord> records) {
        throw new UnsupportedOperationException(PENDING_TASK);
    }

    @Override
    public void markRequested(final UUID batchId, final UUID payloadFileId) {
        throw new UnsupportedOperationException(PENDING_TASK);
    }

    @Override
    public void markGenerated(final UUID batchId, final UUID documentFileId,
            final Instant generatedAt) {
        throw new UnsupportedOperationException(PENDING_TASK);
    }

    @Override
    public void markFailed(final UUID batchId, final BatchFailureReason reason,
            final String sdgReason) {
        throw new UnsupportedOperationException(PENDING_TASK);
    }

    @Override
    public void markNotified(final UUID batchId, final NotificationSummary summary) {
        throw new UnsupportedOperationException(PENDING_TASK);
    }
}
