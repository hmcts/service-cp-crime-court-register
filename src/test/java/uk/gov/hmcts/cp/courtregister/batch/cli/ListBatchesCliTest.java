package uk.gov.hmcts.cp.courtregister.batch.cli;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import uk.gov.hmcts.cp.courtregister.application.RegisterStore;
import uk.gov.hmcts.cp.courtregister.domain.BatchStatus;
import uk.gov.hmcts.cp.courtregister.domain.CourtCentreDay;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterDefendant;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterDocument;
import uk.gov.hmcts.cp.courtregister.domain.NotificationStatus;
import uk.gov.hmcts.cp.courtregister.domain.RecordedFlagState;
import uk.gov.hmcts.cp.courtregister.domain.RegisterBatch;
import uk.gov.hmcts.cp.courtregister.domain.RegisterNotification;
import uk.gov.hmcts.cp.courtregister.domain.RegisterRecord;
import uk.gov.hmcts.cp.courtregister.domain.StoreUnavailableException;
import uk.gov.hmcts.cp.courtregister.persistence.RegisterBatchRepository;
import uk.gov.hmcts.cp.courtregister.persistence.RegisterNotificationRepository;
import uk.gov.hmcts.cp.courtregister.support.PersonalDataMarkers;

/**
 * {@code list-batches --date D | --recorded-while-off}: the only way to see what a register date
 * holds, and the one command with cause to mention a recipient at all.
 *
 * <p>This service exposes no query endpoint, so a support call about a register that did not arrive
 * is answered from here or not at all (FR-016, design "actuator only"). Two questions, two sets of
 * rows: {@code --date} lists a date's batches with their state, their record count and each
 * recipient's outcome, and {@code --recorded-while-off} lists the records automatic batching passed
 * over because the flag did not say ON when they arrived - rows a rollback would otherwise leave
 * waiting for somebody to notice them (research §12).
 *
 * <p><strong>Exactly one of the two.</strong> A listing that silently answered the other question
 * is a rollback carried out against the wrong records, so neither and both are refusals.
 *
 * <p><strong>The output is a report about children, and this suite is what holds it to saying as
 * little as it can</strong> (constitution Principle VII):
 *
 * <ul>
 *   <li>a recipient is shown as a <strong>masked</strong> address - one character of the local part
 *       and the domain, and not even that where the local part is a single character, because an
 *       address short enough to be masked whole is published whole otherwise. Support needs to tell
 *       one team from another and to recognise a typo in a subscription; it does not need the
 *       address, and a terminal's scrollback is pasted into tickets;</li>
 *   <li><strong>no defendant detail at all</strong>, although the records the counts are taken from
 *       carry every field of a child. The count comes from the batch's own rows read back by
 *       identity, which is the same read the render payload is built from, so the documents really
 *       are in front of the command when it prints;</li>
 *   <li>and no recipient <em>name</em> either, which is reference data's text about an organisation
 *       and is not needed to answer "which of these five did not get it".</li>
 * </ul>
 *
 * <p><strong>Stable, line-oriented, and ordered by the reads rather than by this command.</strong>
 * The lines are read by {@code diff} and by eye as often as by a person scrolling, so a listing
 * whose rows moved between two runs would make a re-run look like a change. Both statements behind
 * it are ordered - batches by court house then identity, recipient rows by address - and the cases
 * here assert that the command prints what it was handed in the order it was handed it, which is
 * what makes the statements' order the whole answer.
 */
@ExtendWith(SoftAssertionsExtension.class)
@DisplayName("list-batches")
class ListBatchesCliTest {

    /** Quoted by every case, so the red run names the seam it is waiting on. */
    private static final String PENDING = "T065 implements list-batches; this is its red run";

    /** Returned where the seam refused, so an exit code that was never produced fails as one. */
    private static final int NOT_RUN = -1;

    /** The register date a support call is about. */
    private static final String TYPED_DATE = "2026-09-04";

    private static final LocalDate DATE = LocalDate.parse(TYPED_DATE);

    private static final UUID COURT_CENTRE = UUID.fromString("2a3d5e70-1c4b-4a8e-9f61-70b2c9d4e5f6");

    private static final Instant MOMENT = Instant.parse("2026-09-04T17:31:02Z");

    /** The template every register e-mail goes out under. */
    private static final UUID TEMPLATE = UUID.fromString("9c1f4b2e-88a7-4d35-b0e6-1f7a3c5d9e20");

