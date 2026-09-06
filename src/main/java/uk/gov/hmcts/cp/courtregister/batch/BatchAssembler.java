package uk.gov.hmcts.cp.courtregister.batch;

import java.util.List;
import uk.gov.hmcts.cp.courtregister.domain.RegisterBatch;
import uk.gov.hmcts.cp.courtregister.domain.RegisterRecord;

/**
 * How the night's recorded registers become the night's batches.
 *
 * <p>One batch per (court centre, register date), which is the unit the whole downstream half is
 * about: one PDF, one render request, one set of Youth Offending Teams told once. The first record
 * of a group names the file, as progression named it, and the group's descriptive facts (the OU
 * code, the court house) are copied onto the batch from that record.
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
 * <p><strong>Seam only.</strong> The assembler lands with T043; until then this throws, so that
 * {@code BatchAssemblerTest} records a failing assertion rather than a compile error.
 */
public class BatchAssembler {

    /**
     * Groups the active records into the batches this run will ask to be rendered.
     *
     * @param active          the active unbatched records, as the store answered
     * @param systemGenerated true where the nightly schedule asked, false where the operations CLI
     *                        did, which is progression's own flag and is written to the batch row
     * @return one batch per key, each stamped onto the rows it was assembled from
     */
    public List<RegisterBatch> assemble(final List<RegisterRecord> active,
            final boolean systemGenerated) {
        throw new UnsupportedOperationException("T043");
    }
}
