package uk.gov.hmcts.cp.courtregister.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.courtregister.domain.BatchFailureReason;
import uk.gov.hmcts.cp.courtregister.domain.BatchStatus;
import uk.gov.hmcts.cp.courtregister.domain.RegisterBatch;
import uk.gov.hmcts.cp.courtregister.support.PostgresTestSupport;
import uk.gov.hmcts.cp.courtregister.support.ProcessedLogTestSupport;

/**
 * The {@code register_batch} statements, against a real Postgres.
 *
 * <p>{@link JdbcRegisterStore} owns the writes that have to move {@code processed_output} in the
 * same statement; what is left is this repository, and it is the half every other collaborator
 * reaches the batch through - the reconciler's overdue read, the listener's fallback lookup by
 * payload, the operations CLI's own assembly, and the whole-row write that carries the facts the
 * port's {@code mark} signatures do not. Each of its five statements is round-tripped here, because
 * nothing else will: the phases that consume them mock the store, so a column dropped from one of
 * these statements would first be noticed by a batch that could not say what happened to it.
 *
 * <p><strong>The first case is the insert in exactly the state assembly leaves it in</strong>: ten
 * of the row's columns empty, two of them {@code uuid}, bound as the typed nulls the statement
 * declares. A batch that could not be written at assembly is a court centre's day that is never
 * rendered at all, and the round trip is what says the empty columns come back empty rather than
 * defaulted by the table.
 *
 * <p>The three cases in {@code Updating} between them write every column the update statement
 * names, because that is the only way a dropped column is visible: the statement is a whole-row
 * write, so a column left out of it silently keeps whatever the row already held.
 *
 * <p>Every case mints its own court centre, so the suites sharing one container share no rows;
 * {@link #mine(List)} narrows the one read that answers for the whole table.
 */
@DisplayName("register_batch repository")
class RegisterBatchRepositoryIT {

    /** The single row a write of one batch is expected to change. */
    private static final int ONE_ROW = 1;

    private static final LocalDate MONDAY = LocalDate.of(2026, 8, 24);
    private static final LocalDate TUESDAY = LocalDate.of(2026, 8, 25);

    private static final Instant ASSEMBLED_AT = Instant.parse("2026-08-24T17:00:00Z");
    private static final Instant REQUESTED_AT = Instant.parse("2026-08-24T17:00:04Z");
    private static final Instant GENERATED_AT = Instant.parse("2026-08-24T17:04:11Z");
    private static final Instant NOTIFIED_AT = Instant.parse("2026-08-24T17:04:19Z");
    private static final Instant FAILED_AT = Instant.parse("2026-08-24T17:09:31Z");

    /** The far edge of the grace period the reconciler reads behind. */
    private static final Instant GRACE_EDGE = Instant.parse("2026-08-24T17:30:00Z");

    private static final UUID DOCUMENT_FILE_ID =
            UUID.fromString("3a7f1c92-6d84-4b05-9e73-1c2b8a4e07d5");

    private static final String OU_CODE = "B01LY00";
    private static final String COURT_HOUSE = "Lavender Hill Youth Court";

    /** systemdocgenerator's own words, kept for support and never logged at INFO. */
    private static final String SDG_REASON = "template OEE_Layout5 rendered no pages";

    /** This case's court centre: minted per test so no case can read another's rows. */
    private final UUID courtCentre = UUID.randomUUID();

    /**
     * This case's payload ids, minted per test for the same reason as the court centre.
     *
     * <p>{@link RegisterBatchRepository#findByPayloadFileId(UUID)} answers for the whole table and
     * insists on at most one row, so a payload id shared between cases - or with another suite -
     * would make the lookup fail on how many batches the container held rather than on what the
     * statement does.
     */
    private final UUID payloadFileId = UUID.randomUUID();
    private final UUID secondPayloadFileId = UUID.randomUUID();

    private final RegisterBatchRepository repository =
            new RegisterBatchRepository(ProcessedLogTestSupport.jdbcClient());

    @BeforeAll
    static void migrate() {
        PostgresTestSupport.applyFlyway();
    }