    /** An address long enough for one character of its local part to distinguish it. */
    private static final String LONG_ADDRESS = "jane.doe@yot.example.gov.uk";

    /** The same address as an operator may see it. */
    private static final String LONG_MASKED = "j***@yot.example.gov.uk";

    /** An address whose local part is one character, so masking keeps none of it. */
    private static final String SHORT_ADDRESS = "a@yot.example.gov.uk";

    private static final String SHORT_MASKED = "***@yot.example.gov.uk";

    /** A subscription with a mistyped address, which support has to be able to recognise. */
    private static final String MALFORMED_ADDRESS = "yot.example.gov.uk";

    private static final String MALFORMED_MASKED = "***";

    /** Printed where a batch's row carries no court house, so no line is ever left truncated. */
    private static final String ABSENT = "-";

    private final RegisterBatchRepository batches = mock(RegisterBatchRepository.class);
    private final RegisterNotificationRepository notifications =
            mock(RegisterNotificationRepository.class);
    private final RegisterStore store = mock(RegisterStore.class);
    private final List<String> lines = new ArrayList<>();
    private final Consumer<String> output = lines::add;
    private final ListBatchesCli cli =
            new ListBatchesCli(batches, notifications, store, output);

    @InjectSoftAssertions
    private SoftAssertions softly;

    /**
     * Runs the command over what an operator typed.
     *
     * <p>The call is made inside {@code assertThatCode(...).doesNotThrowAnyException()} so the
     * seam's refusal is recorded as an assertion rather than ending the case, and the exit code it
     * did not produce is then asserted on as {@link #NOT_RUN}.
     *
     * @param args what an operator typed after the command's name
     * @return the exit code, or {@link #NOT_RUN} where the seam refused
     */
    private int run(final String... args) {
        final AtomicInteger code = new AtomicInteger(NOT_RUN);
        softly.assertThatCode(() -> code.set(cli.run(List.of(args))))
                .as(PENDING)
                .doesNotThrowAnyException();
        return code.get();
    }

    /** One batch's line, so the format is stated once. */
    private static String batchLine(final RegisterBatch batch, final int records,
            final int recipients) {

        return "batch=" + batch.batchId()
                + " court-house=" + (batch.courtHouse() == null ? ABSENT : batch.courtHouse())
                + " state=" + batch.status()
                + " records=" + records
                + " recipients=" + recipients;
    }

    /** One recipient's line, under the batch it belongs to. */
    private static String recipientLine(final UUID batchId, final String masked,
            final NotificationStatus outcome) {

        return "batch=" + batchId + " recipient=" + masked + " outcome=" + outcome;
    }

    /** One recorded-while-off record's line. */
    private static String recordLine(final RegisterRecord record) {
        return "record=" + record.outputId()
                + " hearing=" + record.hearingId()
                + " register-date=" + record.key().registerDate()
                + " flag=" + record.flagState();
    }

    private static RegisterBatch batch(final String courtHouse, final BatchStatus status) {
        return new RegisterBatch(UUID.randomUUID(), COURT_CENTRE, "B01LY00", courtHouse, DATE,
                "court-register_" + TYPED_DATE + "_B01LY00.pdf", UUID.randomUUID(),
                UUID.randomUUID(), status, null, null, true, null, MOMENT, MOMENT, MOMENT, null,
                null, 1, null, 0);
    }

    private static RegisterNotification notification(final UUID batchId, final String address,
            final NotificationStatus status) {

        return new RegisterNotification(UUID.randomUUID(), batchId, address,
                PersonalDataMarkers.RECIPIENT_ORGANISATION, "cr_standard", TEMPLATE, status, null,
                MOMENT, 1);
    }

    /**
     * One recorded register, carrying a child in every field the document has for one.
     *
     * <p>The markers are what the privacy cases look for. A suite searching for the word "name"
     * would fail on a field called {@code fileName}; a suite searching for a value nothing else in
     * this repository produces fails only when that value really was printed.
     */
    private static RegisterRecord record(final RecordedFlagState flagState) {
        final CourtRegisterDefendant defendant = new CourtRegisterDefendant(
                UUID.randomUUID().toString(), PersonalDataMarkers.CHILD_NAME,
                PersonalDataMarkers.DATE_OF_BIRTH, null, null, PersonalDataMarkers.ETHNICITY,
                "MALE", "Not Applicable", null, null, null, null, null, null);
        final CourtRegisterDocument document = new CourtRegisterDocument(TYPED_DATE,
                "2026-09-03T00:00:00Z", UUID.randomUUID().toString(), COURT_CENTRE.toString(),
                "court-register_" + TYPED_DATE + "_B01LY00.pdf", null, null, null,
                List.of(defendant));
        return new RegisterRecord(UUID.randomUUID(), UUID.randomUUID(), MOMENT,
                new CourtCentreDay(COURT_CENTRE, DATE), MOMENT,
                "court-register_" + TYPED_DATE + "_B01LY00.pdf", null, flagState, document);
    }

