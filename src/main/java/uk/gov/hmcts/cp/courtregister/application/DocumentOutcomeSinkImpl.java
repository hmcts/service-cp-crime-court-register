package uk.gov.hmcts.cp.courtregister.application;

import java.time.Instant;
import java.util.UUID;
import uk.gov.hmcts.cp.courtregister.domain.CompletedBy;

/**
 * The one place a rendering outcome becomes a batch state, whoever learned it.
 *
 * <p>The listener and the reconciler both arrive here, which is the whole reason the port exists:
 * one code path decides what an outcome does to a batch and to that batch's rows, and one code path
 * absorbs the duplicate that a durable subscription and a grace-period query will eventually produce
 * for the same batch.
 *
 * <p><strong>Scoped to the batch it was given.</strong> A document available for a batch flips that
 * batch's rows and no others, through {@code RegisterStore.markGenerated(batchId)}. Reaching for the
 * court centre and the day instead is exactly defect P3, where progression's completion flipped
 * every register for a court centre including the ones belonging to another day's batch.
 *
 * <p><strong>Defect fix P2 lands here.</strong> progression logs a {@code generation-failed} event
 * and records nothing, so a batch that systemdocgenerator refused is indistinguishable from one it
 * never answered about. Here it becomes a FAILED batch with the bounded reason GENERATION_FAILED,
 * carrying systemdocgenerator's own words in {@code sdg_reason} for support and never logging them
 * at INFO.
 *
 * <p>An outcome for a correlation this service never recorded is counted and ignored: it is another
 * consumer's document, or one from a batch that predates this store, and neither is something to
 * invent a row for.
 *
 * <p><strong>Seam only.</strong> The sink lands with T047; until then both methods throw, so that
 * {@code DocumentOutcomeSinkTest} records a failing assertion rather than a compile error.
 */
public class DocumentOutcomeSinkImpl implements DocumentOutcomeSink {

    @Override
    public void documentAvailable(final UUID correlationId, final UUID payloadFileId,
            final UUID documentFileId, final Instant generatedAt, final CompletedBy completedBy) {
        throw new UnsupportedOperationException("T047");
    }

    @Override
    public void generationFailed(final UUID correlationId, final UUID payloadFileId,
            final String reason, final Instant failedAt, final CompletedBy completedBy) {
        throw new UnsupportedOperationException("T047");
    }
}
