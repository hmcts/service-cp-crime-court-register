package uk.gov.hmcts.cp.courtregister.batch.cli;

import java.util.List;
import java.util.function.Consumer;
import uk.gov.hmcts.cp.courtregister.application.RegisterStore;
import uk.gov.hmcts.cp.courtregister.persistence.RegisterBatchRepository;
import uk.gov.hmcts.cp.courtregister.persistence.RegisterNotificationRepository;

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
 * <p>The store, the two repositories and the stream are held here and read by T065; this is the
 * seam T063 is written against.
 */
// PMD.UnusedPrivateField: the collaborators the body T065 lands reads. They are constructor
// arguments now rather than then so that T063's cases can put a date's batches, their recipients
// and a stream in front of the command and assert exactly what an operator would see.
@SuppressWarnings("PMD.UnusedPrivateField")
public class ListBatchesCli {

    /** The date's batches, read in the order the statement puts them in. */
    private final RegisterBatchRepository batches;

    /** Each batch's recipient rows, which is where an outcome per team comes from. */
    private final RegisterNotificationRepository notifications;

    /**
     * The register rows: how many a batch holds, and which of them were recorded while the flag was
     * off.
     */
    private final RegisterStore store;

    /** Where the listing is written, one line per call. */
    private final Consumer<String> output;

    /**
     * Creates the command over the two reads a listing is built from and the operator's own stream.
     *
     * @param batchRepository        the date's batches
     * @param notificationRepository each batch's recipient rows
     * @param registerStore          the register rows behind a batch's count and the
     *                               recorded-while-off listing
     * @param lines                  where the listing is written, one line per call
     */
    public ListBatchesCli(final RegisterBatchRepository batchRepository,
            final RegisterNotificationRepository notificationRepository,
            final RegisterStore registerStore, final Consumer<String> lines) {
        this.batches = batchRepository;
        this.notifications = notificationRepository;
        this.store = registerStore;
        this.output = lines;
    }

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