    /** Stubs a batch's two reads: how many registers it holds and who it was addressed to. */
    private void holding(final RegisterBatch batch, final List<RegisterRecord> records,
            final List<RegisterNotification> recipients) {

        when(store.batched(batch.batchId())).thenReturn(records);
        when(notifications.findByBatchId(batch.batchId())).thenReturn(recipients);
    }

    /**
     * The question a support call asks: what did this date do.
     */
    @Nested
    @DisplayName("a date's batches")
    class ADate {

        @Test
        void each_batch_should_be_listed_with_its_state_its_records_and_its_recipients() {
            final RegisterBatch batch = batch("B01LY00", BatchStatus.PARTIALLY_NOTIFIED);
            final RegisterNotification told =
                    notification(batch.batchId(), LONG_ADDRESS, NotificationStatus.ACCEPTED);
            final RegisterNotification owed =
                    notification(batch.batchId(), SHORT_ADDRESS, NotificationStatus.FAILED);
            when(batches.findByRegisterDate(DATE)).thenReturn(List.of(batch));
            holding(batch, List.of(record(RecordedFlagState.ON), record(RecordedFlagState.ON)),
                    List.of(told, owed));

            final int code = run("--date", TYPED_DATE);

            softly.assertThat(code)
                    .as("the date was read and answered")
                    .isEqualTo(CliMain.SUCCESS);
            softly.assertThat(lines)
                    .as("the batch, then one line per recipient under it: this is the whole of "
                            + "what a support call about a register that did not arrive is "
                            + "answered from, so all four facts are on it")
                    .containsExactly(
                            batchLine(batch, 2, 2),
                            recipientLine(batch.batchId(), LONG_MASKED,
                                    NotificationStatus.ACCEPTED),
                            recipientLine(batch.batchId(), SHORT_MASKED,
                                    NotificationStatus.FAILED));
        }

        @Test
        void a_batch_nobody_subscribed_to_should_be_listed_with_no_recipient_line() {
            final RegisterBatch batch = batch("B01LY00", BatchStatus.NOTIFIED_NOBODY);
            when(batches.findByRegisterDate(DATE)).thenReturn(List.of(batch));
            holding(batch, List.of(record(RecordedFlagState.ON)), List.of());

            final int code = run("--date", TYPED_DATE);

            softly.assertThat(code).as(PENDING).isEqualTo(CliMain.SUCCESS);
            softly.assertThat(lines)
                    .as("defect fix P1's terminal state read back: a document nobody subscribes to "
                            + "is finished, and the listing says so with a count of nought rather "
                            + "than by leaving the batch out")
                    .containsExactly(batchLine(batch, 1, 0));
        }

        @Test
        void a_batch_whose_row_carries_no_court_house_should_not_print_a_truncated_line() {
            final RegisterBatch batch = batch(null, BatchStatus.GENERATING);
            when(batches.findByRegisterDate(DATE)).thenReturn(List.of(batch));
            holding(batch, List.of(record(RecordedFlagState.ON)), List.of());

            final int code = run("--date", TYPED_DATE);

            softly.assertThat(code).as(PENDING).isEqualTo(CliMain.SUCCESS);
            softly.assertThat(lines)
                    .as("a key with nothing after it reads to a person, and to grep, as a line "
                            + "that was cut off; absent is said out loud instead")
                    .containsExactly("batch=" + batch.batchId() + " court-house=" + ABSENT
                            + " state=GENERATING records=1 recipients=0");
        }

