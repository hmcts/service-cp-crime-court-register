package uk.gov.hmcts.cp.courtregister.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.courtregister.config.GenerationMetrics;
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
 * <p><strong>The correlation is authoritative, and the payload has to agree with it.</strong>
 * {@code sourceCorrelationId} is the batch's own identity and is what the render request carried,
 * so it is the only identifier a batch is ever looked up by here. {@code payloadFileServiceId} is
 * a cross-check on that lookup and never a second route to a batch: the vendored schemas make the
 * correlation optional and the payload required, but this service always sends a correlation, so an
 * outcome that carries none - or one this store has no batch for - is not an outcome to go looking
 * for a batch for by other means. Reaching for {@code findByPayloadFileId} there would take an
 * event whose own account of which batch it is about is missing or wrong and complete a night's
 * registers on it anyway. The lookup is also what makes a redelivery idempotent - a batch already
 * where the outcome would put it is recognised rather than re-stamped, which is the reading
 * {@code BatchStatus} was narrowed to force.
 *
 * <p><strong>Four answers before a mark is made, and only one of them writes.</strong> An outcome
 * naming a correlation no batch answers to is counted and ignored; an outcome whose payload is not
 * the one its batch was requested for is counted and ignored, because an event that contradicts
 * itself is the one shape that could complete the wrong batch; an outcome for a batch already
 * standing where it would put it has nothing left to record; and an outcome that would move a batch
 * along an arrow the data-model diagram does not draw - a document arriving for a batch the
 * reconciler has already given up on, say - leaves that batch exactly where it is and is reported
 * here instead. None of the four is silent: each says at what level it is worth reading, and only
 * the last three of them describe a batch this service actually holds.
 *
 * <p>The two that are ignored are counted on
 * {@code courtregister_public_events_ignored_total{reason}} beside the listener's own
 * {@code foreign-source}, because together the three answer the question a night whose outcomes
 * went nowhere is read by: how many announcements reached this subscription and were applied to
 * nothing, and which of the three ways it happened.
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

    /** Where an outcome that is applied to nothing is counted, under its own bounded reason. */
    private final GenerationMetrics metrics;

    /**
     * Creates the sink over the store it writes through and the batches it correlates against.
     *
     * @param store   where a batch's outcome is written, one batch at a time
     * @param batches the {@code register_batch} reads that say which batch an outcome is about
     * @param metrics where an outcome no batch takes is counted, by the reason it was not taken
     */
    public DocumentOutcomeSinkImpl(final RegisterStore store,
            final RegisterBatchRepository batches, final GenerationMetrics metrics) {
        this.store = store;
        this.batches = batches;
        this.metrics = metrics;
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

        find(correlationId).ifPresentOrElse(
                batch -> applyToTheBatchItNamed(batch, payloadFileId, outcome, mark),
                () -> countUnattributed(correlationId, payloadFileId));
    }

    /**
     * Applies the outcome to the batch the correlation named, if the payload agrees that it is
     * about it.
     *
     * <p>The batch's stored {@code payload_file_id} is what the render request asked
     * systemdocgenerator for, and the event's {@code payloadFileServiceId} is what it says it
     * rendered. Where they differ - including where the batch has no payload id at all, because it
     * never reached the renderer - the event is not this batch's, and there is nothing in it worth
     * believing about any other batch either. It moves nothing and is counted.
     *
     * @param batch         the batch the correlation named, as it stood when it was read
     * @param payloadFileId the payload the outcome is about
     * @param outcome       the state this outcome would put the batch in
     * @param mark          the store call that puts it there
     */
    private void applyToTheBatchItNamed(final RegisterBatch batch, final UUID payloadFileId,
            final BatchStatus outcome, final Consumer<RegisterBatch> mark) {

        if (batch.payloadFileId() == null || !batch.payloadFileId().equals(payloadFileId)) {
            countPayloadMismatch(batch, payloadFileId);
        } else {
            applyTo(batch, outcome, mark);
        }
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
        metrics.unknownCorrelationIgnored();
        LOG.warn("An outcome arrived for correlation {} and payload {}, which this service has no "
                + "batch for, so it is counted and ignored: it is another consumer's document, or "
                + "one from a batch that predates this store. The payload is a cross-check on the "
                + "correlation and not a second way to a batch, so no other lookup is made.",
                correlationId, payloadFileId);
    }

    /**
     * Counts and reports an outcome whose two identifiers do not describe one batch.
     *
     * <p>At WARN because it is not an ordinary event: the correlation is a batch this service
     * really did ask for, so this is not somebody else's document reaching the subscription - it is
     * this service's own render being announced against the wrong artefact, or an event whose
     * fields have been crossed between the renderer and the topic. Both identifiers are in the
     * line, and both are file-service and batch identities rather than anything about a person
     * (constitution Principle VII).
     *
     * @param batch         the batch the correlation named
     * @param payloadFileId the payload the outcome says it is about
     */
    private void countPayloadMismatch(final RegisterBatch batch, final UUID payloadFileId) {
        metrics.payloadMismatchIgnored();
        LOG.warn("An outcome named batch {}, which was requested for payload {}, and says it is "
                + "about payload {}; the two disagree, so the batch is left where it is and the "
                + "outcome is counted rather than applied.",
                batch.batchId(), batch.payloadFileId(), payloadFileId);
    }

    /**
     * The batch an outcome is about, by the identity it named and by nothing else.
     *
     * <p>{@code sourceCorrelationId} is the batch's own identity and is what this service put in
     * the render request, so it is the whole of the lookup. The vendored schemas make it optional,
     * which is why {@code null} is answered rather than refused - an outcome carrying no
     * correlation is contract-legal and is still not one this service can attribute, because it
     * always sends one.
     *
     * @param correlationId the batch identity the outcome named, or {@code null}
     * @return the batch, or empty where the correlation is absent or names none
     */
    private Optional<RegisterBatch> find(final UUID correlationId) {
        return correlationId == null ? Optional.empty() : batches.findById(correlationId);
    }
}
