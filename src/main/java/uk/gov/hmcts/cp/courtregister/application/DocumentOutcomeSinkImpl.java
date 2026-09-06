package uk.gov.hmcts.cp.courtregister.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.courtregister.domain.BatchFailureReason;
import uk.gov.hmcts.cp.courtregister.domain.BatchStatus;
import uk.gov.hmcts.cp.courtregister.domain.CompletedBy;
import uk.gov.hmcts.cp.courtregister.domain.RegisterBatch;
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
 * <p><strong>Three answers before a mark is made, and only one of them writes.</strong> An outcome
 * no batch answers to is counted and ignored; an outcome for a batch already standing where it
 * would put it has nothing left to record; and an outcome that would move a batch along an arrow
 * the data-model diagram does not draw - a document arriving for a batch the reconciler has already
 * given up on, say - leaves that batch exactly where it is and is reported here instead. None of
 * the three is silent: each says at what level it is worth reading, and only the last two of them
 * describe a batch this service actually holds.
 *
 * <p>An outcome for a correlation this service never recorded is counted and ignored: it is another
 * consumer's document, or one from a batch that predates this store, and neither is something to
 * invent a row for. Counted on this class rather than on {@code GenerationMetrics}, because the
 * documented instrument surface is a closed list that {@code GenerationMetricsTest} holds shut, and
 * a series is not what this reading is for: it is read by the person asking why a night's outcomes
 * went nowhere, beside the subscription's own health.
 *
 * <p><strong>The mark is still the decision.</strong> The state read here is a read, and two
 * mechanisms can make it about one batch at the same moment; the store's own compare-and-set is
 * what settles that, refusing the second of two marks rather than letting both believe they moved
 * the batch. What this class does with the same question beforehand is keep the ordinary duplicate -
 * the one a durable subscription is for - from being reported as a refusal every night.
 */
public class DocumentOutcomeSinkImpl implements DocumentOutcomeSink {

    private static final Logger LOG = LoggerFactory.getLogger(DocumentOutcomeSinkImpl.class);

    /** Where a batch's outcome is written, one batch at a time. */
    private final RegisterStore store;

    /** The {@code register_batch} reads that say which batch an outcome is about. */
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

    /**
     * {@inheritDoc}
     *
     * <p>The document's file-service id and the instant it was generated are what the batch had no
     * way of knowing until now, and the mechanism that learned them travels with the same mark: the
     * store writes {@code completed_by} in the statement that moves the batch, so there is no second
     * moment to write it in.
     */
    @Override
    public void documentAvailable(final UUID correlationId, final UUID payloadFileId,
            final UUID documentFileId, final Instant generatedAt, final CompletedBy completedBy) {

        apply(correlationId, payloadFileId, BatchStatus.GENERATED,
                batch -> store.markGenerated(batch.batchId(), documentFileId, generatedAt,
                        completedBy));
    }