        @Test
        void the_order_should_be_the_read_s_own_and_not_this_command_s() {
            final RegisterBatch first = batch("A01AA00", BatchStatus.NOTIFIED);
            final RegisterBatch second = batch("Z99ZZ00", BatchStatus.FAILED);
            when(batches.findByRegisterDate(DATE)).thenReturn(List.of(second, first));
            holding(first, List.of(record(RecordedFlagState.ON)), List.of());
            holding(second, List.of(record(RecordedFlagState.ON)), List.of());

            final int code = run("--date", TYPED_DATE);

            softly.assertThat(code).as(PENDING).isEqualTo(CliMain.SUCCESS);
            softly.assertThat(lines)
                    .as("the statement orders by court house then identity, and it is the only "
                            + "thing that orders: a command that re-sorted, grouped or ran the "
                            + "rows through a map would make the output's stability its own "
                            + "problem rather than the read's")
                    .containsExactly(batchLine(second, 1, 0), batchLine(first, 1, 0));
        }

        @Test
        void the_recipient_lines_should_keep_the_order_their_read_answered_in() {
            final RegisterBatch batch = batch("B01LY00", BatchStatus.NOTIFIED);
            final RegisterNotification alpha =
                    notification(batch.batchId(), "alpha@yot.example.gov.uk",
                            NotificationStatus.ACCEPTED);
            final RegisterNotification zulu =
                    notification(batch.batchId(), "zulu@yot.example.gov.uk",
                            NotificationStatus.ACCEPTED);
            when(batches.findByRegisterDate(DATE)).thenReturn(List.of(batch));
            holding(batch, List.of(record(RecordedFlagState.ON)), List.of(alpha, zulu));

            final int code = run("--date", TYPED_DATE);

            softly.assertThat(code).as(PENDING).isEqualTo(CliMain.SUCCESS);
            softly.assertThat(lines)
                    .as("the notification read is ordered by address so that a list a person "
                            + "compares by eye comes back the same way twice; masking must not "
                            + "reorder it")
                    .containsExactly(
                            batchLine(batch, 1, 2),
                            recipientLine(batch.batchId(), "a***@yot.example.gov.uk",
                                    NotificationStatus.ACCEPTED),
                            recipientLine(batch.batchId(), "z***@yot.example.gov.uk",
                                    NotificationStatus.ACCEPTED));
        }

        @Test
        void two_runs_over_the_same_rows_should_print_the_same_lines() {
            final RegisterBatch batch = batch("B01LY00", BatchStatus.NOTIFIED);
            when(batches.findByRegisterDate(DATE)).thenReturn(List.of(batch));
            holding(batch, List.of(record(RecordedFlagState.ON)),
                    List.of(notification(batch.batchId(), LONG_ADDRESS,
                            NotificationStatus.ACCEPTED)));

            run("--date", TYPED_DATE);
            final List<String> once = List.copyOf(lines);
            lines.clear();
            run("--date", TYPED_DATE);

            softly.assertThat(lines)
                    .as("a re-run that looked like a change would send support after a batch "
                            + "nothing happened to")
                    .isEqualTo(once);
        }

        @Test
        void a_date_with_no_batches_should_be_answered_rather_than_refused() {
            when(batches.findByRegisterDate(DATE)).thenReturn(List.of());

            final int code = run("--date", TYPED_DATE);

            softly.assertThat(code)
                    .as("a date this service generated nothing for is an answer - before cutover "
                            + "it is every date - and a refusal would read as a command that "
                            + "could not run")
                    .isEqualTo(CliMain.SUCCESS);
        }
    }

    /**
     * What may be printed about a Youth Offending Team, which is as little as tells one from
     * another.
     */
    @Nested
    @DisplayName("what it says about a recipient")
    class Recipients {

        @Test
        void an_address_should_be_masked_to_one_character_and_its_domain() {
            listing(LONG_ADDRESS);

            softly.assertThat(lines)
                    .as("enough to tell one team from another and to recognise a subscription's "
                            + "typo; a terminal's scrollback is pasted into tickets, and the "
                            + "address itself is not needed to answer which team missed a register")
                    .anyMatch(line -> line.contains("recipient=" + LONG_MASKED));
            softly.assertThat(lines)
                    .as("and the address itself is nowhere in the output")
                    .noneMatch(line -> line.contains(LONG_ADDRESS));
        }

        @Test
        void a_one_character_local_part_should_not_be_published_at_all() {
            listing(SHORT_ADDRESS);

            softly.assertThat(lines)
                    .as("keeping the first character of a local part that is only one character "
                            + "publishes the whole of it, which is masking that masks nothing")
                    .anyMatch(line -> line.contains("recipient=" + SHORT_MASKED));
            softly.assertThat(lines)
                    .as("so the local part is gone entirely")
                    .noneMatch(line -> line.contains(SHORT_ADDRESS));
        }