    @Nested
    @DisplayName("writing a batch the caller assembled itself")
    class Inserting {

        @Test
        void inserting_an_assembled_batch_should_read_back_exactly_what_the_caller_decided() {
            final RegisterBatch assembled = assembled(MONDAY);

            repository.insert(assembled);

            assertThat(repository.findById(assembled.batchId()))
                    .as("the CLI's assembly says system_generated is false and the schedule's says "
                            + "true, and a row that defaulted either would lose the only record of "
                            + "whether a person or a timer started the run")
                    .contains(assembled);
        }

        @Test
        void inserting_a_batch_the_operations_cli_assembled_should_record_that_a_person_started_it() {
            final RegisterBatch byHand = new RegisterBatch(UUID.randomUUID(), courtCentre, OU_CODE,
                    COURT_HOUSE, MONDAY, fileName(MONDAY), null, null, BatchStatus.PENDING, null,
                    null, false, null, ASSEMBLED_AT, null, null, null, null, 0);

            repository.insert(byHand);

            assertThat(repository.findById(byHand.batchId()).map(RegisterBatch::systemGenerated))
                    .as("progression's own flag, and the CLI is the only writer that sets it false")
                    .contains(false);
        }

        @Test
        void reading_a_batch_this_service_never_recorded_should_answer_that_it_has_none() {
            assertThat(repository.findById(UUID.randomUUID()))
                    .as("an event correlating on a batch nobody assembled is unattributable, and "
                            + "an empty answer is what lets the listener say so")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("finding the batch an outcome names")
    class Finding {

        @Test
        void finding_by_payload_file_id_should_answer_with_the_batch_rendered_from_it() {
            final RegisterBatch requested = requested(MONDAY, payloadFileId, REQUESTED_AT);

            assertThat(repository.findByPayloadFileId(payloadFileId))
                    .as("an outcome names the payload as well as the correlation, so the batch is "
                            + "still findable when only one of the two is trustworthy")
                    .contains(requested);
        }

        @Test
        void finding_by_a_payload_no_batch_owns_should_answer_that_it_has_none() {
            assertThat(repository.findByPayloadFileId(UUID.randomUUID()))
                    .as("a document rendered from a payload this service never stored belongs to "
                            + "nothing here, and the fallback lookup has to be able to say so")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("the batches whose outcome is overdue")
    class Overdue {

        @Test
        void generating_since_should_answer_with_the_overdue_batches_oldest_first() {
            final RegisterBatch first = requested(MONDAY, payloadFileId, REQUESTED_AT);
            final RegisterBatch second = requested(TUESDAY, secondPayloadFileId,
                    REQUESTED_AT.plusSeconds(90));

            assertThat(mine(repository.generatingSince(GRACE_EDGE)))
                    .as("a run that cannot reconcile all of them reconciles the ones that have "
                            + "been waiting longest, which are the registers already missing")
                    .containsExactly(first, second);
        }

        @Test
        void generating_since_should_exclude_a_batch_still_inside_its_grace_period() {
            requested(MONDAY, payloadFileId, GRACE_EDGE.plusSeconds(1));

            assertThat(mine(repository.generatingSince(GRACE_EDGE)))
                    .as("systemdocgenerator is allowed the grace period before anybody asks it "
                            + "again; reconciling inside it would double the render requests")
                    .isEmpty();
        }

        @Test
        void generating_since_should_exclude_a_batch_that_was_never_requested() {
            repository.insert(assembled(MONDAY));

            assertThat(mine(repository.generatingSince(GRACE_EDGE)))
                    .as("a PENDING batch is waiting for this service, not for systemdocgenerator, "
                            + "and querying its outcome would ask about a render nobody requested")
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("writing where a batch has got to")
    class Updating {

        @Test
        void updating_a_generated_batch_should_write_every_column_the_outcome_settled() {
            final RegisterBatch assembled = assembled(MONDAY);
            repository.insert(assembled);
            final RegisterBatch generated = new RegisterBatch(assembled.batchId(), courtCentre,
                    OU_CODE, COURT_HOUSE, MONDAY, fileName(MONDAY), payloadFileId,
                    DOCUMENT_FILE_ID, BatchStatus.NOTIFIED, null, null, true,
                    RegisterBatch.CompletedBy.EVENT, ASSEMBLED_AT, REQUESTED_AT, GENERATED_AT,
                    NOTIFIED_AT, null, 1);

            final int changed = repository.update(generated);

            assertThat(changed)
                    .as("the affected-row count is the decision, and never a read-back")
                    .isEqualTo(ONE_ROW);
            assertThat(repository.findById(assembled.batchId()))
                    .as("a caller that read a batch, decided about it and wrote it back cannot "
                            + "leave half of its decision behind - completed_by above all, which "
                            + "is what the reconciled metric counts and nothing else records")
                    .contains(generated);
        }

        @Test
        void updating_a_failed_batch_should_keep_this_services_code_and_the_renderers_words_apart() {
            final RegisterBatch assembled = assembled(MONDAY);
            repository.insert(assembled);
            final RegisterBatch failed = new RegisterBatch(assembled.batchId(), courtCentre,
                    OU_CODE, COURT_HOUSE, MONDAY, fileName(MONDAY), payloadFileId, null,
                    BatchStatus.FAILED, BatchFailureReason.GENERATION_FAILED, SDG_REASON, true,
                    RegisterBatch.CompletedBy.RECONCILER, ASSEMBLED_AT, REQUESTED_AT, null, null,
                    FAILED_AT, 2);

            assertThat(repository.update(failed)).isEqualTo(ONE_ROW);

            assertThat(repository.findById(assembled.batchId()))
                    .as("the bounded code is what the batches counter labels its outcome with and "
                            + "what the run report prints; the free text is what a person reads, "
                            + "and the two are separate columns so the second can stay out of logs")
                    .contains(failed);
        }

        @Test
        void updating_a_batch_this_service_never_recorded_should_change_nothing() {
            final RegisterBatch absent = assembled(MONDAY);

            assertThat(repository.update(absent))
                    .as("nought rows changed is a batch that is not there, which is a caller "
                            + "acting on a correlation this service never minted")
                    .isZero();
        }
    }

    /** A batch in the state assembly leaves it in: a key, a file name, and ten empty columns. */
    private RegisterBatch assembled(final LocalDate registerDate) {
        return new RegisterBatch(UUID.randomUUID(), courtCentre, OU_CODE, COURT_HOUSE, registerDate,
                fileName(registerDate), null, null, BatchStatus.PENDING, null, null, true, null,
                ASSEMBLED_AT, null, null, null, null, 0);
    }

    /** The same batch after systemdocgenerator accepted its render request. */
    private RegisterBatch generating(final RegisterBatch batch, final UUID payloadFileId,
            final Instant requestedAt) {
        return new RegisterBatch(batch.batchId(), courtCentre, OU_CODE, COURT_HOUSE,
                batch.registerDate(), batch.fileName(), payloadFileId, null, BatchStatus.GENERATING,
                null, null, true, null, ASSEMBLED_AT, requestedAt, null, null, null, 1);
    }

    /** An inserted batch already GENERATING, which is the state the reconciler reads. */
    private RegisterBatch requested(final LocalDate registerDate, final UUID payloadFileId,
            final Instant requestedAt) {
        final RegisterBatch assembled = assembled(registerDate);
        repository.insert(assembled);
        final RegisterBatch requested = generating(assembled, payloadFileId, requestedAt);
        repository.update(requested);
        return requested;
    }

    /**
     * The overdue read, narrowed to the case that asked.
     *
     * <p>The reconciler reads the whole table because it reconciles the whole service; the suites
     * sharing one container do not, so the case that asked is the only one that may be asserted on.
     */
    private List<RegisterBatch> mine(final List<RegisterBatch> batches) {
        return batches.stream()
                .filter(batch -> courtCentre.equals(batch.courtCentreId()))
                .toList();
    }

    private static String fileName(final LocalDate registerDate) {
        return "court-register_" + registerDate + '_' + OU_CODE + ".pdf";
    }
}
