package uk.gov.hmcts.cp.courtregister.batch;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import uk.gov.hmcts.cp.courtregister.domain.AssembledBatch;
import uk.gov.hmcts.cp.courtregister.domain.BatchAssembly;
import uk.gov.hmcts.cp.courtregister.domain.BatchStatus;
import uk.gov.hmcts.cp.courtregister.domain.CourtCentreDay;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterHearingVenue;
import uk.gov.hmcts.cp.courtregister.domain.RecordedFlagState;
import uk.gov.hmcts.cp.courtregister.domain.RegisterBatch;
import uk.gov.hmcts.cp.courtregister.domain.RegisterRecord;

/**
 * How the night's recorded registers become the night's batches.
 *
 * <p>One batch per (court centre, register date), which is the unit the whole downstream half is
 * about: one PDF, one render request, one set of Youth Offending Teams told once. The first record
 * of a group names the file, as progression named it, and the group's descriptive facts are copied
 * onto the batch from that record.
 *
 * <p><strong>Recorded-while-off rows are not assembled.</strong> A register recorded while the
 * cutover flag was off belongs to the legacy pipeline, which has already generated that day, and
 * assembling it would send a second document for a day nobody asked twice about (research §12,
 * FR-015). Superseded rows and rows already carrying a batch are outside the sweep for the same
 * reason: the store's {@code activeUnbatched} predicate is what "active" means, and this class does
 * not widen it.
 *
 * <p><strong>Supplementary batches (design Q27).</strong> A hearing re-shared after its key's batch
 * has finished is a fresh active row for a key that has already been rendered. Once every earlier
 * batch for that key is terminal, its rows are assembled into a supplementary batch that names the
 * batch it follows in {@code supplement_of} and carries the next {@code supplement_index}; while any
 * batch for the key is still in flight the rows wait, because the schema admits one in-flight batch
 * per key. The supplementary batch's file name is the first row's {@code fileName} with
 * {@code -supplementary-<index>} inserted before the extension, so
 * {@code courtregister_2026-08-20.json} is followed by
 * {@code courtregister_2026-08-20-supplementary-1.json}.
 *
 * <p><strong>What is decided here and what the stamp decides.</strong> The batch this class answers
 * with is the grouping's own statement about itself: the identity every downstream call correlates
 * on, the file the day will be rendered under, the trigger that asked and the supplementary link.
 * The moment of assembly is not, and neither is the OU code. {@code assembled_at} belongs to the
 * statement that stamps the rows, so a reading of now taken here would name a moment no row
 * records; the OU code is a column of the register's own row, which {@link RegisterRecord} does not
 * carry and {@code RegisterStore.assemble} copies from the row itself.
 */
public class BatchAssembler {

    /**
     * The states in which a key's batch is still owed something, and the key may not be batched.
     *
     * <p>The three the partial unique index names: a render request, a render outcome or an e-mail
     * is still outstanding, so a second live batch for the same key is the write the index refuses.
     */
    private static final Set<BatchStatus> IN_FLIGHT =
            Set.of(BatchStatus.PENDING, BatchStatus.GENERATING, BatchStatus.GENERATED);

    /** What a supplement's index is written into the day's file name as. */
    private static final String SUPPLEMENT_MARKER = "-supplementary-";

    /**
     * Which of a key's earlier batches a supplement follows.
     *
     * <p>The highest index, so the order the batches were read in decides nothing; the identity
     * breaks a tie the index cannot, so a history that somehow held two batches at one index still
     * assembles the same way twice.
     */
    private static final Comparator<RegisterBatch> BY_SUPPLEMENT_INDEX =
            Comparator.comparingInt(RegisterBatch::supplementIndex)
                    .thenComparing(RegisterBatch::batchId);

    /**
     * Groups the active records into the batches this run will ask to be rendered.
     *
     * <p>The batches already recorded for the keys in play are an argument rather than a read of
     * this class's own, so the grouping stays a function of what it was given: the same registers
     * and the same history assemble the same way, in a test and at 18:00 (constitution Principle
     * V). They are what the supplementary rule is decided from - the next index counts up from the
     * highest one already recorded for the key, and a key with a batch still in flight is left for
     * a later run rather than given a second live one.
     *
     * @param active          the active unbatched records, as the store answered
     * @param existing        every batch already recorded for the keys those records fall under,
     *                        whatever state it reached; empty on a key being rendered for the first
     *                        time
     * @param systemGenerated true where the nightly schedule asked, false where the operations CLI
     *                        did, which is progression's own flag and is written to the batch row
     * @return one batch per key it could assemble, each beside the registers it was assembled from,
     *         and the keys it left waiting
     */
    public BatchAssembly assemble(final List<RegisterRecord> active,
            final List<RegisterBatch> existing, final boolean systemGenerated) {

        final List<AssembledBatch> assembled = new ArrayList<>();
        final List<CourtCentreDay> deferred = new ArrayList<>();

        groupByKey(active).forEach((key, records) -> {
            final List<RegisterBatch> history = recordedFor(key, existing);
            if (history.stream().anyMatch(batch -> IN_FLIGHT.contains(batch.status()))) {
                deferred.add(key);
            } else {
                assembled.add(new AssembledBatch(
                        batch(key, records, history, systemGenerated), records));
            }
        });

        return new BatchAssembly(assembled, deferred);
    }

