package uk.gov.hmcts.cp.courtregister.batch.cli;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.courtregister.application.RegisterStore;
import uk.gov.hmcts.cp.courtregister.domain.RegisterBatch;
import uk.gov.hmcts.cp.courtregister.domain.RegisterNotification;
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
 * <p>Nothing here sorts, groups or de-duplicates. Both statements behind the listing are ordered -
 * batches by court house then identity, recipient rows by address - and this command prints what it
 * was handed in the order it was handed it, which is what makes the read's order the whole answer
 * rather than this class's.
 */
public class ListBatchesCli {

    /**
     * What the command takes, printed under a refusal and on request.
     *
     * <p>Package-visible because {@link CliMain} answers {@code --help} with it before it resolves
     * a single bean: a pod with the downstream half switched off holds none of this command's
     * collaborators, and what the command takes is still the answer to what was asked.
     */
    /* default */ static final String USAGE = "usage: " + CliMain.LIST_BATCHES + " --" + Args.DATE
            + " D | --" + Args.RECORDED_WHILE_OFF + " (exactly one of the two)";

    /** Printed where a batch's row carries no court house, so no line is left truncated. */
    private static final String ABSENT = "-";

    /** What survives of an address whose local part is too short to keep a character of. */
    private static final String MASK = "***";

    /** What separates a local part from the domain it is at. */
    private static final char AT = '@';

    /** The one key a batch's own line and its recipients' lines are written under. */
    private static final String BATCH = "batch=";

    private static final Logger LOG = LoggerFactory.getLogger(ListBatchesCli.class);

    /** What this command could not finish, as the bounded reason the line carries. */
    private static final String NOT_LISTED = "listing-failed";

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
    // PMD.OnlyOneReturn: the five exits are the five things that can happen to an invocation, each
    // said where it is decided; one exit would carry a verdict past the reads that must not be made
    // once the selection has been refused.
    @SuppressWarnings("PMD.OnlyOneReturn")
    public int run(final List<String> args) {
        final Args parsed;
        try {
            parsed = Args.parse(args);
        } catch (IllegalArgumentException notUsable) {
            return CliMain.unreadable(CliMain.LIST_BATCHES, USAGE, notUsable, output);
        }
        if (parsed.askedForHelp()) {
            output.accept(USAGE);
            return CliMain.SUCCESS;
        }
        if (!parsed.permits(Set.of(Args.DATE), Set.of(Args.RECORDED_WHILE_OFF))) {
            return refuse(CliMain.UNEXPECTED_ARGUMENT);
        }
        final String typed = parsed.options().get(Args.DATE);
        final boolean byDate = typed != null;
        final boolean whileOff = parsed.flags().contains(Args.RECORDED_WHILE_OFF);
        if (byDate == whileOff) {
            return refuse(CliMain.MISSING_ARGUMENT);
        }
        return byDate ? listDate(typed) : listRecordedWhileOff();
    }

    /**
     * Lists one register date's batches, each with its own recipients under it.
     *
     * @param typed the date as an operator typed it
     * @return {@link CliMain#SUCCESS} where the date was read and answered,
     *         {@link CliMain#REFUSED} where it is not a date, or {@link CliMain#FAILED} where the
     *         rows could not be read
     */
    // PMD.OnlyOneReturn: a date that is not a date is refused where it is read, and saying so there
    // is what keeps the two reads below out of an invocation that has no date to make them for.
    @SuppressWarnings("PMD.OnlyOneReturn")
    private int listDate(final String typed) {
        final LocalDate registerDate;
        try {
            registerDate = LocalDate.parse(typed);
        } catch (DateTimeParseException notADate) {
            return CliMain.unreadable(CliMain.LIST_BATCHES, USAGE, notADate, output);
        }
        return listed("date=" + registerDate, () -> {
            for (final RegisterBatch batch : batches.findByRegisterDate(registerDate)) {
                print(batch);
            }
        });
    }

