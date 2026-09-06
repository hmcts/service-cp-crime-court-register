package uk.gov.hmcts.cp.courtregister.persistence;

import static org.assertj.core.api.Assertions.tuple;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import uk.gov.hmcts.cp.courtregister.application.RecordOutcome;
import uk.gov.hmcts.cp.courtregister.application.RegisterStore;
import uk.gov.hmcts.cp.courtregister.domain.CourtCentreDay;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterDefendant;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterDocument;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterHearingVenue;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterRecipient;
import uk.gov.hmcts.cp.courtregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.courtregister.domain.RecordedFlagState;
import uk.gov.hmcts.cp.courtregister.domain.RegisterBatch;
import uk.gov.hmcts.cp.courtregister.domain.RegisterRecord;
import uk.gov.hmcts.cp.courtregister.domain.RequestFingerprint;
import uk.gov.hmcts.cp.courtregister.domain.RunClaim;
import uk.gov.hmcts.cp.courtregister.support.PostgresTestSupport;
import uk.gov.hmcts.cp.courtregister.support.ProcessedLogTestSupport;

/**
 * The register store, against a real Postgres.
 *
 * <p>This is the port that replaces the POST. Everything increment 001 assembled now ends as a row
 * here, and three properties decide whether the register a Youth Offending Team eventually receives
 * is the right one: the recording is atomic with the supersession it causes, a row that is already
 * on its way to a PDF is never rewritten underneath the renderer, and a completion belongs to the
 * batch it was reported for and to no other.
 *
 * <p>The last of those is defect fix <strong>P3</strong>, pinned here by
 * {@link Generation#generation_flips_only_the_batchs_own_rows()}. Progression flips rows by court
 * centre ({@code CourtRegisterRequestRepository}'s read-side sweep), so a document generated for
 * Monday marks Tuesday's rows generated too and Tuesday's register is never sent at all. The test
 * seeds two days at one court centre, generates one of them, and counts: the count is what fails
 * against the progression behaviour, and no assertion about a single day could.
 *
 * <p><strong>Soft assertions throughout, deliberately.</strong> Under the TDD red-run convention
 * this class is written before {@link JdbcRegisterStore} has any statements in it, and every port
 * call therefore refuses. A hard assertion would stop each case at the first refusal and the
 * recorded red would be a stack trace from the arrangement rather than the property under test. Each
 * case instead drives the whole scenario through the port inside one
 * {@code assertThatCode(...).doesNotThrowAnyException()} - which records the refusal rather than
 * swallowing it - and then asserts on rows read straight back out of the database, so the red run is
 * the row count and the green run is the same assertions unchanged.
 *
 * <p>Every case mints its own court centre, so the several suites sharing one container share no
 * rows and none of them needs to truncate a table another is using. The store's own reads answer for
 * the whole table, so {@link #mine(List)} narrows them to the case that asked.
 */
@ExtendWith(SoftAssertionsExtension.class)
@DisplayName("register store")
class RegisterStoreIT {

    private static final ZoneId LONDON = ZoneId.of("Europe/London");

