package uk.gov.hmcts.cp.courtregister.application;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import uk.gov.hmcts.cp.courtregister.domain.CompletedBy;
import uk.gov.hmcts.cp.courtregister.persistence.RegisterBatchRepository;

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
 * <p><strong>Which batch an outcome is about is looked up, not assumed.</strong> The vendored
 * schemas make {@code sourceCorrelationId} optional and {@code payloadFileServiceId} required on
 * both events, so the correlation alone is not enough to find a batch by:
 * {@link RegisterBatchRepository#findByPayloadFileId(UUID)} is the fallback its own javadoc names,
 * and an outcome that neither identifier finds a batch for is the unattributed one below. The
 * lookup is also what makes a redelivery idempotent - a batch already where the outcome would put
 * it is recognised rather than re-stamped, which is the reading {@code BatchStatus} was narrowed to
 * force.
 *
 * <p>An outcome for a correlation this service never recorded is counted and ignored: it is another
 * consumer's document, or one from a batch that predates this store, and neither is something to
 * invent a row for. Counted on this class rather than on {@code GenerationMetrics}, because the
 * documented instrument surface is a closed list that {@code GenerationMetricsTest} holds shut, and
 * a series is not what this reading is for: it is read by the person asking why a night's outcomes
 * went nowhere, beside the subscription's own health.
 *
 * <p><strong>Seam only.</strong> The sink lands with T047; until then both methods throw and the
 * tally stays at zero, so that {@code DocumentOutcomeSinkTest} records a failing assertion rather
 * than a compile error.
 */
public class DocumentOutcomeSinkImpl implements DocumentOutcomeSink {

    /** The task that replaces the refusals below with the lookup and the two marks. */
    private static final String PENDING_TASK =
            "T047 implements DocumentOutcomeSinkImpl; DocumentOutcomeSinkTest (T037) guards it";

    // Both are read by T047, which replaces the refusals below with the lookup and the marks.
    @SuppressWarnings("PMD.UnusedPrivateField")
    private final RegisterStore store;

    @SuppressWarnings("PMD.UnusedPrivateField")
    private final RegisterBatchRepository batches;

    /**
     * Outcomes that named a batch this service never recorded, counted for the life of the pod.
     *
     * <p>Atomic because the two drivers are not one thread: the listener's deliveries arrive on the
     * container's threads and the reconciler runs on the job's, and a tally that lost increments
     * would under-report the one thing it exists to report.
     */
    private final AtomicLong unattributed = new AtomicLong();

    /**
     * Creates the sink over the store it writes through and the batches it correlates against.
     *
     * @param store   where a batch's outcome is written, one batch at a time
     * @param batches the {@code register_batch} reads that say which batch an outcome is about
     */
    public DocumentOutcomeSinkImpl(final RegisterStore store,
            final RegisterBatchRepository batches) {
        this.store = store;
        this.batches = batches;
    }

    @Override
    public void documentAvailable(final UUID correlationId, final UUID payloadFileId,
            final UUID documentFileId, final Instant generatedAt, final CompletedBy completedBy) {
        throw new UnsupportedOperationException(PENDING_TASK);
    }

    @Override
    public void generationFailed(final UUID correlationId, final UUID payloadFileId,
            final String reason, final Instant failedAt, final CompletedBy completedBy) {
        throw new UnsupportedOperationException(PENDING_TASK);
    }

    /**
     * How many outcomes named a batch this service has no record of.
     *
     * <p>Zero is the expected reading. Anything else says that either another consumer's documents
     * are reaching this subscription - the {@code originatingSource} filter is the listener's answer
     * to that - or that documents are coming back for batches this store never wrote, which is a
     * correlation that was lost between the render request and the event.
     *
     * @return the tally since this pod started
     */
    public long unattributedOutcomes() {
        return unattributed.get();
    }
}