        @Test
        void an_address_with_no_domain_to_show_should_be_masked_whole() {
            listing(MALFORMED_ADDRESS);

            softly.assertThat(lines)
                    .as("a subscription whose address is not an address is exactly the row support "
                            + "is looking for, and printing it whole because it parsed badly is "
                            + "the one case masking must not fall through on")
                    .anyMatch(line -> line.contains("recipient=" + MALFORMED_MASKED));
            softly.assertThat(lines)
                    .as("nothing of it survives")
                    .noneMatch(line -> line.contains(MALFORMED_ADDRESS));
        }

        @Test
        void the_organisation_s_name_should_not_be_printed_at_all() {
            listing(LONG_ADDRESS);

            softly.assertThat(lines)
                    .as("reference data's text about an organisation, and not needed to answer "
                            + "which of a date's teams did not get its register")
                    .noneMatch(line -> line.contains(PersonalDataMarkers.RECIPIENT_ORGANISATION));
        }

        /** Lists one date whose one batch is addressed to one recipient at the given address. */
        private void listing(final String address) {
            final RegisterBatch batch = batch("B01LY00", BatchStatus.PARTIALLY_NOTIFIED);
            when(batches.findByRegisterDate(DATE)).thenReturn(List.of(batch));
            holding(batch, List.of(record(RecordedFlagState.ON)),
                    List.of(notification(batch.batchId(), address, NotificationStatus.FAILED)));

            softly.assertThat(run("--date", TYPED_DATE)).as(PENDING).isEqualTo(CliMain.SUCCESS);
        }
    }

    /**
     * The records automatic batching passed over, which a rollback has to be able to find.
     */
    @Nested
    @DisplayName("the records recorded while the flag was off")
    class RecordedWhileOff {

        @Test
        void each_record_should_be_listed_by_its_hearing_and_its_register_date() {
            final RegisterRecord off = record(RecordedFlagState.OFF);
            final RegisterRecord unknown = record(RecordedFlagState.UNKNOWN);
            when(store.recordedWhileOff()).thenReturn(List.of(off, unknown));

            final int code = run("--recorded-while-off");

            softly.assertThat(code)
                    .as("without this listing every such row waits for somebody to notice it, "
                            + "because no automatic run will ever pick one up")
                    .isEqualTo(CliMain.SUCCESS);
            softly.assertThat(lines)
                    .as("the hearing and the register date are what a rollback reconciles against "
                            + "the legacy's own list, and the flag state says which of the two "
                            + "reasons kept the row out")
                    .containsExactly(recordLine(off), recordLine(unknown));
        }

        @Test
        void the_date_s_batches_should_not_be_read_for_a_question_about_records() {
            when(store.recordedWhileOff()).thenReturn(List.of(record(RecordedFlagState.OFF)));

            run("--recorded-while-off");

            verifyNoInteractions(batches, notifications);
        }

        @Test
        void nothing_recorded_while_the_flag_was_off_should_be_answered_rather_than_refused() {
            when(store.recordedWhileOff()).thenReturn(List.of());

            final int code = run("--recorded-while-off");

            softly.assertThat(code)
                    .as("an empty answer is the answer a stack that has been cut over cleanly "
                            + "gives, and it is the one a rollback runbook wants to see")
                    .isEqualTo(CliMain.SUCCESS);
        }
    }

    /**
     * Every defendant on a register is a child, and the documents are in front of the command when
     * it prints.
     */
    @Nested
    @DisplayName("what it says about a defendant")
    class Defendants {

        @Test
        void a_date_s_listing_should_carry_nothing_about_a_child() {
            final RegisterBatch batch = batch("B01LY00", BatchStatus.NOTIFIED);
            when(batches.findByRegisterDate(DATE)).thenReturn(List.of(batch));
            holding(batch, List.of(record(RecordedFlagState.ON)),
                    List.of(notification(batch.batchId(), LONG_ADDRESS,
                            NotificationStatus.ACCEPTED)));

            softly.assertThat(run("--date", TYPED_DATE)).as(PENDING).isEqualTo(CliMain.SUCCESS);

            softly.assertThat(String.join(System.lineSeparator(), lines))
                    .as("the count comes from the batch's own rows read back by identity - the "
                            + "same read the render payload is built from - so the documents "
                            + "really are in front of the command; a count is all that may come "
                            + "out of them")
                    .doesNotContain(PersonalDataMarkers.CHILD_NAME,
                            PersonalDataMarkers.DATE_OF_BIRTH, PersonalDataMarkers.ETHNICITY);
        }