    private static final Duration LEASE = Duration.ofMinutes(5);

    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 24);
    private static final LocalDate TUESDAY = LocalDate.of(2026, 8, 25);

    private static final Instant MONDAY_SHARED = Instant.parse("2026-08-24T09:00:00Z");
    private static final Instant MONDAY_RESHARED = Instant.parse("2026-08-24T16:30:00Z");
    private static final Instant TUESDAY_SHARED = Instant.parse("2026-08-25T09:00:00Z");

    private static final Instant HEARING_DATE = Instant.parse("2026-08-19T00:00:00Z");

    private static final UUID HEARING_ONE = UUID.fromString("1828f356-f746-4f2d-932b-79ef2df95c80");
    private static final UUID HEARING_TWO = UUID.fromString("6b0d5a1f-4c8e-4a92-8f31-2d7c6e05b114");
    private static final UUID HEARING_THREE =
            UUID.fromString("c41e9a37-05b2-4f6d-9e18-7a3b2c5d8064");
    private static final UUID HEARING_FOUR = UUID.fromString("9d2c7b48-3e15-4a70-b6c9-0f8e1d4a2537");

    private static final UUID PAYLOAD_FILE_ID =
            UUID.fromString("5e08b6d1-92a7-4c33-8f10-6b4d3e79a281");

    private static final UUID DOCUMENT_FILE_ID =
            UUID.fromString("3a7f1c92-6d84-4b05-9e73-1c2b8a4e07d5");

    private static final Instant GENERATED_AT = Instant.parse("2026-08-24T18:04:11Z");

    private static final String OU_CODE = "B01LY00";

    private static final String APPLICANT = "Applicant";
    private static final String RESPONDENT = "Respondent";

    private static final String RECORDED = "RECORDED";
    private static final String SUPERSEDED = "SUPERSEDED";
    private static final String GENERATED = "GENERATED";

    /** The refusal every port call makes until T015 replaces it with a statement. */
    private static final String PENDING = "T015 implements the register store; this is its red run";

    @InjectSoftAssertions
    private SoftAssertions softly;

    /** This case's court centre: minted per test so no case can read another's rows. */
    private final UUID courtCentre = UUID.randomUUID();

    private final RegisterStore store =
            new JdbcRegisterStore(ProcessedLogTestSupport.jdbcClient());

    @BeforeAll
    static void migrate() {
        PostgresTestSupport.applyFlyway();
    }

    /**
     * A recorded register is the row the whole downstream half is written against, so the two things
     * that decide what a batch can do with it - what it says, and that there is exactly one of it -
     * are asserted before anything else.
     */
    @Nested
    @DisplayName("recording a register")
    class Recording {

        @Test
        void recording_a_register_should_write_one_recorded_row_for_the_command() {
            final DistributionCommand command = seededCommand(HEARING_ONE, MONDAY_SHARED);

            softly.assertThatCode(() -> store.record(
                            command,
                            document(HEARING_ONE, MONDAY, MONDAY_SHARED),
                            APPLICANT,
                            RecordedFlagState.ON))
                    .as(PENDING)
                    .doesNotThrowAnyException();

            softly.assertThat(rowsAtCourtCentre())
                    .as("one command, one register: the row is written in the transaction that "
                            + "completes the command, and nothing is sent to progression")
                    .isEqualTo(1);
            softly.assertThat(statusOf(command))
                    .as("RECORDED is where a register waits for 18:00, not PENDING - which in this "
                            + "store still means a POST that has not settled")
                    .contains(RECORDED);
        }

        /**
         * Everything the batch half reads, read back through the port that will read it.
         *
         * <p>The document above all: it is the thing that was validated, and the payload mapper
         * renders it rather than re-deriving anything from the hearing, so a store that dropped a
         * field here would produce a PDF nobody could tell was wrong.
         */
        @Test
        void a_recorded_register_should_read_back_with_its_hearing_document_and_flag_state() {
            final DistributionCommand command = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final CourtRegisterDocument document = document(HEARING_ONE, MONDAY, MONDAY_SHARED);

            softly.assertThatCode(() -> store.record(
                            command, document, RESPONDENT, RecordedFlagState.ON))
                    .as(PENDING)
                    .doesNotThrowAnyException();

            final List<RegisterRecord> active = activeUnbatched();
            softly.assertThat(active)
                    .extracting(RegisterRecord::hearingId, RegisterRecord::hearingDate,
                            RegisterRecord::registerTime, RegisterRecord::defendantType,
                            RegisterRecord::flagState)
                    .as("the hearing, the instant that decides which re-share wins, the defendant "
                            + "type the register is rendered under, and the flag as it stood")
                    .containsExactly(tuple(HEARING_ONE, HEARING_DATE, MONDAY_SHARED, RESPONDENT,
                            RecordedFlagState.ON));
            softly.assertThat(active)
                    .extracting(RegisterRecord::key, RegisterRecord::fileName,
                            RegisterRecord::document)
                    .as("the key the row is batched under is the London day of the register "
                            + "instant, and the document is stored exactly as it will be rendered")
                    .containsExactly(tuple(new CourtCentreDay(courtCentre, MONDAY),
                            fileName(HEARING_ONE, MONDAY), document));
        }
    }

    /**
     * Supersession, settled in the write transaction rather than swept up on the read side.
     *
     * <p>Progression takes the greatest {@code register_time} per hearing when it generates, which
     * gets the same answer most nights and cannot say, between two reads, which register will not be
     * sent. Here the recording that replaces a register is the event that says so, and the row it
     * replaced is named (research §8).
     */
    @Nested
    @DisplayName("superseding an earlier register")
    class Supersession {

        @Test
        void a_re_share_for_the_same_day_should_supersede_the_hearings_earlier_row() {
            final DistributionCommand first = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand second = seededCommand(HEARING_ONE, MONDAY_RESHARED);
            final AtomicReference<RecordOutcome> reshare = new AtomicReference<>();

            softly.assertThatCode(() -> {
                store.record(first, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                reshare.set(store.record(second,
                        document(HEARING_ONE, MONDAY, MONDAY_RESHARED), APPLICANT,
                        RecordedFlagState.ON));
            }).as(PENDING).doesNotThrowAnyException();

            softly.assertThat(rowsAtCourtCentre())
                    .as("both registers are kept: the earlier one is evidence of what was assembled "
                            + "before the results were shared again")
                    .isEqualTo(2);
            softly.assertThat(statusOf(first))
                    .as("the later register wins on register_time")
                    .contains(SUPERSEDED);
            softly.assertThat(statusOf(second)).contains(RECORDED);
            softly.assertThat(reshare.get())
                    .as("the row that will not be sent is named rather than counted, or support is "
                            + "left to guess which register the batch dropped")
                    .extracting(RecordOutcome::supersededOutputId)
                    .isEqualTo(outputIdOf(first).orElse(null));
        }

        @Test
        void a_re_share_should_leave_exactly_one_active_row_for_the_hearing_and_day() {
            final DistributionCommand first = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand second = seededCommand(HEARING_ONE, MONDAY_RESHARED);

            softly.assertThatCode(() -> {
                store.record(first, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                store.record(second, document(HEARING_ONE, MONDAY, MONDAY_RESHARED), APPLICANT,
                        RecordedFlagState.ON);
            }).as(PENDING).doesNotThrowAnyException();

            softly.assertThat(activeUnbatched())
                    .as("two active rows for one hearing and one day would put the same hearing on "
                            + "one PDF twice")
                    .extracting(RegisterRecord::registerTime)
                    .containsExactly(MONDAY_RESHARED);
        }

        /**
         * The row a renderer has already been asked about is not the store's to rewrite.
         *
         * <p>Once a row carries a batch, systemdocgenerator has been handed a payload built from it
         * and an event will arrive naming that batch. Superseding it would take a register out of a
         * PDF that already contains it, and the batch's own completion would then flip a row that
         * says it belongs to nothing.
         */
        @Test
        void a_batched_row_should_never_be_superseded_by_a_later_re_share() {
            final DistributionCommand first = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand second = seededCommand(HEARING_ONE, MONDAY_RESHARED);
            final AtomicReference<RecordOutcome> reshare = new AtomicReference<>();

            softly.assertThatCode(() -> {
                store.record(first, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                store.assemble(new CourtCentreDay(courtCentre, MONDAY),
                        mine(store.activeUnbatched()));
                reshare.set(store.record(second,
                        document(HEARING_ONE, MONDAY, MONDAY_RESHARED), APPLICANT,
                        RecordedFlagState.ON));
            }).as(PENDING).doesNotThrowAnyException();

            softly.assertThat(statusOf(first))
                    .as("a row on its way to a PDF is left exactly as the payload described it")
                    .contains(RECORDED);
            softly.assertThat(statusOf(second)).contains(RECORDED);
            softly.assertThat(reshare.get())
                    .as("nothing was superseded, so nothing is named")
                    .extracting(RecordOutcome::supersededOutputId)
                    .isNull();
            softly.assertThat(rowsAtCourtCentre()).isEqualTo(2);
        }

        /**
         * The cross-date flip, refused at the write. Two sittings of one hearing on two days are two
         * registers for two court days, and the second is not a correction of the first.
         */
        @Test
        void a_re_share_on_a_later_day_should_start_a_fresh_row_of_its_own() {
            final DistributionCommand monday = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand tuesday = seededCommand(HEARING_ONE, TUESDAY_SHARED);

            softly.assertThatCode(() -> {
                store.record(monday, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                store.record(tuesday, document(HEARING_ONE, TUESDAY, TUESDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
            }).as(PENDING).doesNotThrowAnyException();

            softly.assertThat(statusOf(monday))
                    .as("Monday's register is not superseded by Tuesday's sitting")
                    .contains(RECORDED);
            softly.assertThat(statusOf(tuesday)).contains(RECORDED);
            softly.assertThat(activeUnbatched())
                    .as("both days wait for their own batch")
                    .extracting(RegisterRecord::key)
                    .containsExactlyInAnyOrder(new CourtCentreDay(courtCentre, MONDAY),
                            new CourtCentreDay(courtCentre, TUESDAY));
        }
    }

    /**
     * What the nightly job is allowed to pick up.
     *
     * <p>Three exclusions, and the third is the one that is easy to forget and expensive to get
     * wrong: a register recorded while the cutover flag was not ON may already have been sent by the
     * legacy, and batching it would send a second copy of the same day's register to the same Youth
     * Offending Team (research §12).
     */
    @Nested
    @DisplayName("the registers waiting to be batched")
    class ActiveUnbatched {

        @Test
        void the_unbatched_registers_should_exclude_rows_already_stamped_with_a_batch() {
            final DistributionCommand batched = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand waiting = seededCommand(HEARING_TWO, MONDAY_SHARED);

            softly.assertThatCode(() -> {
                store.record(batched, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                store.assemble(new CourtCentreDay(courtCentre, MONDAY),
                        mine(store.activeUnbatched()));
                store.record(waiting, document(HEARING_TWO, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
            }).as(PENDING).doesNotThrowAnyException();

            softly.assertThat(activeUnbatched())
                    .as("a batched row is somebody else's work in progress; re-assembling it would "
                            + "put one hearing on two PDFs")
                    .extracting(RegisterRecord::hearingId)
                    .containsExactly(HEARING_TWO);
        }

        @Test
        void the_unbatched_registers_should_exclude_registers_recorded_while_the_flag_was_not_on() {
            final DistributionCommand on = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand off = seededCommand(HEARING_TWO, MONDAY_SHARED);
            final DistributionCommand unknown = seededCommand(HEARING_THREE, MONDAY_SHARED);

            softly.assertThatCode(() -> {
                store.record(on, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                store.record(off, document(HEARING_TWO, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.OFF);
                store.record(unknown, document(HEARING_THREE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.UNKNOWN);
            }).as(PENDING).doesNotThrowAnyException();

            softly.assertThat(rowsAtCourtCentre())
                    .as("all three are recorded; only one of them is this service's to generate")
                    .isEqualTo(3);
            softly.assertThat(activeUnbatched())
                    .as("OFF says the legacy was generating and UNKNOWN says nobody could tell - "
                            + "both are excluded, and both are surfaced by list-batches instead")
                    .extracting(RegisterRecord::hearingId)
                    .containsExactly(HEARING_ONE);
        }
    }

    /**
     * Defect fix P3, stated as the count progression gets wrong.
     *
     * <p>Progression's generation marks a court centre's rows generated, not a batch's. One court
     * centre sitting on two days therefore loses a whole day's register: the Monday document flips
     * Tuesday's rows too, Tuesday's batch is never assembled, and nobody is told. The store's port
     * takes a batch identity precisely so the widening cannot come back without changing the
     * signature, and this is the test that would fail if it did.
     *
     * <p>The Monday batch is requested before it is generated because that is the only way a batch
     * reaches GENERATED: {@code BatchStatus.canTransitionTo} refuses PENDING -&gt; GENERATED, since a
     * document cannot exist before a render was asked for. The request writes {@code register_batch}
     * and nothing else, so it moves no row and the counts below are the counts P3 is about.
     */
    @Nested
    @DisplayName("marking a batch generated")
    class Generation {

        @Test
        void generation_flips_only_the_batchs_own_rows() {
            final DistributionCommand mondayFirst = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand mondaySecond = seededCommand(HEARING_TWO, MONDAY_SHARED);
            final DistributionCommand tuesdayFirst = seededCommand(HEARING_THREE, TUESDAY_SHARED);
            final DistributionCommand tuesdaySecond = seededCommand(HEARING_FOUR, TUESDAY_SHARED);

            softly.assertThatCode(() -> {
                store.record(mondayFirst, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                store.record(mondaySecond, document(HEARING_TWO, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                store.record(tuesdayFirst, document(HEARING_THREE, TUESDAY, TUESDAY_SHARED),
                        APPLICANT, RecordedFlagState.ON);
                store.record(tuesdaySecond, document(HEARING_FOUR, TUESDAY, TUESDAY_SHARED),
                        APPLICANT, RecordedFlagState.ON);
                final List<RegisterRecord> waiting = mine(store.activeUnbatched());
                final RegisterBatch monday = store.assemble(
                        new CourtCentreDay(courtCentre, MONDAY), recordsOn(waiting, MONDAY));
                store.assemble(new CourtCentreDay(courtCentre, TUESDAY), recordsOn(waiting, TUESDAY));
                store.markRequested(monday.batchId(), PAYLOAD_FILE_ID);
                store.markGenerated(monday.batchId(), DOCUMENT_FILE_ID, GENERATED_AT);
            }).as(PENDING).doesNotThrowAnyException();

            softly.assertThat(statusesOn(MONDAY))
                    .as("the batch that generated a document is the batch whose rows move")
                    .containsExactly(GENERATED, GENERATED);
            softly.assertThat(statusesOn(TUESDAY))
                    .as("P3: progression flips by court centre, so Monday's document would carry "
                            + "Tuesday's rows to GENERATED and Tuesday's register would never be "
                            + "assembled, rendered or sent")
                    .containsExactly(RECORDED, RECORDED);
            softly.assertThat(generatedRowsAtCourtCentre())
                    .as("two rows generated, not four: the count is the whole of defect fix P3")
                    .isEqualTo(2);
        }
    }

    /**
     * A command with its {@code processed_request} parent already written, holding the run claim.
     *
     * <p>{@code processed_output} carries a foreign key to the request, which is the schema saying
     * what the design rules say: an output row is evidence about a request, and evidence with
     * nothing to be about is not evidence.
     */
    private DistributionCommand seededCommand(final UUID hearingId, final Instant sharedTime) {
        final DistributionCommand command = new DistributionCommand(
                ProcessedLogTestSupport.SOURCE,
                UUID.randomUUID(),
                hearingId,
                LocalDate.ofInstant(sharedTime, LONDON),
                sharedTime,
                "Hearing_Resulted");
        ProcessedLogTestSupport.repository(LEASE).insertNew(
                command,
                RequestFingerprint.of(command),
                new RunClaim(command.source(), command.requestId(), "runner-1", UUID.randomUUID(),
                        "msg-1"));
        return command;
    }

    /** One hearing's register for this case's court centre, as the pipeline would hand it over. */
    private CourtRegisterDocument document(
            final UUID hearingId, final LocalDate registerDate, final Instant registerTime) {
        return new CourtRegisterDocument(
                registerTime.toString(),
                HEARING_DATE.toString(),
                hearingId.toString(),
                courtCentre.toString(),
                fileName(hearingId, registerDate),
                new CourtRegisterHearingVenue("Lavender Hill LJA", "Lavender Hill Youth Court",
                        null),
                List.of(new CourtRegisterRecipient(
                        "Wandsworth Youth Offending Team", "yot@example.gov.uk", null,
                        "cr_standard")),
                List.of(new CourtRegisterDefendant(
                        "b2b3f5a1-6c9d-4e21-8a7f-3d5c1e9b0426", "SMITH, John", "2008-04-11",
                        null, null, null, "MALE", "Not Applicable", null, null,
                        List.of(), List.of(), List.of(), List.of())));
    }

    private static String fileName(final UUID hearingId, final LocalDate registerDate) {
        return "court-register_" + registerDate + '_' + OU_CODE + '_' + hearingId + ".pdf";
    }

    /**
     * The store's answer, narrowed to this case, with a refusal recorded rather than thrown.
     *
     * <p>The port answers for the whole table because the job does; the several suites sharing one
     * container do not, so the case that asked is the only one that may be asserted on.
     */
    private List<RegisterRecord> activeUnbatched() {
        final List<RegisterRecord> waiting = new ArrayList<>();
        softly.assertThatCode(() -> waiting.addAll(mine(store.activeUnbatched())))
                .as(PENDING)
                .doesNotThrowAnyException();
        return List.copyOf(waiting);
    }

    private List<RegisterRecord> mine(final List<RegisterRecord> records) {
        return records.stream()
                .filter(record -> courtCentre.equals(record.key().courtCentreId()))
                .toList();
    }

    private static List<RegisterRecord> recordsOn(
            final List<RegisterRecord> records, final LocalDate registerDate) {
        return records.stream()
                .filter(record -> registerDate.equals(record.key().registerDate()))
                .toList();
    }

    private long rowsAtCourtCentre() {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("SELECT count(*) FROM processed_output WHERE court_centre_id = :courtCentre")
                .param("courtCentre", courtCentre)
                .query(Long.class)
                .single();
    }

    private long generatedRowsAtCourtCentre() {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT count(*)
                          FROM processed_output
                         WHERE court_centre_id = :courtCentre AND status = 'GENERATED'
                        """)
                .param("courtCentre", courtCentre)
                .query(Long.class)
                .single();
    }

    private List<String> statusesOn(final LocalDate registerDate) {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT status
                          FROM processed_output
                         WHERE court_centre_id = :courtCentre AND register_date = :registerDate
                         ORDER BY output_id
                        """)
                .param("courtCentre", courtCentre)
                .param("registerDate", registerDate)
                .query(String.class)
                .list();
    }

    private static Optional<String> statusOf(final DistributionCommand command) {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT status
                          FROM processed_output
                         WHERE source = :source AND request_id = :requestId
                        """)
                .param("source", command.source())
                .param("requestId", command.requestId())
                .query(String.class)
                .optional();
    }

    private static Optional<UUID> outputIdOf(final DistributionCommand command) {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT output_id
                          FROM processed_output
                         WHERE source = :source AND request_id = :requestId
                        """)
                .param("source", command.source())
                .param("requestId", command.requestId())
                .query(UUID.class)
                .optional();
    }
}
