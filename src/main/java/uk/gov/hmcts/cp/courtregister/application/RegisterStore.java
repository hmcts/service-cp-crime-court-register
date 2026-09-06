package uk.gov.hmcts.cp.courtregister.application;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import uk.gov.hmcts.cp.courtregister.domain.BatchFailureReason;
import uk.gov.hmcts.cp.courtregister.domain.CompletedBy;
import uk.gov.hmcts.cp.courtregister.domain.CourtCentreDay;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterDocument;
import uk.gov.hmcts.cp.courtregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.courtregister.domain.GuardDecision;
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
 * and it is the adapter that makes {@link #recordAndComplete} atomic - the insert, the supersession
 * of the hearing's earlier active row and the completion of the command are one transaction, so no
 * reader can see two active registers for one hearing and one day (research §8) and no delivery can
 * stop between the register and the completion it belongs to.
 *
 * <p><strong>Every {@code mark} is scoped to one batch.</strong> That is defect fix P3 stated as a
 * signature: progression flips rows by court centre, so a document generated for Monday marks
 * Tuesday's rows generated too and Tuesday's register is never sent. A batch identity is the
 * argument here precisely so the widening cannot be reintroduced without changing the port.
 */
public interface RegisterStore {

    /**
     * Records one hearing's register and completes the command that produced it, in one transaction.
     *
     * <p>One transaction, and the whole of it: the new row is inserted RECORDED, any earlier
     * RECORDED, unsuperseded, unbatched row for the same hearing within the same
     * {@link CourtCentreDay} is marked SUPERSEDED and pointed at the new one, and the completion of
     * the command is written beside them. A row that already carries a {@code batch_id} is never
     * touched - it is on its way to a PDF, and rewriting it would take a register out of a batch the
     * renderer has already been asked about.
     *
     * <p><strong>The completion is passed in rather than written here.</strong> What a completion
     * means, and what it is worth when the claim behind it has been reclaimed, belongs to
     * {@link IdempotencyGuard} and to nothing in this package; what belongs here is the commit
     * boundary the two writes share. So the caller hands over the completion as the thing to do
     * inside the transaction, the adapter runs it there, and the guard's own contract is untouched -
     * every other outcome a run can have is still written by the guard alone, outside any store.
     *
     * <p><strong>They stand or fall together.</strong> A completion that throws takes the recording
     * back with it, and so does a completion the guard did not admit: a register recorded under a
     * claim somebody else holds is a register the redelivery records again and this one only
     * supersedes. There is therefore no window in which a register is recorded against a request the
     * broker will deliver again.
     *
     * <p><strong>Idempotent on the command even so.</strong> A delivery can still arrive for a
     * command this store has recorded - the transaction committed and the broker never learned the
     * message was settled - and it is answered with the row that was already written, the row it
     * superseded included, with nothing written a second time. The guard settles most of those
     * before they reach here; this is what makes the ones that do reach here harmless.
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
     * @param completion         the completion of this command, run inside the recording's own
     *                           transaction; a decision other than
     *                           {@link GuardDecision.Complete} takes the recording back with it
     * @return the row that was written, the row it superseded if any, and what the completion
     *         answered
     */
    RecordedCompletion recordAndComplete(DistributionCommand command,
            CourtRegisterDocument document, String courtCentreOuCode, String defendantType,
            RecordedFlagState flagState, Supplier<GuardDecision> completion);

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
     * The registers one batch was assembled from, oldest first.
     *
     * <p>Read back by identity rather than carried through from the assembly, because the batch
     * identity is the only thing that says which rows a document is built from once the run has
     * stamped them - the same predicate {@link #markGenerated} moves rows under, asked in the other
     * direction. A caller that kept the grouping in memory and rendered from that would be
     * rendering from what it read before the stamp, and the stamp is the moment the batch became a
     * fact.
     *
     * <p>The order is the assembly order, because the first record names the file and the render
     * payload is progression's array of documents in the order the batch holds them.
     *
     * @param batchId the batch whose registers are wanted
     * @return the registers stamped with this batch, in the order they were assembled
     */
    List<RegisterRecord> batched(UUID batchId);

    /**
     * The batches already recorded for these keys, whatever state they reached.
     *
     * <p>What the assembler is given so that the supplementary rule can be decided at all (design
     * Q27). A key whose earlier batch is still PENDING, GENERATING or GENERATED has its registers
     * left waiting - the schema admits one in-flight batch per key - and a key whose batches are all
     * terminal may be followed by a supplementary one, which names the batch it follows and carries
     * the next index. Neither decision can be made from the registers alone, and a run that read
     * nothing here would answer "no earlier batch" for every key and assemble every late re-share as
     * if it were a day's first document.
     *
     * <p>Asked for the keys in play rather than for everything, because that is what the decision
     * needs: another court centre's finished document, or the same court centre's other day, says
     * nothing about whether this day may be rendered again.
     *
     * @param keys the court centre and register days a run holds active registers for
     * @return every batch recorded for those keys, in no particular order; empty where none of them
     *         has ever been batched
     */
    List<RegisterBatch> batchesFor(Collection<CourtCentreDay> keys);

    /**
     * Writes the batch the assembler decided on and stamps its identity onto the rows.
     *
     * <p><strong>The batch is an argument, not something this port invents.</strong> Which identity
     * a day's document is correlated on, what the file is called, which batch it follows and at what
     * supplementary index are the assembler's decisions - it is the only thing that has seen the
     * key's history - and a store that minted its own identity and named its own file would be
     * deciding all four again, differently, at the moment the rows are stamped. What the adapter
     * still owns is the moment ({@code assembled_at}) and the OU code, which is a column of the
     * register's own row and is copied from it.
     *
     * <p>All of it or none of it. A register that was superseded or batched elsewhere between the
     * read and the stamp means this is not the batch that was asked for, and the batch is refused
     * <em>and</em> undone: a refused batch that left its row behind would hold that court centre and
     * day against every later run, and the day would never be rendered at all.
     *
     * @param batch   the batch as the assembler decided it: identity, file name, trigger source and
     *                the supplementary link
     * @param records the registers that belong to it, in the order the batch holds them
     * @return the batch as the row now stands, carrying the stamps the database made
     */
    RegisterBatch assemble(RegisterBatch batch, List<RegisterRecord> records);

    /**
     * Records the file-service id this batch's payload is about to be written under.
     *
     * <p>Before the write, and that is the whole of why this is a separate mark. An id minted, used
     * for an insert and only then written down is an id that exists in the file service and nowhere
     * in this service if the pod dies in between - a payload nothing points at, and, if the render
     * request got out first, a document that comes back attributable to nothing. The batch stays
     * PENDING: the id says which payload the render will be about, not that one was asked for.
     *
     * @param batchId       the batch the payload belongs to
     * @param payloadFileId the file-service id the caller minted for it
     */
    void markPayloadMinted(UUID batchId, UUID payloadFileId);

    /**
     * Records that systemdocgenerator accepted the render request for this batch.
     *
     * <p>The payload id is named again rather than assumed from {@link #markPayloadMinted}, because
     * this is the statement that moves the batch and a move that read a column it also depends on
     * would be two reads of one fact. The two must be the same id; a run that passed a different one
     * would be saying the render it just had accepted was about a payload it never stored.
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