        @Test
        void the_recorded_while_off_listing_should_carry_nothing_about_a_child_either() {
            when(store.recordedWhileOff()).thenReturn(List.of(record(RecordedFlagState.OFF)));

            softly.assertThat(run("--recorded-while-off")).as(PENDING).isEqualTo(CliMain.SUCCESS);

            softly.assertThat(String.join(System.lineSeparator(), lines))
                    .as("the rows this listing names are whole recorded registers, and the only "
                            + "reason to name one is to say it is waiting")
                    .doesNotContain(PersonalDataMarkers.CHILD_NAME,
                            PersonalDataMarkers.DATE_OF_BIRTH, PersonalDataMarkers.ETHNICITY);
        }
    }

    /**
     * Two questions, and no way to ask both or neither.
     */
    @Nested
    @DisplayName("choosing between the two questions")
    class Selection {

        @Test
        void neither_selection_should_be_refused_without_reading_anything() {
            final int code = run();

            softly.assertThat(code)
                    .as("there is no default question: a listing that answered whichever one it "
                            + "felt like would have a rollback carried out against the wrong rows")
                    .isEqualTo(CliMain.REFUSED);
            verifyNoInteractions(batches, notifications, store);
        }

        @Test
        void both_selections_should_be_refused_without_reading_anything() {
            final int code = run("--date", TYPED_DATE, "--recorded-while-off");

            softly.assertThat(code)
                    .as("two different questions over two different sets of rows; answering both "
                            + "in one listing would make the two indistinguishable in the output")
                    .isEqualTo(CliMain.REFUSED);
            verifyNoInteractions(batches, notifications, store);
        }

        @ParameterizedTest
        @ValueSource(strings = {"last-tuesday", "2026-09-04T17:00:00Z", "", "2026-13-01"})
        void a_date_it_cannot_read_should_be_refused_rather_than_interpreted(final String typed) {
            final int code = run("--date", typed);

            softly.assertThat(code)
                    .as("a register date is a date. An instant, a month that does not exist and a "
                            + "phrase are all the same answer: say which argument could not be "
                            + "used rather than list whichever date it resolved to")
                    .isEqualTo(CliMain.REFUSED);
            verifyNoInteractions(batches, notifications, store);
        }

        @Test
        void an_argument_this_command_does_not_take_should_be_refused() {
            final int code = run("--date", TYPED_DATE, "--batch", UUID.randomUUID().toString());

            softly.assertThat(code)
                    .as("a listing narrowed to one batch is a batch read by identity, which is "
                            + "not the question this command answers; accepting the argument and "
                            + "ignoring it would answer about the whole date under a line that "
                            + "said otherwise")
                    .isEqualTo(CliMain.REFUSED);
            verifyNoInteractions(batches, notifications, store);
        }

        @Test
        void asking_for_help_should_exit_zero_and_read_nothing() {
            final int code = run("--help");

            softly.assertThat(code)
                    .as("the one argument every command takes, and the one that does nothing")
                    .isEqualTo(CliMain.SUCCESS);
            softly.assertThat(lines)
                    .as("usage says which command it is about, so an operator with five of them "
                            + "in a runbook can tell the answers apart")
                    .anyMatch(line -> line.contains(CliMain.LIST_BATCHES));
            verifyNoInteractions(batches, notifications, store);
        }
    }

    /**
     * The listing was asked for and could not be read.
     */
    @Nested
    @DisplayName("a listing that could not be read")
    class CouldNotFinish {

        @Test
        void a_store_that_went_away_should_fail_rather_than_print_half_a_date() {
            when(batches.findByRegisterDate(DATE)).thenThrow(
                    new StoreUnavailableException("list a date's batches",
                            new SQLException("connection closed")));

            final int code = run("--date", TYPED_DATE);

            softly.assertThat(code)
                    .as("a listing that exited 0 having printed nothing would tell support the "
                            + "date held no batches, which is the answer they would act on")
                    .isEqualTo(CliMain.FAILED);
            softly.assertThat(lines)
                    .as("and no batch line is printed, because none was read")
                    .noneMatch(line -> line.startsWith("batch="));
        }
    }
}
