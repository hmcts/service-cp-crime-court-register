package uk.gov.hmcts.cp.courtregister.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * <p>The cases in {@code Moving} between them write every column the update statement names,
 * because that is the only way a dropped column is visible: the statement is a whole-row write, so
 * a column left out of it silently keeps whatever the row already held. They also walk the batch
 * through the states the diagram draws rather than jumping to the one under test, because the write
 * is a compare-and-set and the state machine is what it is set against.
 *
 * <p>Every case mints its own court centre, so the suites sharing one container share no rows;
 * {@link #mine(List)} narrows the one read that answers for the whole table.
 */
@DisplayName("register_batch repository")
class RegisterBatchRepositoryIT {

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

    /** The same words, from a renderer that said far more of them than the column holds. */
    private static final String OVERSIZED_SDG_REASON = (SDG_REASON + "; ").repeat(30);

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
    @DisplayName("moving a batch from the state it was read in")
    class Moving {

        @Test
        void moving_a_batch_to_notified_should_write_every_column_the_outcome_settled() {
            final RegisterBatch assembled = assembled(MONDAY);
            repository.insert(assembled);
            final RegisterBatch requested = generating(assembled, payloadFileId, REQUESTED_AT);
            repository.compareAndSet(requested, BatchStatus.PENDING);
            final RegisterBatch generated = generated(assembled);
            repository.compareAndSet(generated, BatchStatus.GENERATING);
            final RegisterBatch notified = new RegisterBatch(assembled.batchId(), courtCentre,
                    OU_CODE, COURT_HOUSE, MONDAY, fileName(MONDAY), payloadFileId,
                    DOCUMENT_FILE_ID, BatchStatus.NOTIFIED, null, null, true,
                    RegisterBatch.CompletedBy.EVENT, ASSEMBLED_AT, REQUESTED_AT, GENERATED_AT,
                    NOTIFIED_AT, null, 1);

            final boolean moved = repository.compareAndSet(notified, BatchStatus.GENERATED);

            assertThat(moved)
                    .as("whether a row changed is the decision, and never a read-back")
                    .isTrue();
            assertThat(repository.findById(assembled.batchId()))
                    .as("a caller that read a batch, decided about it and wrote it back cannot "
                            + "leave half of its decision behind - completed_by above all, which "
                            + "is what the reconciled metric counts and nothing else records")
                    .contains(notified);
        }

        @Test
        void moving_a_batch_to_failed_should_keep_this_services_code_and_the_renderers_words_apart() {
            final RegisterBatch assembled = assembled(MONDAY);
            repository.insert(assembled);
            final RegisterBatch failed = new RegisterBatch(assembled.batchId(), courtCentre,
                    OU_CODE, COURT_HOUSE, MONDAY, fileName(MONDAY), payloadFileId, null,
                    BatchStatus.FAILED, BatchFailureReason.GENERATION_FAILED, SDG_REASON, true,
                    RegisterBatch.CompletedBy.RECONCILER, ASSEMBLED_AT, REQUESTED_AT, null, null,
                    FAILED_AT, 2);

            assertThat(repository.compareAndSet(failed, BatchStatus.PENDING)).isTrue();

            assertThat(repository.findById(assembled.batchId()))
                    .as("the bounded code is what the batches counter labels its outcome with and "
                            + "what the run report prints; the free text is what a person reads, "
                            + "and the two are separate columns so the second can stay out of logs")
                    .contains(failed);
        }

        /**
         * How long systemdocgenerator's message is is its decision, and the column's bound is this
         * service's. The batch applies the bound as it is built, so a caller cannot carry a reason
         * the row will refuse - which would fail the write that was recording why the batch failed.
         */
        @Test
        void an_oversized_renderer_reason_should_be_bounded_by_the_batch_that_carries_it() {
            final RegisterBatch assembled = assembled(MONDAY);
            repository.insert(assembled);
            final RegisterBatch failed = new RegisterBatch(assembled.batchId(), courtCentre,
                    OU_CODE, COURT_HOUSE, MONDAY, fileName(MONDAY), payloadFileId, null,
                    BatchStatus.FAILED, BatchFailureReason.GENERATION_FAILED, OVERSIZED_SDG_REASON,
                    true, RegisterBatch.CompletedBy.RECONCILER, ASSEMBLED_AT, REQUESTED_AT, null,
                    null, FAILED_AT, 2);

            assertThat(repository.compareAndSet(failed, BatchStatus.PENDING))
                    .as("the row the renderer's verbosity would otherwise have refused")
                    .isTrue();

            assertThat(repository.findById(assembled.batchId()).map(RegisterBatch::sdgReason))
                    .as("bounded, and saying so: a reader who cannot see the rest must be able to "
                            + "tell that there is a rest")
                    .hasValueSatisfying(reason -> assertThat(reason)
                            .hasSize(RegisterBatch.REASON_LIMIT)
                            .startsWith(SDG_REASON)
                            .endsWith(" [truncated]"));
        }

        @Test
        void moving_a_batch_this_service_never_recorded_should_change_nothing() {
            final RegisterBatch absent = generating(assembled(MONDAY), payloadFileId, REQUESTED_AT);

            assertThat(repository.compareAndSet(absent, BatchStatus.PENDING))
                    .as("no row changed is a batch that is not there, which is a caller acting on "
                            + "a correlation this service never minted")
                    .isFalse();
        }

        /**
         * FAILED is terminal, and the diagram's {@code FAILED -> PENDING} is the re-assembly CLI
         * minting a <em>new</em> batch identity rather than reviving this one.
         *
         * <p>A whole-row write with no expected state would revive it: systemdocgenerator's verdict
         * about the old identity stays attached to a batch that is being rendered again, and the
         * failure that was reported to an operator quietly stops being true.
         */
        @Test
        void reviving_a_failed_batch_should_be_refused_by_the_state_machine() {
            final RegisterBatch assembled = assembled(MONDAY);
            repository.insert(assembled);
            final RegisterBatch failed = new RegisterBatch(assembled.batchId(), courtCentre,
                    OU_CODE, COURT_HOUSE, MONDAY, fileName(MONDAY), payloadFileId, null,
                    BatchStatus.FAILED, BatchFailureReason.GENERATION_FAILED, SDG_REASON, true,
                    RegisterBatch.CompletedBy.RECONCILER, ASSEMBLED_AT, REQUESTED_AT, null, null,
                    FAILED_AT, 2);
            repository.compareAndSet(failed, BatchStatus.PENDING);
            final RegisterBatch revived = generating(assembled, payloadFileId, REQUESTED_AT);

            assertThatThrownBy(() -> repository.compareAndSet(revived, BatchStatus.FAILED))
                    .as("the move is refused where it is attempted, not discovered afterwards "
                            + "from a row that already changed")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("FAILED")
                    .hasMessageContaining("GENERATING");
            assertThat(repository.findById(assembled.batchId()))
                    .as("and the batch is still the failure it was")
                    .contains(failed);
        }

        /**
         * The other half: a move the machine draws, made against a state the batch has left.
         */
        @Test
        void a_stale_expected_status_should_change_nothing_and_say_so() {
            final RegisterBatch assembled = assembled(MONDAY);
            repository.insert(assembled);
            final RegisterBatch requested = generating(assembled, payloadFileId, REQUESTED_AT);
            repository.compareAndSet(requested, BatchStatus.PENDING);
            final RegisterBatch again = generating(assembled, secondPayloadFileId,
                    REQUESTED_AT.plusSeconds(90));

            assertThat(repository.compareAndSet(again, BatchStatus.PENDING))
                    .as("a second run that still believes the batch is PENDING changes nothing, "
                            + "and is told so rather than being left to assume it requested a "
                            + "render that another run had already requested")
                    .isFalse();
            assertThat(repository.findById(assembled.batchId()))
                    .as("the payload the first run actually stored is still the batch's")
                    .contains(requested);
        }
    }

    @Nested
    @DisplayName("a batch enters this table at the beginning")
    class Insertion {

        /**
         * Every writer of this statement is assembling: the operations CLI, and the re-assembly of a
         * FAILED batch under a fresh identity. Both start at PENDING, and a row inserted anywhere
         * else in the machine is a batch that skipped the states it should have been moved through
         * - and whose earlier stamps therefore describe events that never happened.
         */
        @Test
        void inserting_a_batch_anywhere_but_pending_should_be_refused() {
            final RegisterBatch midway =
                    generating(assembled(MONDAY), payloadFileId, REQUESTED_AT);

            assertThatThrownBy(() -> repository.insert(midway))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("GENERATING");
            assertThat(repository.findById(midway.batchId()))
                    .as("and nothing is written")
                    .isEmpty();
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

    /** The same batch again once systemdocgenerator's document exists. */
    private RegisterBatch generated(final RegisterBatch batch) {
        return new RegisterBatch(batch.batchId(), courtCentre, OU_CODE, COURT_HOUSE,
                batch.registerDate(), batch.fileName(), payloadFileId, DOCUMENT_FILE_ID,
                BatchStatus.GENERATED, null, null, true, RegisterBatch.CompletedBy.EVENT,
                ASSEMBLED_AT, REQUESTED_AT, GENERATED_AT, null, null, 1);
    }

    /** An inserted batch already GENERATING, which is the state the reconciler reads. */
    private RegisterBatch requested(final LocalDate registerDate, final UUID payloadFileId,
            final Instant requestedAt) {
        final RegisterBatch assembled = assembled(registerDate);
        repository.insert(assembled);
        final RegisterBatch requested = generating(assembled, payloadFileId, requestedAt);
        repository.compareAndSet(requested, BatchStatus.PENDING);
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
