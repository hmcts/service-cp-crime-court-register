package uk.gov.hmcts.cp.courtregister.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import uk.gov.hmcts.cp.courtregister.domain.BatchFailureReason;
import uk.gov.hmcts.cp.courtregister.domain.CompletedBy;
import uk.gov.hmcts.cp.courtregister.domain.CourtCentreDay;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterDocument;
import uk.gov.hmcts.cp.courtregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.courtregister.domain.RecordedFlagState;
import uk.gov.hmcts.cp.courtregister.domain.RegisterBatch;
import uk.gov.hmcts.cp.courtregister.domain.RegisterRecord;

/**
 * Where an assembled register is kept, and how a batch's progress is written against it.
 *
 * <p>The port that replaces {@link RegisterSubmissionClient} as the pipeline's last step. The
 * register is no longer somebody else's to hold: it is recorded here, in this service's own store,
 * and progression's {@code add-court-register} is not called at all unless
 * {@code courtregister.output=progression-post} is set for the documented fallback sequencing.
 *
 * <p>Nothing here names SQL, a transaction manager or a row. The adapter behind it owns all three,
 * and it is the adapter that makes {@link #record} atomic - the insert and the supersession of the
 * hearing's earlier active row are one transaction, so no reader can see two active registers for
 * one hearing and one day (research §8).
 *
 * <p><strong>Every {@code mark} is scoped to one batch.</strong> That is defect fix P3 stated as a
 * signature: progression flips rows by court centre, so a document generated for Monday marks
 * Tuesday's rows generated too and Tuesday's register is never sent. A batch identity is the
 * argument here precisely so the widening cannot be reintroduced without changing the port.
 */
public interface RegisterStore {

    /**
     * Records one hearing's register, superseding that hearing's earlier active row for the day.
     *
     * <p>One transaction: the new row is inserted RECORDED and any earlier RECORDED, unsuperseded,
     * unbatched row for the same hearing within the same {@link CourtCentreDay} is marked SUPERSEDED
     * and pointed at the new one. A row that already carries a {@code batch_id} is never touched -
     * it is on its way to a PDF, and rewriting it would take a register out of a batch the renderer
     * has already been asked about.
     *
     * <p>The court centre's OU code is an argument because it is the one fact the batch needs that
     * the document does not carry: the transformation resolves it from reference data (001 carries
     * it on {@code ProcessedOutputClaim} for the same reason), {@code assemble} copies it from the
     * batch's first row, and the render payload and the file name are built from it. Nothing
     * downstream can re-derive it from a row that did not record it.
     *
     * @param document           the validated register document, stored exactly as it will be
     *                           rendered
     * @param command            the request this register was produced for
     * @param courtCentreOuCode  the court centre's OU code as the transformation resolved it, or
     *                           {@code null} where reference data named none
     * @param defendantType      {@code Applicant} / {@code Appellant} / {@code Respondent}, or
     *                           {@code null} where the hearing carried no court application
     * @param flagState          the cutover flag as last read, which decides whether the row is
     *                           batched automatically at all (research §12)
     * @return the row that was written and the row it superseded, if any
     */
    RecordOutcome record(DistributionCommand command, CourtRegisterDocument document,
            String courtCentreOuCode, String defendantType, RecordedFlagState flagState);

    /**
     * The registers waiting to be batched.
     *
     * <p>RECORDED, unsuperseded, unbatched and recorded while the flag was ON. The last of those four
     * is the one that is easy to forget and expensive to get wrong: a register recorded while the
     * legacy was generating may already have been sent by the legacy, and batching it would send a
     * second copy to the same Youth Offending Team.
     *
     * @return every register eligible for automatic batching, oldest first
     */
    List<RegisterRecord> activeUnbatched();

    /**
     * Groups one court centre's day into a batch and stamps its identity onto the rows.
     *
     * <p>All of it or none of it. A register that was superseded or batched elsewhere between the
     * read and the stamp means this is not the batch that was asked for, and the batch is refused
     * <em>and</em> undone: a refused batch that left its row behind would hold that court centre and
     * day against every later run, and the day would never be rendered at all.
     *
     * @param key     the court centre and register day being batched
     * @param records the registers that belong to it
     * @return the batch, carrying the identity every downstream call correlates on
     */
    RegisterBatch assemble(CourtCentreDay key, List<RegisterRecord> records);

    /**
     * Records that systemdocgenerator accepted the render request for this batch.
     *
     * @param batchId       the batch that was requested
     * @param payloadFileId the file-service id the payload was stored under
     */
    void markRequested(UUID batchId, UUID payloadFileId);

    /**
     * Records the document this batch generated, and moves this batch's rows to GENERATED.
     *
     * <p><strong>This batch's rows and no others (defect fix P3).</strong>
     *
     * <p>Which mechanism learned the outcome is an argument rather than a later write. A batch state
     * change is a compare-and-set through {@code BatchStatus}, so there is no moment either side of
     * the transition in which {@code completed_by} could be set on its own: before the mark the
     * batch is still GENERATING and the write would have to guess the outcome, and after it the only
     * move left is GENERATED to GENERATED, which the machine refuses. It travels with the mark and
     * is written by the mark's own statement.
     *
     * @param batchId        the batch the document belongs to
     * @param documentFileId the rendered document's file-service id
     * @param generatedAt    when systemdocgenerator generated it
     * @param completedBy    the mechanism that learned the document exists: the event listener or
     *                       the grace-period reconciler
     */
    void markGenerated(UUID batchId, UUID documentFileId, Instant generatedAt,
            CompletedBy completedBy);

    /**
     * Fails the batch under a bounded reason, leaving its rows where the reason says they belong.
     *
     * <p>{@code completedBy} is nullable here and only here: four of the six reasons are this
     * service's own verdict about a render it could not ask for or could not get an answer about,
     * and naming a completion mechanism for those would credit a decision nobody outside this
     * service made. The two that are somebody's answer - a {@code generation-failed} event, a
     * reconciled query - carry EVENT and RECONCILER respectively.
     *
     * @param batchId     the batch that failed
     * @param reason      the bounded reason it is failed under
     * @param sdgReason   systemdocgenerator's own words, for support only, or {@code null} where it
     *                    said nothing
     * @param completedBy the mechanism that learned the render failed, or {@code null} where this
     *                    service failed the batch on its own account
     */
    void markFailed(UUID batchId, BatchFailureReason reason, String sdgReason,
            CompletedBy completedBy);

    /**
     * Settles the batch on its notification tally, and moves its rows to NOTIFIED.
     *
     * @param batchId the batch whose recipients have all been attempted
     * @param summary the tally and the terminal state it produces
     */
    void markNotified(UUID batchId, NotificationSummary summary);
}
