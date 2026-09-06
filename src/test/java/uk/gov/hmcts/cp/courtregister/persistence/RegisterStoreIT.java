package uk.gov.hmcts.cp.courtregister.persistence;

import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;

import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.ConcurrencyFailureException;
import uk.gov.hmcts.cp.courtregister.application.NotificationSummary;
import uk.gov.hmcts.cp.courtregister.application.RecordOutcome;
import uk.gov.hmcts.cp.courtregister.application.RegisterStore;
import uk.gov.hmcts.cp.courtregister.domain.BatchFailureReason;
import uk.gov.hmcts.cp.courtregister.domain.BatchStatus;
import uk.gov.hmcts.cp.courtregister.domain.CompletedBy;
import uk.gov.hmcts.cp.courtregister.domain.CourtCentreDay;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterDefendant;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterDocument;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterHearingVenue;
import uk.gov.hmcts.cp.courtregister.domain.CourtRegisterRecipient;
import uk.gov.hmcts.cp.courtregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.courtregister.domain.FailureClassification;
import uk.gov.hmcts.cp.courtregister.domain.RecordedFlagState;
import uk.gov.hmcts.cp.courtregister.domain.RegisterBatch;
import uk.gov.hmcts.cp.courtregister.domain.RegisterNotRecordedException;
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
 * <p>The two endings a batch can have are asserted beside them, because both write
 * {@code processed_output} as well as {@code register_batch} and neither is reachable from a mocked
 * store: a failure gives its rows back to the next run only for the two reasons that say nothing
 * ever left this service, and a notification tally moves the rows of the batch it settles and of no
 * other, under all three of the endings a tally can produce.
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
    private static final Instant MONDAY_RESHARED_AGAIN = Instant.parse("2026-08-24T16:45:00Z");
    private static final Instant TUESDAY_SHARED = Instant.parse("2026-08-25T09:00:00Z");

    private static final Instant HEARING_DATE = Instant.parse("2026-08-19T00:00:00Z");

    private static final UUID HEARING_ONE = UUID.fromString("1828f356-f746-4f2d-932b-79ef2df95c80");
    private static final UUID HEARING_TWO = UUID.fromString("6b0d5a1f-4c8e-4a92-8f31-2d7c6e05b114");
    private static final UUID HEARING_THREE =
            UUID.fromString("c41e9a37-05b2-4f6d-9e18-7a3b2c5d8064");
    private static final UUID HEARING_FOUR = UUID.fromString("9d2c7b48-3e15-4a70-b6c9-0f8e1d4a2537");

    private static final UUID PAYLOAD_FILE_ID =
            UUID.fromString("5e08b6d1-92a7-4c33-8f10-6b4d3e79a281");
    private static final UUID SECOND_PAYLOAD_FILE_ID =
            UUID.fromString("0c6a4f18-b573-4d29-8e04-95f2a7c31b6e");

    private static final UUID DOCUMENT_FILE_ID =
            UUID.fromString("3a7f1c92-6d84-4b05-9e73-1c2b8a4e07d5");
    private static final UUID SECOND_DOCUMENT_FILE_ID =
            UUID.fromString("7b53d0e4-1a86-4f97-b2c0-48e6d9f13a52");

    private static final Instant GENERATED_AT = Instant.parse("2026-08-24T18:04:11Z");

    /** systemdocgenerator's own words about a failure, which the row keeps and no log prints. */
    private static final String SDG_REASON = "template OEE_Layout5 rendered no pages";

    /** The same words, from a renderer that said far more of them than the column holds. */
    private static final String OVERSIZED_SDG_REASON = (SDG_REASON + "; ").repeat(30);

    private static final String OU_CODE = "B01LY00";

    /** Two re-shares of one hearing, which is the smallest number that can lose the invariant. */
    private static final int RACERS = 2;

    /** How long the suite waits for both recorders to reach the register they are superseding. */
    private static final Duration PARKED_DEADLINE = Duration.ofSeconds(10);

    private static final Duration PARKED_POLL = Duration.ofMillis(20);

    private static final String APPLICANT = "Applicant";
    private static final String RESPONDENT = "Respondent";

    private static final String RECORDED = "RECORDED";
    private static final String SUPERSEDED = "SUPERSEDED";
    private static final String GENERATING = "GENERATING";
    private static final String GENERATED = "GENERATED";
    private static final String NOTIFIED = "NOTIFIED";
    private static final String FAILED = "FAILED";

    /** The refusal every port call makes until T015 replaces it with a statement. */
    private static final String PENDING = "T015 implements the register store; this is its red run";

    /**
     * What an unexpected refusal means in the suites written after T015 landed.
     *
     * <p>Their arrangements walk the ordinary path a batch walks - assemble, request, generate - so
     * a refusal out of one of them is the statement under test saying no, not a fixture that needs
     * fixing, and the description says which of the two the reader is looking at.
     */
    private static final String WALKED =
            "the arrangement is the path every batch walks; a refusal here is the store's answer";

    @InjectSoftAssertions
    private SoftAssertions softly;

    /** This case's court centre: minted per test so no case can read another's rows. */
    private final UUID courtCentre = UUID.randomUUID();

    private final RegisterStore store =
            new JdbcRegisterStore(ProcessedLogTestSupport.jdbcClient(),
                    ProcessedLogTestSupport.transactions());

    @BeforeAll
    static void migrate() {
        PostgresTestSupport.applyFlyway();
    }

    /**
     * A recorded register is the row the whole downstream half is written against, so the two things
     * that decide what a batch can do with it - what it says, and that there is exactly one of it -
     * are asserted before anything else.
     *
     * <p>And the third thing, which is what a <em>second</em> delivery of one command makes of it.
     * The recording and the completion of the command are two statements, so a pod that stops
     * between them leaves a register recorded against a request the broker will deliver again; 001's
     * POST path met the same shape and answered it with {@code ON CONFLICT (source, request_id)}.
     * Recording has to be idempotent on the command's own key for the same reason: a redelivery that
     * met {@code processed_output_unique_request} instead would fail a command whose register is
     * recorded and active, and would go on failing it until the broker parked it.
     */
    @Nested
    @DisplayName("recording a register")
    class Recording {

        @Test
        void recording_a_register_should_write_one_recorded_row_for_the_command() {
            final DistributionCommand command = seededCommand(HEARING_ONE, MONDAY_SHARED);

            softly.assertThatCode(() -> record(
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

            softly.assertThatCode(() -> record(
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

        /**
         * The one fact the batch reads off a row that the document does not carry.
         *
         * <p>{@code assemble} copies the OU code from the batch's first row into
         * {@code register_batch}, where the render payload and the file name are built from it. The
         * recorder is the only writer that ever knows it - the transformation resolves it from
         * reference data and 001 carries it on {@code ProcessedOutputClaim} for exactly this reason
         * - so a recording that dropped it would leave every batch of the new shape addressed under
         * a court centre nothing downstream can name.
         */
        @Test
        void a_recorded_register_should_carry_the_court_centre_ou_code_the_batch_needs() {
            final DistributionCommand command = seededCommand(HEARING_ONE, MONDAY_SHARED);

            softly.assertThatCode(() -> {
                store.record(command, document(HEARING_ONE, MONDAY, MONDAY_SHARED), OU_CODE,
                        APPLICANT, RecordedFlagState.ON);
                store.assemble(new CourtCentreDay(courtCentre, MONDAY),
                        mine(store.activeUnbatched()));
            }).as(WALKED).doesNotThrowAnyException();

            softly.assertThat(ouCodeOf(command))
                    .as("the OU code the transformation resolved, on the row that recorded it")
                    .contains(OU_CODE);
            softly.assertThat(batchOuCodeOn(MONDAY))
                    .as("and therefore on the batch, which takes it from its first row and has "
                            + "nowhere else to get it from")
                    .contains(OU_CODE);
        }

        /**
         * The redelivery of a command whose register is already recorded.
         *
         * <p>The recording and the completion of the command are separate statements, so a pod that
         * dies between them - or a completion that fails transiently - leaves the register written
         * and the request unfinished, and the broker delivers the message again. What the second
         * delivery must find is the register the first one wrote: one row, still active, answered
         * under the identity it already has.
         */
        @Test
        void a_redelivered_command_is_answered_with_the_register_it_already_recorded() {
            final DistributionCommand command = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final CourtRegisterDocument document = document(HEARING_ONE, MONDAY, MONDAY_SHARED);
            final AtomicReference<RecordOutcome> first = new AtomicReference<>();
            final AtomicReference<RecordOutcome> redelivered = new AtomicReference<>();

            softly.assertThatCode(() -> {
                first.set(record(command, document, APPLICANT, RecordedFlagState.ON));
                redelivered.set(record(command, document, APPLICANT, RecordedFlagState.ON));
            }).as(PENDING).doesNotThrowAnyException();

            softly.assertThat(rowsAtCourtCentre())
                    .as("one command, one register, however many times the broker delivers it")
                    .isEqualTo(1);
            softly.assertThat(statusOf(command))
                    .as("and it is still the active register: a redelivery neither supersedes it "
                            + "nor moves it out of the nightly sweep")
                    .contains(RECORDED);
            softly.assertThat(redelivered.get())
                    .as("the second delivery is answered with the row the first one wrote, so the "
                            + "run it belongs to can be completed rather than failed and retried "
                            + "until the queue parks a register that is safely recorded")
                    .isEqualTo(first.get());
        }

        /**
         * The same redelivery, of the re-share that replaced an earlier register.
         *
         * <p>The supersession is the part that must not happen twice. A second run of the statement
         * would find the register this command already recorded, supersede <em>it</em>, and record a
         * third row - so the hearing would end the day with a register nobody asked for and an
         * answer naming a row that was never dropped.
         */
        @Test
        void a_redelivered_re_share_supersedes_nothing_a_second_time() {
            final DistributionCommand first = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand reshare = seededCommand(HEARING_ONE, MONDAY_RESHARED);
            final CourtRegisterDocument resharedDocument =
                    document(HEARING_ONE, MONDAY, MONDAY_RESHARED);
            final AtomicReference<RecordOutcome> redelivered = new AtomicReference<>();

            softly.assertThatCode(() -> {
                record(first, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                record(reshare, resharedDocument, APPLICANT, RecordedFlagState.ON);
                redelivered.set(
                        record(reshare, resharedDocument, APPLICANT, RecordedFlagState.ON));
            }).as(PENDING).doesNotThrowAnyException();

            softly.assertThat(rowsAtCourtCentre())
                    .as("two registers and no more: the redelivery supersedes nothing and records "
                            + "nothing")
                    .isEqualTo(2);
            softly.assertThat(statusOf(first))
                    .as("the register the re-share replaced is superseded once, by the recording "
                            + "that replaced it")
                    .contains(SUPERSEDED);
            softly.assertThat(statusOf(reshare)).contains(RECORDED);
            softly.assertThat(redelivered.get())
                    .as("and the answer is the recording that already happened, the row it "
                            + "superseded included")
                    .isEqualTo(new RecordOutcome(outputIdOf(reshare).orElse(null),
                            outputIdOf(first).orElse(null)));
        }

        /**
         * The same redelivery, once a second register has also come to name the one it answers with.
         *
         * <p>Two rows can point at one register, and the store writes them both itself. A re-share
         * supersedes the register it replaces and names itself on it; and an <em>earlier</em> share
         * that arrives afterwards - a redelivery that overtook the register it belongs behind, or
         * the loser of a race between two deliveries - is recorded SUPERSEDED against that same
         * later register. Both of them then carry it in {@code superseded_by}.
         *
         * <p>So a read of "the row naming this one" is a read of two rows, and the redelivery it is
         * for - a pod that stopped between the recording and the completion - is exactly when it is
         * asked. What the redelivery must be answered with is the register this recording actually
         * replaced, which is the answer its first delivery was given; a refusal here would fail a
         * command whose register is recorded and active, on every delivery, until the broker parked
         * it.
         */
        @Test
        void a_redelivered_re_share_is_answered_though_a_later_arrival_names_it_too() {
            final DistributionCommand shared = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand reshare = seededCommand(HEARING_ONE, MONDAY_RESHARED_AGAIN);
            final DistributionCommand overtaken = seededCommand(HEARING_ONE, MONDAY_RESHARED);
            final CourtRegisterDocument resharedDocument =
                    document(HEARING_ONE, MONDAY, MONDAY_RESHARED_AGAIN);
            final AtomicReference<RecordOutcome> redelivered = new AtomicReference<>();

            softly.assertThatCode(() -> {
                record(shared, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                record(reshare, resharedDocument, APPLICANT, RecordedFlagState.ON);
                record(overtaken, document(HEARING_ONE, MONDAY, MONDAY_RESHARED), APPLICANT,
                        RecordedFlagState.ON);
                redelivered.set(record(reshare, resharedDocument, APPLICANT,
                        RecordedFlagState.ON));
            }).as(WALKED).doesNotThrowAnyException();

            softly.assertThat(statusOf(overtaken))
                    .as("the earlier results that arrived late do not displace the register that "
                            + "replaced them")
                    .contains(SUPERSEDED);
            softly.assertThat(supersessionOf(overtaken))
                    .as("and they name that register, which is the second row to do so")
                    .hasValueSatisfying(pair -> softly.assertThat(pair.supersededBy())
                            .isEqualTo(outputIdOf(reshare).orElse(null)));
            softly.assertThat(redelivered.get())
                    .as("the redelivery is still answered with the register the re-share itself "
                            + "replaced, and not refused because a later arrival names the same row")
                    .isEqualTo(new RecordOutcome(outputIdOf(reshare).orElse(null),
                            outputIdOf(shared).orElse(null)));
            softly.assertThat(rowsAtCourtCentre())
                    .as("three commands, three registers: the redelivery records nothing")
                    .isEqualTo(3);
        }

        /**
         * A refusal the recorder does not account for, which is not the race and is not retried.
         *
         * <p>The recorder knows two unique keys and settles both itself:
         * {@code processed_output_unique_request} is this command arriving beside itself and is
         * answered from the row that landed, and {@code idx_output_active_register_key} is the race
         * for the day's active register and is read again and recorded against the winner. Reading
         * every <em>other</em> duplicate-key refusal as the race spends three attempts on a
         * constraint no attempt can satisfy and then reports contention - a transient failure the
         * broker redelivers - for a register that will be refused in exactly the same way on every
         * delivery until the queue parks it under an exhaustion nobody can explain.
         *
         * <p>The constraint is made rather than imagined: a unique index over {@code court_house},
         * partial on this case's own court centre so no other suite can meet it, refuses the second
         * of two registers for two different hearings at the same court house. Neither key the
         * recorder knows is touched - the hearings differ and so do the commands - so what the
         * store meets is precisely the case this asserts.
         */
        @Test
        void a_unique_violation_that_is_not_the_active_row_race_is_propagated_not_retried() {
            final DistributionCommand first = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand second = seededCommand(HEARING_TWO, MONDAY_SHARED);

            softly.assertThatCode(() -> record(first, document(HEARING_ONE, MONDAY, MONDAY_SHARED),
                            APPLICANT, RecordedFlagState.ON))
                    .as(WALKED)
                    .doesNotThrowAnyException();
            withACourtHouseUniqueIndex(() -> softly
                    .assertThatThrownBy(() -> record(second,
                            document(HEARING_TWO, MONDAY, MONDAY_SHARED), APPLICANT,
                            RecordedFlagState.ON))
                    .as("a constraint the recorder does not account for is the store answering "
                            + "that this row may not be written, which no redelivery changes - not "
                            + "a race to try again and report as contention")
                    .isInstanceOf(RegisterNotRecordedException.class)
                    .isNotInstanceOf(ConcurrencyFailureException.class)
                    .asInstanceOf(InstanceOfAssertFactories.type(RegisterNotRecordedException.class))
                    .extracting(RegisterNotRecordedException::classification)
                    .isEqualTo(FailureClassification.NON_TRANSIENT));

            softly.assertThat(rowsAtCourtCentre())
                    .as("and the refused recording left nothing behind it")
                    .isEqualTo(1);
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
                record(first, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                reshare.set(record(second,
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
            softly.assertThat(supersessionOf(first))
                    .as("and the pair is on the row, not only in the answer: superseded_at is what "
                            + "takes the register out of the nightly sweep, and superseded_by is "
                            + "the only thing that leads from a dropped register to its replacement")
                    .hasValueSatisfying(pair -> {
                        softly.assertThat(pair.supersededAt()).isNotNull();
                        softly.assertThat(pair.supersededBy())
                                .isEqualTo(outputIdOf(second).orElse(null));
                    });
            softly.assertThat(supersessionOf(second))
                    .as("the register that won carries neither, or the sweep that reads "
                            + "superseded_at IS NULL would leave it out as well and the day would "
                            + "be rendered without the hearing entirely")
                    .hasValueSatisfying(pair -> {
                        softly.assertThat(pair.supersededAt()).isNull();
                        softly.assertThat(pair.supersededBy()).isNull();
                    });
        }

        @Test
        void a_re_share_should_leave_exactly_one_active_row_for_the_hearing_and_day() {
            final DistributionCommand first = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand second = seededCommand(HEARING_ONE, MONDAY_RESHARED);

            softly.assertThatCode(() -> {
                record(first, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                record(second, document(HEARING_ONE, MONDAY, MONDAY_RESHARED), APPLICANT,
                        RecordedFlagState.ON);
            }).as(PENDING).doesNotThrowAnyException();

            softly.assertThat(activeUnbatched())
                    .as("two active rows for one hearing and one day would put the same hearing on "
                            + "one PDF twice")
                    .extracting(RegisterRecord::registerTime)
                    .containsExactly(MONDAY_RESHARED);
        }

        /**
         * The same invariant, put to two recorders at once, which is the only way it can be lost.
         *
         * <p>Which register a re-share replaces is decided by a read: the recording statement looks
         * for the hearing's active row for the day and writes what it found. Two re-shares that
         * both read before either of them has committed therefore both find the same incumbent,
         * both supersede it and both insert an active register - and the day is rendered with one
         * hearing on it twice, under two different sets of results, with nothing in the store
         * saying which of them the Youth Offending Team should believe. One recording at a time can
         * never show this, because the second read always sees the first row: the case above
         * asserts the same property and would stay green throughout.
         *
         * <p>The race is arranged rather than hoped for. The suite takes the incumbent row itself,
         * on its own connection, and only then releases both recorders: each statement reads the
         * incumbent on its own snapshot and then parks on the supersession it is about to make, so
         * neither can commit until the suite lets go and both have therefore read the register they
         * are replacing while it was still the active one. The suite waits for the database to say
         * both are parked rather than for a duration, so what it releases them into is a fact it
         * checked and not a delay it hoped was long enough.
         *
         * <p>Nothing here is the caller's problem. A re-share that loses this race is a message the
         * broker delivered and the pipeline completed, so the store settles it and reports nothing:
         * a duplicate-key failure escaping to the listener would abandon a command whose register
         * is safely recorded, and turn an invariant the database keeps into an outage.
         */
        @Test
        void two_concurrent_re_shares_leave_exactly_one_active_row() {
            final DistributionCommand shared = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand reshared = seededCommand(HEARING_ONE, MONDAY_RESHARED);
            final DistributionCommand resharedAgain =
                    seededCommand(HEARING_ONE, MONDAY_RESHARED_AGAIN);

            softly.assertThatCode(() -> record(shared,
                            document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                            RecordedFlagState.ON))
                    .as(WALKED)
                    .doesNotThrowAnyException();
            final List<Throwable> escaped = raceToRecord(shared, reshared, resharedAgain);

            softly.assertThat(escaped)
                    .as("a re-share that lost the race is still a register this service recorded, "
                            + "and the command that carried it completed: the store settles the "
                            + "collision itself rather than handing the listener a failure")
                    .isEmpty();
            softly.assertThat(activeUnbatched())
                    .as("one hearing, one day, one active register - whichever recorder got there "
                            + "first, the later results are the ones the day is rendered from")
                    .extracting(RegisterRecord::registerTime)
                    .containsExactly(MONDAY_RESHARED_AGAIN);
            softly.assertThat(statusOf(resharedAgain)).contains(RECORDED);
            softly.assertThat(statusOf(reshared))
                    .as("and the earlier of the two is superseded rather than lost or refused")
                    .contains(SUPERSEDED);
            softly.assertThat(supersessionOf(reshared))
                    .as("naming the register that replaced it, which is the only thing that leads "
                            + "from a dropped register to the one the batch will carry")
                    .hasValueSatisfying(pair -> {
                        softly.assertThat(pair.supersededAt()).isNotNull();
                        softly.assertThat(pair.supersededBy())
                                .isEqualTo(outputIdOf(resharedAgain).orElse(null));
                    });
            softly.assertThat(statusOf(shared))
                    .as("the register both of them replaced is superseded exactly once, whichever "
                            + "of them got to it")
                    .contains(SUPERSEDED);
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
            final AtomicReference<Map<String, String>> beforeTheReshare = new AtomicReference<>();

            softly.assertThatCode(() -> {
                record(first, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                store.assemble(new CourtCentreDay(courtCentre, MONDAY),
                        mine(store.activeUnbatched()));
                beforeTheReshare.set(wholeRowOf(first));
                reshare.set(record(second,
                        document(HEARING_ONE, MONDAY, MONDAY_RESHARED), APPLICANT,
                        RecordedFlagState.ON));
            }).as(PENDING).doesNotThrowAnyException();

            softly.assertThat(wholeRowOf(first))
                    .as("every column, read before the re-share and after it: the payload handed "
                            + "to systemdocgenerator was built from this row, so anything at all "
                            + "moving on it - a status, a stamp, updated_at - would mean the "
                            + "document already asked for no longer matches the register it holds")
                    .isEqualTo(beforeTheReshare.get());
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
                record(monday, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                record(tuesday, document(HEARING_ONE, TUESDAY, TUESDAY_SHARED), APPLICANT,
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
                record(batched, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                store.assemble(new CourtCentreDay(courtCentre, MONDAY),
                        mine(store.activeUnbatched()));
                record(waiting, document(HEARING_TWO, MONDAY, MONDAY_SHARED), APPLICANT,
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
                record(on, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                record(off, document(HEARING_TWO, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.OFF);
                record(unknown, document(HEARING_THREE, MONDAY, MONDAY_SHARED), APPLICANT,
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
     * Assembly, which is all of it or none of it.
     *
     * <p>The batch row, the stamps and the count that judges them are one decision. Between the
     * read that produced the list and the write that stamps it, a re-share can supersede a row or
     * another run can stamp it, and a batch that quietly contained fewer registers than it was asked
     * for would render a document missing a hearing nobody could name. The store refuses such a
     * batch - and the refusal is only worth anything if the batch row goes with it, because
     * {@code idx_register_batch_live_key} admits one unfailed batch per court centre and day: a
     * PENDING row left behind by a refusal holds that key against every later run, and the day is
     * never rendered at all.
     */
    @Nested
    @DisplayName("assembling a batch")
    class Assembly {

        @Test
        void a_batch_asked_for_a_register_that_moved_should_leave_no_trace_of_itself() {
            final DistributionCommand first = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand second = seededCommand(HEARING_TWO, MONDAY_SHARED);
            final List<RegisterRecord> stale = new ArrayList<>();

            softly.assertThatCode(() -> {
                record(first, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                record(second, document(HEARING_TWO, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                stale.addAll(mine(store.activeUnbatched()));
                // The re-share the run did not see: it lands after the list was read, supersedes
                // the first hearing's row, and leaves the list the run is holding one register out
                // of date.
                record(seededCommand(HEARING_ONE, MONDAY_RESHARED),
                        document(HEARING_ONE, MONDAY, MONDAY_RESHARED), APPLICANT,
                        RecordedFlagState.ON);
            }).as(WALKED).doesNotThrowAnyException();

            softly.assertThatThrownBy(() ->
                            store.assemble(new CourtCentreDay(courtCentre, MONDAY), stale))
                    .as("a batch that would render one of the two registers it was asked for is "
                            + "refused rather than sent")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("was asked for 2 registers and stamped 1");
            softly.assertThat(batchOn(MONDAY))
                    .as("and the batch row goes with the refusal, or it holds the day's live key "
                            + "against every later run and the day is never rendered")
                    .isEmpty();
            softly.assertThat(stampedRowsOn(MONDAY))
                    .as("the register that was stamped before the count came back is unstamped "
                            + "again, and waits for the batch that does contain it")
                    .isZero();

            softly.assertThatCode(() -> store.assemble(new CourtCentreDay(courtCentre, MONDAY),
                            mine(store.activeUnbatched())))
                    .as("the next run assembles the day as it now stands, which is the whole point "
                            + "of refusing the first one")
                    .doesNotThrowAnyException();
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
                record(mondayFirst, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                record(mondaySecond, document(HEARING_TWO, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                record(tuesdayFirst, document(HEARING_THREE, TUESDAY, TUESDAY_SHARED),
                        APPLICANT, RecordedFlagState.ON);
                record(tuesdaySecond, document(HEARING_FOUR, TUESDAY, TUESDAY_SHARED),
                        APPLICANT, RecordedFlagState.ON);
                final List<RegisterRecord> waiting = mine(store.activeUnbatched());
                final RegisterBatch monday = store.assemble(
                        new CourtCentreDay(courtCentre, MONDAY), recordsOn(waiting, MONDAY));
                store.assemble(new CourtCentreDay(courtCentre, TUESDAY), recordsOn(waiting, TUESDAY));
                store.markRequested(monday.batchId(), PAYLOAD_FILE_ID);
                store.markGenerated(monday.batchId(), DOCUMENT_FILE_ID, GENERATED_AT,
                        CompletedBy.EVENT);
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

        /**
         * Which mechanism learned the outcome, written by the mark that learned it.
         *
         * <p>{@code completed_by} is what the {@code reconciled} metric counts, and a run whose
         * outcomes all arrive by RECONCILER is a broker or a subscription somebody has to look at.
         * Nothing else in the flow records it, so a batch that does not carry it is a batch whose
         * completion mechanism is lost.
         *
         * <p>It cannot be a second write. Batch state changes are compare-and-set through
         * {@code BatchStatus}: written before the mark, the batch is still GENERATING and the update
         * would be guessing at an outcome that has not arrived; written after it, the only move left
         * is GENERATED to GENERATED, which the machine refuses. The mark therefore carries it, and
         * this case asserts that the mark's own statement is where it lands - both endings that
         * somebody outside this service reported, so the EVENT path and the RECONCILER path are
         * pinned by the same test rather than by one and an assumption.
         */
        @Test
        void generation_records_who_completed_the_batch() {
            final DistributionCommand listenedFor = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand reconciledFor = seededCommand(HEARING_THREE, TUESDAY_SHARED);

            softly.assertThatCode(() -> {
                record(listenedFor, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                record(reconciledFor, document(HEARING_THREE, TUESDAY, TUESDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final List<RegisterRecord> waiting = mine(store.activeUnbatched());
                final RegisterBatch listened = store.assemble(
                        new CourtCentreDay(courtCentre, MONDAY), recordsOn(waiting, MONDAY));
                final RegisterBatch reconciled = store.assemble(
                        new CourtCentreDay(courtCentre, TUESDAY), recordsOn(waiting, TUESDAY));
                store.markRequested(listened.batchId(), PAYLOAD_FILE_ID);
                store.markGenerated(listened.batchId(), DOCUMENT_FILE_ID, GENERATED_AT,
                        CompletedBy.EVENT);
                store.markRequested(reconciled.batchId(), SECOND_PAYLOAD_FILE_ID);
                store.markFailed(reconciled.batchId(), BatchFailureReason.GENERATION_FAILED,
                        SDG_REASON, CompletedBy.RECONCILER);
            }).as(WALKED).doesNotThrowAnyException();

            softly.assertThat(completedByOn(MONDAY))
                    .as("the document arrived on the public event, and the row that says the batch "
                            + "is generated says so in the same breath")
                    .contains("EVENT");
            softly.assertThat(completedByOn(TUESDAY))
                    .as("and the failure the grace-period reconciler went and asked for is the "
                            + "other mechanism, which is the one the metric exists to count")
                    .contains("RECONCILER");
        }

        /**
         * A generated batch that names nobody, refused before anything is written.
         *
         * <p>A document exists because some mechanism outside this service said so, and the row that
         * records the document is the only place that says which one. A GENERATED row with no
         * {@code completed_by} is therefore not an incomplete row but a contradictory one: it claims
         * an answer arrived and denies that anything delivered it, and the {@code reconciled} metric
         * counts it as neither.
         *
         * <p>The refusal has to come before the statement. The mark is a compare-and-set that also
         * moves this batch's registers to GENERATED, and there is no second write afterwards that
         * could add the attribution: GENERATED to GENERATED is a move
         * {@link uk.gov.hmcts.cp.courtregister.domain.BatchStatus} refuses. A row written without it
         * is a row that can never acquire it.
         */
        @Test
        void a_generated_mark_without_attribution_is_refused() {
            final DistributionCommand first = seededCommand(HEARING_ONE, MONDAY_SHARED);

            softly.assertThatCode(() -> {
                record(first, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final RegisterBatch monday = store.assemble(
                        new CourtCentreDay(courtCentre, MONDAY), mine(store.activeUnbatched()));
                store.markRequested(monday.batchId(), PAYLOAD_FILE_ID);
            }).as(WALKED).doesNotThrowAnyException();
            final UUID batchId = batchIdOn(MONDAY);

            softly.assertThatThrownBy(() ->
                            store.markGenerated(batchId, DOCUMENT_FILE_ID, GENERATED_AT, null))
                    .as("a document arrived because some mechanism reported it, and this row is "
                            + "the only place that ever says which one")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(GENERATED);
            softly.assertThat(batchOn(MONDAY))
                    .as("and the refusal is made before the statement, so the batch is still "
                            + "waiting for the outcome it was asked about")
                    .contains(new BatchOutcome(GENERATING, null, null));
            softly.assertThat(completedByOn(MONDAY))
                    .as("nothing was attributed, because nothing was written")
                    .isEmpty();
            softly.assertThat(statusesOn(MONDAY))
                    .as("and the register the batch was assembled from did not move either")
                    .containsExactly(RECORDED);
        }
    }

    /**
     * The ending where no document was ever produced, and what it does to the rows.
     *
     * <p>Six bounded reasons, and the store treats two of them differently from the other four. The
     * two that say the batch never left this service - the payload was not stored, the assembly
     * itself failed - release the stamp, so the registers become unbatched again and tonight's
     * failure is tomorrow's first batch. The other four leave the stamp exactly where it is:
     * systemdocgenerator was asked, a document may yet exist under that correlation, and re-rendering
     * one is a decision a person makes through the operations CLI rather than one a schedule makes
     * silently at 18:00.
     *
     * <p>The rows stay RECORDED under every reason. Nothing was ever sent about them, and a status
     * that said otherwise would take a register out of the next run without anybody having received
     * it.
     */
    @Nested
    @DisplayName("failing a batch")
    class Failure {

        @Test
        void a_failure_that_never_left_this_service_should_release_its_rows_for_the_next_run() {
            final DistributionCommand first = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand second = seededCommand(HEARING_TWO, MONDAY_SHARED);

            softly.assertThatCode(() -> {
                record(first, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                record(second, document(HEARING_TWO, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final RegisterBatch monday = store.assemble(
                        new CourtCentreDay(courtCentre, MONDAY), mine(store.activeUnbatched()));
                store.markFailed(monday.batchId(),
                        BatchFailureReason.PAYLOAD_STORE_UNAVAILABLE, null, null);
            }).as(WALKED).doesNotThrowAnyException();

            softly.assertThat(batchOn(MONDAY))
                    .as("the batch is finished under the bounded code the run report counts it by, "
                            + "and systemdocgenerator said nothing because it was never asked")
                    .contains(new BatchOutcome(FAILED, "PAYLOAD_STORE_UNAVAILABLE", null));
            softly.assertThat(statusesOn(MONDAY))
                    .as("nothing was sent about these registers, so nothing about them moved on")
                    .containsExactly(RECORDED, RECORDED);
            softly.assertThat(stampedRowsOn(MONDAY))
                    .as("the stamp is released: a batch that never left this service holds no "
                            + "register hostage to a document that will never exist")
                    .isZero();
            softly.assertThat(activeUnbatched())
                    .as("and the next run picks the same registers up, under a fresh batch identity")
                    .extracting(RegisterRecord::hearingId)
                    .containsExactlyInAnyOrder(HEARING_ONE, HEARING_TWO);
        }

        @Test
        void a_failure_after_the_render_request_should_keep_the_stamp_on_its_rows() {
            final DistributionCommand first = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand second = seededCommand(HEARING_TWO, MONDAY_SHARED);

            softly.assertThatCode(() -> {
                record(first, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                record(second, document(HEARING_TWO, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final RegisterBatch monday = store.assemble(
                        new CourtCentreDay(courtCentre, MONDAY), mine(store.activeUnbatched()));
                store.markRequested(monday.batchId(), PAYLOAD_FILE_ID);
                store.markFailed(monday.batchId(), BatchFailureReason.GENERATION_FAILED, SDG_REASON,
                        CompletedBy.EVENT);
            }).as(WALKED).doesNotThrowAnyException();

            softly.assertThat(batchOn(MONDAY))
                    .as("what systemdocgenerator said is kept beside this service's own code, for "
                            + "the person who has to decide whether to render the day again")
                    .contains(new BatchOutcome(FAILED, "GENERATION_FAILED", SDG_REASON));
            softly.assertThat(statusesOn(MONDAY)).containsExactly(RECORDED, RECORDED);
            softly.assertThat(stampedRowsOn(MONDAY))
                    .as("the renderer was asked, so a document may yet exist under this batch: "
                            + "re-assembling the same registers automatically could send the day "
                            + "twice, and the stamp is what stops the next run doing it")
                    .isEqualTo(2);
            softly.assertThat(activeUnbatched())
                    .as("nothing is waiting: these registers belong to a batch a person must look at")
                    .isEmpty();
        }

        /**
         * The one column here holding words this service did not author.
         *
         * <p>{@code sdg_reason} is systemdocgenerator's own message, and how long it is is
         * systemdocgenerator's decision rather than this service's. The column is bounded, so the
         * bound has to be applied before the write: an unbounded write against a bounded column
         * fails the whole failure statement, and the batch that could not say why it failed then
         * stays GENERATING until the reconciler gives up on it - the failure lost twice over.
         */
        @Test
        void a_reason_longer_than_the_column_should_be_bounded_before_it_is_written() {
            final DistributionCommand first = seededCommand(HEARING_ONE, MONDAY_SHARED);

            softly.assertThatCode(() -> {
                record(first, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final RegisterBatch monday = store.assemble(
                        new CourtCentreDay(courtCentre, MONDAY), mine(store.activeUnbatched()));
                store.markRequested(monday.batchId(), PAYLOAD_FILE_ID);
                store.markFailed(monday.batchId(), BatchFailureReason.GENERATION_FAILED,
                        OVERSIZED_SDG_REASON, CompletedBy.EVENT);
            }).as("the failure is recorded whatever the renderer chose to say")
                    .doesNotThrowAnyException();

            softly.assertThat(batchOn(MONDAY).map(BatchOutcome::sdgReason))
                    .as("bounded to the column, and saying so: a reader who cannot see the rest "
                            + "must be able to tell that there is a rest")
                    .hasValueSatisfying(reason -> {
                        softly.assertThat(reason).hasSize(512);
                        softly.assertThat(reason).startsWith(SDG_REASON);
                        softly.assertThat(reason).endsWith(" [truncated]");
                    });
            softly.assertThat(batchOn(MONDAY).map(BatchOutcome::status))
                    .as("and the batch still ends where the reason says it ended")
                    .contains(FAILED);
        }

        /**
         * The two endings somebody outside this service reported, and what they must carry.
         *
         * <p>GENERATION_FAILED is systemdocgenerator's own verdict about the render and
         * GENERATION_TIMED_OUT is the reconciler's verdict about systemdocgenerator's silence. Both
         * are learned by a named mechanism, and the {@code reconciled} metric is the count of which
         * one: a batch that ends under either of them without naming it is the one row the metric
         * cannot be computed from, and no later write can supply it.
         */
        @Test
        void a_generator_failure_without_attribution_is_refused() {
            final DistributionCommand first = seededCommand(HEARING_ONE, MONDAY_SHARED);

            softly.assertThatCode(() -> {
                record(first, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final RegisterBatch monday = store.assemble(
                        new CourtCentreDay(courtCentre, MONDAY), mine(store.activeUnbatched()));
                store.markRequested(monday.batchId(), PAYLOAD_FILE_ID);
            }).as(WALKED).doesNotThrowAnyException();
            final UUID batchId = batchIdOn(MONDAY);

            softly.assertThatThrownBy(() -> store.markFailed(batchId,
                            BatchFailureReason.GENERATION_FAILED, SDG_REASON, null))
                    .as("this reason is somebody else's answer about the render, so a caller that "
                            + "cannot say whose answer it is has lost half of it")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("GENERATION_FAILED");
            softly.assertThat(batchOn(MONDAY))
                    .as("and nothing is written: no ending, and no reason to explain one")
                    .contains(new BatchOutcome(GENERATING, null, null));
            softly.assertThat(stampedRowsOn(MONDAY))
                    .as("the batch is still the batch its register belongs to")
                    .isEqualTo(1);
        }

        /**
         * The four endings this service reached on its own, and what they must not carry.
         *
         * <p>The payload was never stored, the request was never delivered, it was refused, or the
         * batch could not be assembled at all. Nobody outside this service was ever in a position to
         * answer, so naming EVENT or RECONCILER on one of these credits a decision that mechanism
         * never made - and the {@code reconciled} metric, which exists to say how many outcomes had
         * to be gone and asked for, counts an outcome nobody delivered.
         */
        @Test
        void a_service_failure_with_attribution_is_refused() {
            final DistributionCommand first = seededCommand(HEARING_ONE, MONDAY_SHARED);

            softly.assertThatCode(() -> {
                record(first, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                store.assemble(new CourtCentreDay(courtCentre, MONDAY),
                        mine(store.activeUnbatched()));
            }).as(WALKED).doesNotThrowAnyException();
            final UUID batchId = batchIdOn(MONDAY);

            softly.assertThatThrownBy(() -> store.markFailed(batchId,
                            BatchFailureReason.PAYLOAD_STORE_UNAVAILABLE, null, CompletedBy.EVENT))
                    .as("the file service was never written to, so no event and no query could "
                            + "have reported anything about a render nobody was asked for")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("PAYLOAD_STORE_UNAVAILABLE");
            softly.assertThat(batchOn(MONDAY).map(BatchOutcome::status))
                    .as("and the batch is exactly where assembly left it")
                    .contains("PENDING");
            softly.assertThat(completedByOn(MONDAY))
                    .as("nothing was attributed, because nothing was written")
                    .isEmpty();
            softly.assertThat(stampedRowsOn(MONDAY))
                    .as("and the stamp this ending would have released is still on the register")
                    .isEqualTo(1);
        }

        /**
         * The second generator-attributed ending, in the direction that refuses.
         *
         * <p>GENERATION_TIMED_OUT is the only one of the six the reconciler itself decides: the
         * grace period passed and systemdocgenerator still had no verdict, so the reconciler's
         * having gone and asked is the whole of what the row records about the ending. A timeout
         * that names nobody is a row saying an outcome was chased and refusing to say by what, and
         * the {@code reconciled} metric - which exists to count exactly these - cannot be computed
         * from it. The store answers for both attributed reasons alike, and the case that pins the
         * other one would not have noticed had this one been left out of the rule.
         */
        @Test
        void a_timed_out_generation_without_attribution_is_refused() {
            final DistributionCommand first = seededCommand(HEARING_ONE, MONDAY_SHARED);

            softly.assertThatCode(() -> {
                record(first, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final RegisterBatch monday = store.assemble(
                        new CourtCentreDay(courtCentre, MONDAY), mine(store.activeUnbatched()));
                store.markRequested(monday.batchId(), PAYLOAD_FILE_ID);
            }).as(WALKED).doesNotThrowAnyException();
            final UUID batchId = batchIdOn(MONDAY);

            softly.assertThatThrownBy(() -> store.markFailed(batchId,
                            BatchFailureReason.GENERATION_TIMED_OUT, null, null))
                    .as("the grace period passing is a verdict somebody reached by going and "
                            + "looking, and the row is the only place that says who looked")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("GENERATION_TIMED_OUT");
            softly.assertThat(batchOn(MONDAY))
                    .as("and nothing is written: the batch is still the one the reconciler is "
                            + "waiting on")
                    .contains(new BatchOutcome(GENERATING, null, null));
            softly.assertThat(completedByOn(MONDAY))
                    .as("nothing was attributed, because nothing was written")
                    .isEmpty();
        }

        /**
         * And the direction that accepts, which is the ordinary path the reconciler walks.
         *
         * <p>RECONCILER rather than EVENT: a timeout is by definition an ending no event carried,
         * so the mechanism that learned it is always the one that went and asked. The row keeps the
         * bounded code, the attribution and the stamp on its registers, because systemdocgenerator
         * was asked and a document may yet exist under that correlation.
         */
        @Test
        void a_timed_out_generation_the_reconciler_reported_should_be_recorded_as_its_verdict() {
            final DistributionCommand first = seededCommand(HEARING_ONE, MONDAY_SHARED);

            softly.assertThatCode(() -> {
                record(first, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final RegisterBatch monday = store.assemble(
                        new CourtCentreDay(courtCentre, MONDAY), mine(store.activeUnbatched()));
                store.markRequested(monday.batchId(), PAYLOAD_FILE_ID);
                store.markFailed(monday.batchId(), BatchFailureReason.GENERATION_TIMED_OUT, null,
                        CompletedBy.RECONCILER);
            }).as(WALKED).doesNotThrowAnyException();

            softly.assertThat(batchOn(MONDAY))
                    .as("the bounded code is what the batches counter labels the outcome with, and "
                            + "the renderer said nothing for sdg_reason to hold")
                    .contains(new BatchOutcome(FAILED, "GENERATION_TIMED_OUT", null));
            softly.assertThat(completedByOn(MONDAY))
                    .as("a timeout is an ending no event carried, so the mechanism that learned it "
                            + "is always the one that went and asked")
                    .contains("RECONCILER");
            softly.assertThat(stampedRowsOn(MONDAY))
                    .as("and the stamp stays: systemdocgenerator was asked, so a document may yet "
                            + "exist under that correlation and re-rendering is a person's decision")
                    .isEqualTo(1);
            softly.assertThat(statusesOn(MONDAY))
                    .as("the register was never sent, so it stays exactly what it was")
                    .containsExactly(RECORDED);
        }
    }

    /**
     * The ending where everybody who could be told has been, and what it does to the rows.
     *
     * <p>Three of the seven states settle a notification run, and the rows reach NOTIFIED under all
     * three: everybody was told, somebody was not, or there was nobody to tell. The third is defect
     * fix P1 - the progression leg leaves a batch nobody subscribes to sitting generated for ever,
     * waiting for an event nobody publishes - and it is a state no count of failures can produce,
     * which is why the verdict travels with the tally rather than being derived from it.
     *
     * <p>The flip is scoped to the batch for the same reason {@code markGenerated}'s is, and the
     * scoping is asserted the same way: two days at one court centre, one of them notified, and the
     * other day's rows still saying they are waiting.
     */
    @Nested
    @DisplayName("settling a batch's notifications")
    class Notification {

        @Test
        void notification_flips_only_the_batchs_own_rows() {
            final DistributionCommand mondayFirst = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand mondaySecond = seededCommand(HEARING_TWO, MONDAY_SHARED);
            final DistributionCommand tuesdayFirst = seededCommand(HEARING_THREE, TUESDAY_SHARED);
            final DistributionCommand tuesdaySecond = seededCommand(HEARING_FOUR, TUESDAY_SHARED);

            softly.assertThatCode(() -> {
                record(mondayFirst, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                record(mondaySecond, document(HEARING_TWO, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                record(tuesdayFirst, document(HEARING_THREE, TUESDAY, TUESDAY_SHARED),
                        APPLICANT, RecordedFlagState.ON);
                record(tuesdaySecond, document(HEARING_FOUR, TUESDAY, TUESDAY_SHARED),
                        APPLICANT, RecordedFlagState.ON);
                final List<RegisterRecord> waiting = mine(store.activeUnbatched());
                final RegisterBatch monday = store.assemble(
                        new CourtCentreDay(courtCentre, MONDAY), recordsOn(waiting, MONDAY));
                final RegisterBatch tuesday = store.assemble(
                        new CourtCentreDay(courtCentre, TUESDAY), recordsOn(waiting, TUESDAY));
                generate(monday, PAYLOAD_FILE_ID, DOCUMENT_FILE_ID);
                generate(tuesday, SECOND_PAYLOAD_FILE_ID, SECOND_DOCUMENT_FILE_ID);
                store.markNotified(monday.batchId(), new NotificationSummary(1, 0,
                        BatchStatus.NOTIFIED));
            }).as(WALKED).doesNotThrowAnyException();

            softly.assertThat(statusesOn(MONDAY))
                    .as("the batch whose recipients were told is the batch whose rows move")
                    .containsExactly(NOTIFIED, NOTIFIED);
            softly.assertThat(statusesOn(TUESDAY))
                    .as("Tuesday's register has been generated and nobody has been told about it "
                            + "yet; a widened flip would say it had been sent")
                    .containsExactly(GENERATED, GENERATED);
            softly.assertThat(batchOn(TUESDAY))
                    .as("and Tuesday's batch is still waiting for its own notification run")
                    .contains(new BatchOutcome(GENERATED, null, null));
        }

        @ParameterizedTest
        @EnumSource(value = BatchStatus.class,
                names = {"NOTIFIED", "PARTIALLY_NOTIFIED", "NOTIFIED_NOBODY"})
        void every_notification_outcome_should_settle_the_batch_and_move_its_rows(
                final BatchStatus outcome) {
            final DistributionCommand first = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand second = seededCommand(HEARING_TWO, MONDAY_SHARED);

            softly.assertThatCode(() -> {
                record(first, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                record(second, document(HEARING_TWO, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                final RegisterBatch monday = store.assemble(
                        new CourtCentreDay(courtCentre, MONDAY), mine(store.activeUnbatched()));
                generate(monday, PAYLOAD_FILE_ID, DOCUMENT_FILE_ID);
                store.markNotified(monday.batchId(), tallyFor(outcome));
            }).as(WALKED).doesNotThrowAnyException();

            softly.assertThat(batchOn(MONDAY))
                    .as("the verdict the tally carried is the state the batch ends in, and all "
                            + "three of them are endings rather than somewhere in the middle")
                    .contains(new BatchOutcome(outcome.name(), null, null));
            softly.assertThat(statusesOn(MONDAY))
                    .as("the rows follow the batch under every ending, P1's included: a batch with "
                            + "no recipients is finished, not generated for ever")
                    .containsExactly(NOTIFIED, NOTIFIED);
        }

        @Test
        void a_tally_that_settles_no_notification_should_be_refused_before_the_write() {
            final DistributionCommand first = seededCommand(HEARING_ONE, MONDAY_SHARED);
            final DistributionCommand second = seededCommand(HEARING_TWO, MONDAY_SHARED);

            softly.assertThatCode(() -> {
                record(first, document(HEARING_ONE, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                record(second, document(HEARING_TWO, MONDAY, MONDAY_SHARED), APPLICANT,
                        RecordedFlagState.ON);
                generate(store.assemble(new CourtCentreDay(courtCentre, MONDAY),
                        mine(store.activeUnbatched())), PAYLOAD_FILE_ID, DOCUMENT_FILE_ID);
            }).as(WALKED).doesNotThrowAnyException();
            final UUID batchId = batchIdOn(MONDAY);

            softly.assertThatThrownBy(() -> store.markNotified(batchId,
                            new NotificationSummary(0, 0, BatchStatus.FAILED)))
                    .as("a notification run ends in one of three states; a summary carrying any "
                            + "other verdict is a caller that lost the tally it meant to write")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("NOTIFIED_NOBODY");
            softly.assertThat(batchOn(MONDAY))
                    .as("and the refusal is made before the statement, so the batch is exactly "
                            + "where the document left it")
                    .contains(new BatchOutcome(GENERATED, null, null));
            softly.assertThat(statusesOn(MONDAY)).containsExactly(GENERATED, GENERATED);
        }

        /** The path from a stamped batch to a generated one, which every ending starts from. */
        private void generate(final RegisterBatch batch, final UUID payloadFileId,
                final UUID documentFileId) {
            store.markRequested(batch.batchId(), payloadFileId);
            store.markGenerated(batch.batchId(), documentFileId, GENERATED_AT, CompletedBy.EVENT);
        }

        /**
         * The counts each ending is reached with, so the tally and its verdict agree.
         *
         * <p>Nought and nought is NOTIFIED_NOBODY and nothing else: a batch that had recipients and
         * failed every one of them reports the failures, which is what makes the two tellable apart.
         */
        private NotificationSummary tallyFor(final BatchStatus outcome) {
            return switch (outcome) {
                case NOTIFIED -> new NotificationSummary(2, 0, outcome);
                case PARTIALLY_NOTIFIED -> new NotificationSummary(1, 1, outcome);
                case NOTIFIED_NOBODY -> new NotificationSummary(0, 0, outcome);
                default -> throw new IllegalArgumentException(
                        "no notification run ends in " + outcome);
            };
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

    /**
     * The recording every case makes, under this suite's court centre OU code.
     *
     * <p>The code is a fact about the court centre rather than about any one case, so it is named
     * once here instead of at each of the thirty call sites. The case that is <em>about</em> the OU
     * code calls the port directly, so the port's own shape is still asserted somewhere.
     */
    private RecordOutcome record(final DistributionCommand command,
            final CourtRegisterDocument document, final String defendantType,
            final RecordedFlagState flagState) {
        return store.record(command, document, OU_CODE, defendantType, flagState);
    }

    /**
     * Runs the body with a unique constraint the recorder has never heard of in place.
     *
     * <p>Over {@code court_house} and partial on this case's court centre, so it refuses a second
     * register at the same court house and is invisible to every other suite sharing the container.
     * Dropped whatever the body does: an index left behind would refuse the next case to record two
     * hearings here, and the failure would name a constraint that is not in any migration.
     *
     * @param refused what is expected to meet the constraint
     */
    private void withACourtHouseUniqueIndex(final Runnable refused) {
        final String index = "test_only_court_house_" + courtCentre.toString().replace("-", "");
        ProcessedLogTestSupport.jdbcClient()
                .sql("CREATE UNIQUE INDEX " + index + " ON processed_output (court_house) "
                        + "WHERE court_centre_id = '" + courtCentre + "'")
                .update();
        try {
            refused.run();
        } finally {
            ProcessedLogTestSupport.jdbcClient().sql("DROP INDEX " + index).update();
        }
    }

    /**
     * Two re-shares of one hearing recorded at once, with the collision made to happen.
     *
     * <p>Both recorders are submitted and then held at a start line the suite only opens once it
     * has the incumbent register under lock on its own connection. Each statement then reads that
     * incumbent on its own snapshot and parks on the supersession it is about to make, so neither
     * can commit before the other has read - which is the whole of the race, and the one thing a
     * sequence of calls cannot produce.
     *
     * @param incumbent the register both re-shares replace, held while they gather behind it
     * @param first     one re-share
     * @param second    the other, whose register instant is the later of the two
     * @return whatever escaped either recorder, which is expected to be nothing
     */
    private List<Throwable> raceToRecord(final DistributionCommand incumbent,
            final DistributionCommand first, final DistributionCommand second) {
        final CountDownLatch startLine = new CountDownLatch(1);
        try (ExecutorService racers = Executors.newFixedThreadPool(RACERS)) {
            final List<Future<RecordOutcome>> attempts = List.of(
                    racers.submit(reshare(startLine, first)),
                    racers.submit(reshare(startLine, second)));
            holdTheIncumbent(incumbent, startLine);
            return escapedFrom(attempts);
        }
    }

    /** One re-share, waiting at the start line until the register it replaces is under lock. */
    private Callable<RecordOutcome> reshare(
            final CountDownLatch startLine, final DistributionCommand command) {
        return () -> {
            startLine.await();
            return record(command, document(HEARING_ONE, MONDAY, command.sharedTime()), APPLICANT,
                    RecordedFlagState.ON);
        };
    }

    /**
     * Holds the register both re-shares supersede until both of them are parked behind it.
     *
     * <p>The lock is taken before the start line opens, so no recorder can be past it, and released
     * by this transaction committing once the database says both are waiting. A recorder that had
     * not yet issued its statement when the lock went would read an incumbent one of the others had
     * already committed, and the suite would be asserting the ordinary case under a concurrent
     * name.
     */
    private void holdTheIncumbent(
            final DistributionCommand incumbent, final CountDownLatch startLine) {
        ProcessedLogTestSupport.transactions().executeWithoutResult(held -> {
            ProcessedLogTestSupport.jdbcClient()
                    .sql("""
                            SELECT output_id
                              FROM processed_output
                             WHERE source = :source AND request_id = :requestId
                               FOR UPDATE
                            """)
                    .param("source", incumbent.source())
                    .param("requestId", incumbent.requestId())
                    .query(UUID.class)
                    .single();
            startLine.countDown();
            await().alias("both re-shares parked behind the register they supersede")
                    .atMost(PARKED_DEADLINE)
                    .pollInterval(PARKED_POLL)
                    .until(() -> recordersParkedOnALock() == RACERS);
        });
    }

    /**
     * How many other client backends this database has parked on a lock.
     *
     * <p>Asked of the database rather than inferred from a sleep, and asked on the connection
     * holding the lock, so the connection doing the asking is the one excluded.
     */
    private static long recordersParkedOnALock() {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT count(*)
                          FROM pg_stat_activity
                         WHERE datname = current_database()
                           AND backend_type = 'client backend'
                           AND pid <> pg_backend_pid()
                           AND wait_event_type = 'Lock'
                        """)
                .query(Long.class)
                .single();
    }

    /** What each recorder threw, unwrapped from the executor that carried it back. */
    private static List<Throwable> escapedFrom(final List<Future<RecordOutcome>> attempts) {
        final List<Throwable> escaped = new ArrayList<>();
        for (final Future<RecordOutcome> attempt : attempts) {
            try {
                attempt.get();
            } catch (ExecutionException failed) {
                escaped.add(failed.getCause());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "the race was interrupted before it settled", interrupted);
            }
        }
        return List.copyOf(escaped);
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
                null,
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

    private long stampedRowsOn(final LocalDate registerDate) {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT count(*)
                          FROM processed_output
                         WHERE court_centre_id = :courtCentre AND register_date = :registerDate
                           AND batch_id IS NOT NULL
                        """)
                .param("courtCentre", courtCentre)
                .param("registerDate", registerDate)
                .query(Long.class)
                .single();
    }

    /**
     * The three columns an ending is judged by, read straight back out of {@code register_batch}.
     *
     * <p>Read rather than taken from the {@link RegisterBatch} the port answered with: the port
     * returns the batch as it stood at assembly, and what the endings are about is what the table
     * says afterwards.
     *
     * @param status        where the batch ended
     * @param failureReason this service's own bounded code, or {@code null}
     * @param sdgReason     systemdocgenerator's own words, or {@code null} where it said nothing
     */
    private record BatchOutcome(String status, String failureReason, String sdgReason) {
    }

    private Optional<BatchOutcome> batchOn(final LocalDate registerDate) {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT status, failure_reason, sdg_reason
                          FROM register_batch
                         WHERE court_centre_id = :courtCentre AND register_date = :registerDate
                        """)
                .param("courtCentre", courtCentre)
                .param("registerDate", registerDate)
                .query((rs, rowNumber) -> new BatchOutcome(rs.getString("status"),
                        rs.getString("failure_reason"), rs.getString("sdg_reason")))
                .optional();
    }

    /** Which mechanism the batch's ending was learned from, read back out of the column. */
    private Optional<String> completedByOn(final LocalDate registerDate) {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT completed_by
                          FROM register_batch
                         WHERE court_centre_id = :courtCentre AND register_date = :registerDate
                        """)
                .param("courtCentre", courtCentre)
                .param("registerDate", registerDate)
                .query(String.class)
                .optional();
    }

    /** The OU code the batch was assembled under, read back out of {@code register_batch}. */
    private Optional<String> batchOuCodeOn(final LocalDate registerDate) {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT court_centre_ou_code
                          FROM register_batch
                         WHERE court_centre_id = :courtCentre AND register_date = :registerDate
                        """)
                .param("courtCentre", courtCentre)
                .param("registerDate", registerDate)
                .query(String.class)
                .optional();
    }

    /** This case's batch for a day, insisting the arrangement wrote one. */
    private UUID batchIdOn(final LocalDate registerDate) {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT batch_id
                          FROM register_batch
                         WHERE court_centre_id = :courtCentre AND register_date = :registerDate
                        """)
                .param("courtCentre", courtCentre)
                .param("registerDate", registerDate)
                .query(UUID.class)
                .single();
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

    /**
     * The supersession pair as the row holds it, rather than as the port reported it.
     *
     * @param supersededAt when the row stopped being the active register, or {@code null}
     * @param supersededBy the register that replaced it, or {@code null}
     */
    private record SupersessionPair(Instant supersededAt, UUID supersededBy) {
    }

    private static Optional<SupersessionPair> supersessionOf(final DistributionCommand command) {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT superseded_at, superseded_by
                          FROM processed_output
                         WHERE source = :source AND request_id = :requestId
                        """)
                .param("source", command.source())
                .param("requestId", command.requestId())
                .query((rs, rowNumber) -> new SupersessionPair(
                        instant(rs.getObject("superseded_at", OffsetDateTime.class)),
                        rs.getObject("superseded_by", UUID.class)))
                .optional();
    }

    /**
     * Every column of the row, by name, so "unchanged" means the whole row and not a chosen part.
     *
     * <p>Read through the result set's own metadata rather than as a list this suite maintains: a
     * column added by a later migration is then compared too, which is exactly the column a
     * later statement would be the first to move without anybody noticing. Values are compared as
     * their printed form, because the point is that nothing about the row differs and not which
     * driver type each column arrives as.
     */
    private static Map<String, String> wholeRowOf(final DistributionCommand command) {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT *
                          FROM processed_output
                         WHERE source = :source AND request_id = :requestId
                        """)
                .param("source", command.source())
                .param("requestId", command.requestId())
                .query((rs, rowNumber) -> allColumnsOf(rs))
                .single();
    }

    private static Instant instant(final OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static Map<String, String> allColumnsOf(final ResultSet rs) throws SQLException {
        final ResultSetMetaData columns = rs.getMetaData();
        final Map<String, String> row = new LinkedHashMap<>();
        for (int column = 1; column <= columns.getColumnCount(); column++) {
            row.put(columns.getColumnLabel(column), String.valueOf(rs.getObject(column)));
        }
        return row;
    }

    private static Optional<String> ouCodeOf(final DistributionCommand command) {
        return ProcessedLogTestSupport.jdbcClient()
                .sql("""
                        SELECT court_centre_ou_code
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