    /**
     * {@inheritDoc}
     *
     * <p>The renderer's words are bounded where they cross into this service rather than only at
     * the row: {@link RegisterBatch#boundedReason(String)} is the one bound, and applying it here
     * means the store is handed a reason already the size its column admits. They are carried as an
     * argument and never logged - the batch's own reason is the bounded GENERATION_FAILED, and that
     * is what a log line and the counters get.
     *
     * <p>{@code failedAt} is the renderer's account of when it gave up and is not written: the
     * store stamps {@code failed_at} in the statement that fails the batch, so what the row records
     * is when this service learned of the refusal rather than another system's clock reading. It
     * stays on the port because the reconciler and the listener both have it, and a port that
     * dropped it would have to be widened again by whatever wants it next.
     */
    @Override
    public void generationFailed(final UUID correlationId, final UUID payloadFileId,
            final String reason, final Instant failedAt, final CompletedBy completedBy) {

        apply(correlationId, payloadFileId, BatchStatus.FAILED,
                batch -> store.markFailed(batch.batchId(), BatchFailureReason.GENERATION_FAILED,
                        RegisterBatch.boundedReason(reason), completedBy));
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

    /**
     * The one code path: find the batch, decide whether the outcome is news, and mark it if it is.
     *
     * <p>The mark itself is passed in rather than switched on here, so that what an outcome does to
     * a batch lives beside the method that received it and what an outcome has to survive to be
     * applied at all lives in exactly one place for both.
     *
     * @param correlationId the batch identity the outcome named, which the contract allows to be
     *                      absent
     * @param payloadFileId the payload the outcome is about, which the contract requires
     * @param outcome       the state this outcome would put the batch in
     * @param mark          the store call that puts it there
     */
    private void apply(final UUID correlationId, final UUID payloadFileId,
            final BatchStatus outcome, final Consumer<RegisterBatch> mark) {

        find(correlationId, payloadFileId).ifPresentOrElse(
                batch -> applyTo(batch, outcome, mark),
                () -> countUnattributed(correlationId, payloadFileId));
    }

    /**
     * What an outcome does to the batch it was attributed to: nothing, nothing, or the mark.
     *
     * <p>The three branches in the order they are worth reading. A batch already standing where the
     * outcome would put it is the redelivery a durable subscription is for and a reconciler racing
     * an in-flight event produces, and it is at DEBUG because it is expected. A move the state
     * machine does not draw is not: it is a late outcome for a batch already ended - a document for
     * one the reconciler gave up on, or a second refusal for one already FAILED under a different
     * reason - and re-stamping it would take systemdocgenerator's verdict about one identity and
     * attach it to a batch that has moved past it.
     *
     * @param batch   the batch the outcome was attributed to, as it stood when it was read
     * @param outcome the state this outcome would put it in
     * @param mark    the store call that puts it there
     */
    private void applyTo(final RegisterBatch batch, final BatchStatus outcome,
            final Consumer<RegisterBatch> mark) {

        if (batch.status() == outcome) {
            LOG.debug("Batch {} already stands at {}, so the outcome that has just arrived for it "
                    + "again is recognised rather than re-stamped.", batch.batchId(), outcome);
        } else if (batch.status().canTransitionTo(outcome)) {
            mark.accept(batch);
        } else {
            LOG.warn("Batch {} stands at {} and an outcome arrived that would move it to {}, which "
                    + "the state machine does not draw; the batch is left where it is and the "
                    + "outcome is reported here rather than applied.",
                    batch.batchId(), batch.status(), outcome);
        }
    }

    /**
     * Counts and reports an outcome no batch in this store answers to.
     *
     * <p>Both identifiers are in the line because which of the two was carried is half of what a
     * lost correlation looks like, and neither is about a defendant, a recipient or a register
     * (constitution Principle VII).
     *
     * @param correlationId the batch identity the outcome named, or {@code null} where it named none
     * @param payloadFileId the payload the outcome is about
     */
    private void countUnattributed(final UUID correlationId, final UUID payloadFileId) {
        unattributed.incrementAndGet();
        LOG.warn("An outcome arrived for correlation {} and payload {}, which this service has no "
                + "batch for, so it is counted and ignored: it is another consumer's document, or "
                + "one from a batch that predates this store.", correlationId, payloadFileId);
    }

    /**
     * The batch an outcome is about, by the identity it named or by the payload it was rendered
     * from.
     *
     * <p>The correlation first, because it is the batch's own identity and is what the render
     * request carried as {@code sourceCorrelationId}. The payload second, because the vendored
     * schemas make the correlation optional and the payload required, so a message that carries only
     * the payload is contract-legal and is still some batch's outcome - and because a correlation
     * that finds nothing is exactly the case the fallback exists for.
     *
     * @param correlationId the batch identity the outcome named, or {@code null}
     * @param payloadFileId the payload the outcome is about, or {@code null}
     * @return the batch, or empty where neither identifier finds one
     */
    private Optional<RegisterBatch> find(final UUID correlationId, final UUID payloadFileId) {
        Optional<RegisterBatch> found = correlationId == null
                ? Optional.empty()
                : batches.findById(correlationId);
        if (found.isEmpty() && payloadFileId != null) {
            found = batches.findByPayloadFileId(payloadFileId);
        }
        return found;
    }
}