    /**
     * One batch's line, and one line for each recipient it was addressed to.
     *
     * <p>The record count is the batch's own rows read back by identity - the same read the render
     * payload is built from, so the documents really are in front of the command as it prints - and
     * a count is all that may come out of them.
     *
     * @param batch the batch as the date's read answered it
     */
    private void print(final RegisterBatch batch) {
        final int records = store.batched(batch.batchId()).size();
        final List<RegisterNotification> recipients =
                notifications.findByBatchId(batch.batchId());
        output.accept(BATCH + batch.batchId()
                + " court-house=" + orAbsent(batch.courtHouse())
                + " state=" + batch.status()
                + " records=" + records
                + " recipients=" + recipients.size());
        recipients.forEach(recipient -> output.accept(BATCH + batch.batchId()
                + " recipient=" + masked(recipient.emailAddress())
                + " outcome=" + recipient.status()));
    }

    /**
     * Lists the records automatic batching passed over because the flag did not say ON.
     *
     * <p>The date's two reads are not made at all: this is a question about registers rather than
     * about batches, and a listing that read both would put a date's documents under a heading
     * about rows that never reached one.
     *
     * @return {@link CliMain#SUCCESS} where the rows were read, {@link CliMain#FAILED} where they
     *         could not be
     */
    private int listRecordedWhileOff() {
        return listed("selection=" + Args.RECORDED_WHILE_OFF, () -> store.recordedWhileOff()
                .forEach(waiting -> output.accept("record=" + waiting.outputId()
                        + " hearing=" + waiting.hearingId()
                        + " register-date=" + waiting.key().registerDate()
                        + " flag=" + waiting.flagState())));
    }

    /**
     * Runs one listing, or says it could not be read rather than printing half of it.
     *
     * <p>A listing that exited 0 having printed nothing would tell support the date held no
     * batches, which is the answer they would act on - so a read that failed is
     * {@link CliMain#FAILED} and says which of the two questions it was about.
     *
     * @param subject which question was asked, as one {@code key=value} pair
     * @param listing the reads and the lines they produce
     * @return {@link CliMain#SUCCESS} where the listing was written, {@link CliMain#FAILED} where
     *         it could not be
     */
    // PMD.AvoidCatchingGenericException: the store translates an outage into its own unchecked type
    // and a statement can refuse with another; both mean the same thing here - this listing was not
    // read - and a partial listing under exit 0 is the worst answer this command could give.
    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn"})
    private int listed(final String subject, final Runnable listing) {
        try {
            listing.run();
            return CliMain.SUCCESS;
        } catch (RuntimeException notRead) {
            LOG.error("The listing {} could not be read, so no rows are reported for it. cause={}",
                    subject, notRead.getClass().getName(), notRead);
            return CliMain.failure(CliMain.LIST_BATCHES, subject, NOT_LISTED, output);
        }
    }

    /**
     * A court house, or the absence of one said out loud.
     *
     * <p>A key with nothing after it reads to a person, and to grep, as a line that was cut off.
     *
     * @param courtHouse the court house the batch's row carries, or {@code null}
     * @return the court house, or {@link #ABSENT}
     */
    private static String orAbsent(final String courtHouse) {
        return courtHouse == null || courtHouse.isEmpty() ? ABSENT : courtHouse;
    }

    /**
     * One recipient's address, masked to as little as tells one team from another.
     *
     * <p>One character of the local part and the domain - and not even that where the local part is
     * a single character, because an address short enough to be masked whole is published whole
     * otherwise. An address with no domain to show is masked entirely: a subscription whose address
     * is not an address is exactly the row support is looking for, and printing it because it parsed
     * badly is the one case masking must not fall through on.
     *
     * @param address the address the notification row holds
     * @return the masked address, which is never the address
     */
    private static String masked(final String address) {
        // Absent, and an address with nothing before or after its at-sign, all mask to the same
        // thing: the mask is what is always printed, and the two halves are what may survive it.
        final int at = address == null ? -1 : address.lastIndexOf(AT);
        final String local = at > 0 ? address.substring(0, at) : "";
        final String kept = local.length() > 1 ? local.substring(0, 1) : "";
        return kept + MASK + (at < 0 ? "" : address.substring(at));
    }

    /**
     * Declines, under the bounded reason and this command's own usage.
     *
     * @param reason one of {@link CliMain}'s three argument reasons
     * @return {@link CliMain#REFUSED}
     */
    private int refuse(final String reason) {
        return CliMain.refusal(CliMain.LIST_BATCHES, USAGE, reason, output);
    }
}
