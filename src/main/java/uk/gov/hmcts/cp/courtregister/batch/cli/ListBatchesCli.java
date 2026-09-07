package uk.gov.hmcts.cp.courtregister.batch.cli;

import java.util.List;

/**
 * {@code list-batches --date D | --recorded-while-off}.
 *
 * <p>The only way to see what a register date holds, since there is no query endpoint to ask. With
 * {@code --date} it lists the date's batches with their state, their record count and each
 * recipient's outcome, which is what a support call about a register that did not arrive is
 * answered from. With {@code --recorded-while-off} it lists instead the records that were recorded
 * while the flag said the legacy was live: automatic batching passes those over deliberately, so
 * without a command that names them a rollback would leave every one of them waiting for somebody
 * to find it.
 *
 * <p>Exactly one of the two, because they are two different questions over two different sets of
 * rows and a listing that silently answered the other one is a rollback carried out against the
 * wrong records.
 *
 * <p><strong>The output is a report about children, so it says as little as it can.</strong>
 * Batch and hearing identifiers, register dates, bounded state codes and counts, one row per line
 * in a stable order; a recipient is shown as a masked address, and no defendant, case or document
 * content appears at all (constitution Principle VII, FR-016). The order is stable because the
 * lines are read by {@code diff} and by eye as often as by a person scrolling: a listing whose rows
 * moved between two runs would make a re-run look like a change.
 *
 * <p>Collaborators - the store and the batch and notification repositories - arrive with T065; this
 * is the seam T063 is written against.
 */
public class ListBatchesCli {

    /**
     * Lists the date's batches, or the records recorded while the flag was off.
     *
     * @param args the arguments that followed {@code list-batches}
     * @return {@link CliMain#SUCCESS}, {@link CliMain#REFUSED} where neither or both selections
     *         were given, or {@link CliMain#FAILED}
     */
    public int run(final List<String> args) {
        throw new UnsupportedOperationException("T065");
    }
}