    /**
     * The registers this run may batch, gathered under the key each belongs to.
     *
     * <p>Insertion-ordered, and the order is the store's: the run requests one batch at a time
     * against a deadline, so which batches a deadline cuts off is decided by the registers' own
     * order rather than by whatever a hash map answered with.
     *
     * @param active the active unbatched records, as the store answered
     * @return the ON records of each key, in the order they were recorded, keys in first-seen order
     */
    private static Map<CourtCentreDay, List<RegisterRecord>> groupByKey(
            final List<RegisterRecord> active) {

        // Not ON is the legacy's row: OFF says the legacy was generating, UNKNOWN says nobody could
        // tell, and batching either sends a Youth Offending Team the same day twice.
        return active.stream()
                .filter(register -> register.flagState() == RecordedFlagState.ON)
                .collect(Collectors.groupingBy(RegisterRecord::key, LinkedHashMap::new,
                        Collectors.toList()));
    }

    /**
     * The batches already recorded for one key.
     *
     * <p>Filtered per key, because the supplementary link is per key: another court centre's
     * finished document, or the same court centre's other day, says nothing about whether this day
     * may be rendered again, and a first batch that claimed to follow one would name a document for
     * a different set of children.
     *
     * @param key      the court centre and register day being assembled
     * @param existing every batch already recorded for the keys this run holds records for
     * @return the ones recorded for this key, in the order they were given
     */
    private static List<RegisterBatch> recordedFor(final CourtCentreDay key,
            final List<RegisterBatch> existing) {

        return existing.stream().filter(batch -> key.equals(batch.key())).toList();
    }

    /**
     * One key's batch, as it stands before anything is asked of the renderer.
     *
     * <p>PENDING and nothing else: a batch assembled further along the machine would carry stamps
     * for events that never happened.
     *
     * @param key             the court centre and register day being assembled
     * @param records         the registers it groups, the first of which names the file
     * @param history         the batches already recorded for this key, all of them terminal
     * @param systemGenerated whether the nightly schedule asked, rather than an operator
     * @return the batch this run will ask to be rendered
     */
    private static RegisterBatch batch(final CourtCentreDay key,
            final List<RegisterRecord> records, final List<RegisterBatch> history,
            final boolean systemGenerated) {

        final RegisterRecord first = records.getFirst();
        final RegisterBatch follows = history.stream().max(BY_SUPPLEMENT_INDEX).orElse(null);
        final int index = follows == null ? 0 : follows.supplementIndex() + 1;
        final String fileName = index == 0
                ? first.fileName()
                : supplementaryName(first.fileName(), index);

        return new RegisterBatch(UUID.randomUUID(), key.courtCentreId(), null, courtHouse(first),
                key.registerDate(), fileName, null, null, BatchStatus.PENDING, null, null,
                systemGenerated, null, null, null, null, null, null, 0,
                follows == null ? null : follows.batchId(), index);
    }

    /**
     * The day's second document named so that a reader who has only the names can order them.
     *
     * <p>Built from the register's own file name rather than from the last supplement's, because a
     * name built from a name accumulates the suffix and a day's third document would be called
     * {@code -supplementary-1-supplementary-2}. A name with no extension takes the marker at the
     * end, which is the same statement in the only other shape a file name comes in.
     *
     * @param name  the first register's file name, as progression named the document
     * @param index this supplement's index, counting up from 1
     * @return the name with {@code -supplementary-<index>} before the extension
     */
    private static String supplementaryName(final String name, final int index) {
        final int extension = name.lastIndexOf('.');
        final String marked = SUPPLEMENT_MARKER + index;
        return extension <= 0
                ? name + marked
                : name.substring(0, extension) + marked + name.substring(extension);
    }

    /**
     * The court house the batch is described by, from the register that names the file.
     *
     * <p>Descriptive and not a key: the batch is grouped by court centre and day, and the court
     * house is progression's own column, carried so that a batch reads as something a person can
     * recognise.
     *
     * @param first the first register the batch holds
     * @return that register's court house, or {@code null} where the document named no venue
     */
    private static String courtHouse(final RegisterRecord first) {
        final CourtRegisterHearingVenue venue = first.document().hearingVenue();
        return venue == null ? null : venue.courtHouse();
    }
}
