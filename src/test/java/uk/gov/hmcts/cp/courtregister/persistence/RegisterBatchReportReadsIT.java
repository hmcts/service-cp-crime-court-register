package uk.gov.hmcts.cp.courtregister.persistence;

import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.data.Offset.offset;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.assertj.core.api.SoftAssertions;
import org.assertj.core.api.junit.jupiter.InjectSoftAssertions;
import org.assertj.core.api.junit.jupiter.SoftAssertionsExtension;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import uk.gov.hmcts.cp.courtregister.domain.BatchException;
import uk.gov.hmcts.cp.courtregister.domain.BatchFailureReason;
import uk.gov.hmcts.cp.courtregister.domain.BatchStatus;
import uk.gov.hmcts.cp.courtregister.domain.CompletedBy;
import uk.gov.hmcts.cp.courtregister.domain.RegisterBatch;
import uk.gov.hmcts.cp.courtregister.domain.StoreUnavailableException;
import uk.gov.hmcts.cp.courtregister.support.PostgresTestSupport;
import uk.gov.hmcts.cp.courtregister.support.ReportReadsDatabase;

/**
 * The four reads the exception report makes of the batch table.
 *
 * <p>Three of them are BATCH_LATE - a batch nothing has been asked of the renderer for, one whose
 * answer has not come back, one holding a document nobody was told about - and the fourth is
 * BATCH_FAILED, the downstream half's equivalent of a parked request. All four answer a projection
 * carrying an age, and each measures it from its own stage timestamp, because "waiting" means a
 * different moment in each of the four.
 *
 * <p><strong>They are new reads rather than 002's three</strong>, and one case here says why by
 * asserting the old ones still answer entities and still answer what their own suites expect. A
 * report that used {@code pendingSince}, {@code generatingSince} and {@code generatedSince} would
 * have to subtract a stored timestamp from a JVM reading, and widening them to produce an age would
 * change a leg this increment does not otherwise touch.
 *
 * <p>A database of this suite's own, and soft assertions, for the reasons
 * {@link ProcessedRequestReportReadsIT} gives.
 */
@ExtendWith(SoftAssertionsExtension.class)
@DisplayName("the batch table's report reads")
class RegisterBatchReportReadsIT {

    private static final String DATABASE = "courtregister_batch_report_reads";

    private static final String BATCH_TABLE = "register_batch";

    private static final LocalDate MONDAY = LocalDate.of(2026, 9, 14);
    private static final LocalDate TUESDAY = LocalDate.of(2026, 9, 15);

    private static final String OU_CODE = "B01LY00";
    private static final String COURT_HOUSE = "Lavender Hill Youth Court";

    private static final UUID DOCUMENT_FILE_ID =
            UUID.fromString("3a7f1c92-6d84-4b05-9e73-1c2b8a4e07d5");

    /** systemdocgenerator's own words about somebody's document, and the column with no reader. */
    private static final String SDG_REASON = "template OEE_Layout5 rendered no pages";

    private static final String SDG_REASON_COLUMN = "sdg_reason";

    private static final Duration NOTIFIER_LEASE = Duration.ofMinutes(15);

    private static final String SEAM =
            "the report's four batch reads implement these statements; this is their red run";

    private static final long SECONDS_OF_SLACK = 5;

    private static ReportReadsDatabase database;
    private static RegisterBatchRepository repository;

    @InjectSoftAssertions
    private SoftAssertions softly;

    @BeforeAll
    static void migrate() {
        database = ReportReadsDatabase.migrated(DATABASE);
        repository = new RegisterBatchRepository(database.jdbcClient(), database.transactions(),
                NOTIFIER_LEASE);
    }

    @BeforeEach
    void emptyTheTable() {
        database.empty(BATCH_TABLE);
    }

    /**
     * What each of the four answers with, and what each of them leaves out.
     */
    @Nested
    @DisplayName("what each read answers with")
    class WhatIsAnswered {

        @Test
        void late_pending_returns_pending_batches_assembled_before_the_cut_off_oldest_first() {
            final RegisterBatch oldest = pending(MONDAY, minutesAgo(90));
            final RegisterBatch next = pending(TUESDAY, minutesAgo(45));
            pending(MONDAY, minutesAgo(2));
            generating(TUESDAY, minutesAgo(90));

            softly.assertThat(latePending(minutesAgo(30)))
                    .as("a court centre's day nothing has been asked of the renderer for, oldest "
                            + "first; a batch assembled a moment ago is a run still working")
                    .extracting(BatchException::batchId)
                    .containsExactly(oldest.batchId(), next.batchId());
        }

        @Test
        void late_generating_returns_generating_batches_requested_before_the_cut_off_oldest_first() {
            final RegisterBatch oldest = generating(MONDAY, minutesAgo(90));
            final RegisterBatch next = generating(TUESDAY, minutesAgo(45));
            generating(MONDAY, minutesAgo(2));
            pending(TUESDAY, minutesAgo(90));

            softly.assertThat(lateGenerating(minutesAgo(30)))
                    .as("a render systemdocgenerator accepted and has not answered, oldest first")
                    .extracting(BatchException::batchId)
                    .containsExactly(oldest.batchId(), next.batchId());
        }

        @Test
        void late_generated_returns_generated_batches_generated_before_the_cut_off_oldest_first() {
            final RegisterBatch oldest = generated(MONDAY, minutesAgo(90));
            final RegisterBatch next = generated(TUESDAY, minutesAgo(45));
            generated(MONDAY, minutesAgo(2));
            generating(TUESDAY, minutesAgo(90));

            softly.assertThat(lateGenerated(minutesAgo(30)))
                    .as("the one state that leaves a Youth Offending Team untold while a document "
                            + "for it exists, oldest first")
                    .extracting(BatchException::batchId)
                    .containsExactly(oldest.batchId(), next.batchId());
        }

        @Test
        void failed_since_returns_batches_failed_inside_the_window_and_nothing_else() {
            final RegisterBatch failed = failed(MONDAY, minutesAgo(45));
            failed(TUESDAY, hoursAgo(9));
            generating(MONDAY, hoursAgo(9));

            softly.assertThat(failedSince(hoursAgo(2)))
                    .as("a report is a statement about a period: a batch that ended before the "
                            + "window belongs to the report that already named it")
                    .extracting(BatchException::batchId)
                    .containsExactly(failed.batchId());
        }

        @Test
        void every_read_carries_the_court_centre_the_register_date_and_the_bounded_failure_reason() {
            final RegisterBatch waiting = pending(MONDAY, minutesAgo(90));
            final RegisterBatch requested = generating(MONDAY, minutesAgo(90));
            final RegisterBatch rendered = generated(MONDAY, minutesAgo(90));
            final RegisterBatch ended = failed(TUESDAY, minutesAgo(90));

            softly.assertThat(latePending(minutesAgo(30)))
                    .as("the key, because a support engineer reads a national morning a court "
                            + "centre's day at a time; nothing has ended, so no reason")
                    .extracting(BatchException::courtCentreId, BatchException::registerDate,
                            BatchException::status, BatchException::failureReason,
                            BatchException::attempts)
                    .containsExactly(tuple(waiting.courtCentreId(), MONDAY, BatchStatus.PENDING,
                            null, 0));
            softly.assertThat(lateGenerating(minutesAgo(30)))
                    .as("the same three facts, and the attempt tally the render moved")
                    .extracting(BatchException::courtCentreId, BatchException::registerDate,
                            BatchException::status, BatchException::failureReason,
                            BatchException::attempts)
                    .containsExactly(tuple(requested.courtCentreId(), MONDAY,
                            BatchStatus.GENERATING, null, 1));
            softly.assertThat(lateGenerated(minutesAgo(30)))
                    .as("and again for the batch holding a document")
                    .extracting(BatchException::courtCentreId, BatchException::registerDate,
                            BatchException::status, BatchException::failureReason)
                    .containsExactly(tuple(rendered.courtCentreId(), MONDAY, BatchStatus.GENERATED,
                            null));
            softly.assertThat(failedSince(hoursAgo(2)))
                    .as("the bounded reason, which is this service's own code with a fixed "
                            + "meaning and never the renderer's words about the document")
                    .extracting(BatchException::courtCentreId, BatchException::registerDate,
                            BatchException::status, BatchException::failureReason)
                    .containsExactly(tuple(ended.courtCentreId(), TUESDAY, BatchStatus.FAILED,
                            BatchFailureReason.GENERATION_FAILED));
        }
    }

    /**
     * Where the ages come from, and the column none of the four may read.
     */
    @Nested
    @DisplayName("how the four behave as statements")
    class AsStatements {

        @Test
        void age_seconds_is_answered_in_seconds_from_the_stage_timestamp() {
            pending(MONDAY, minutesAgo(90));
            generating(MONDAY, minutesAgo(60));
            generated(MONDAY, minutesAgo(45));
            failed(TUESDAY, minutesAgo(30));

            final List<Long> ages = everyAge();
            final List<String> statements = everyStatement();
            final List<Long> stages = List.of(Duration.ofMinutes(90).toSeconds(),
                    Duration.ofMinutes(60).toSeconds(), Duration.ofMinutes(45).toSeconds(),
                    Duration.ofMinutes(30).toSeconds());

            for (int read = 0; read < stages.size(); read++) {
                softly.assertThat(ages.get(read))
                        .as("read %d measures from its own stage timestamp: assembly, the render "
                                + "request, the document, the ending", read)
                        .isCloseTo(stages.get(read), offset(SECONDS_OF_SLACK));
                softly.assertThat(statements.get(read))
                        .as("and read %d takes that reading in the database, in the same "
                                + "statement that selects the row - which is the only place it "
                                + "can be taken, because this repository holds no clock to "
                                + "compare a stored timestamp against (V1's single time "
                                + "authority)", read)
                        .contains("now()")
                        .contains("extract(epoch");
            }
        }

        @Test
        void every_read_goes_through_store_outage_translating() {
            PostgresTestSupport.refuseConnectionsTo(DATABASE);
            try {
                softly.assertThatThrownBy(() -> repository.latePending(minutesAgo(30)))
                        .as("an unreachable store is the generation half's own signal, and a "
                                + "org.springframework.dao type reaching the core is Principle V")
                        .isInstanceOf(StoreUnavailableException.class);
                softly.assertThatThrownBy(() -> repository.lateGenerating(minutesAgo(30)))
                        .as("the same for the render nobody has answered for")
                        .isInstanceOf(StoreUnavailableException.class);
                softly.assertThatThrownBy(() -> repository.lateGenerated(minutesAgo(30)))
                        .as("and for the document nobody has been told about")
                        .isInstanceOf(StoreUnavailableException.class);
                softly.assertThatThrownBy(() -> repository.failedSince(hoursAgo(2)))
                        .as("and for the batches that ended, so a morning the database is away "
                                + "is a run that failed for a reason with a name rather than a "
                                + "driver exception nobody classified")
                        .isInstanceOf(StoreUnavailableException.class);
            } finally {
                PostgresTestSupport.allowConnectionsTo(DATABASE);
            }
        }

        @Test
        void the_sdg_reason_column_is_never_selected_by_any_of_the_four() {
            failed(MONDAY, minutesAgo(30));
            database.forgetStatements();

            latePending(minutesAgo(10));
            lateGenerating(minutesAgo(10));
            lateGenerated(minutesAgo(10));
            failedSince(hoursAgo(2));

            softly.assertThat(database.statements())
                    .as("four reads, four statements")
                    .hasSize(4);
            softly.assertThat(String.join("\n", database.statements()))
                    .as("another system's free text about a document whose every defendant is a "
                            + "child: a column that is never read cannot reach a line, a label or "
                            + "the CSV")
                    .doesNotContain(SDG_REASON_COLUMN);
        }

        @Test
        void the_002_entity_reads_are_untouched() {
            final RegisterBatch waiting = pending(MONDAY, minutesAgo(90));
            final RegisterBatch requested = generating(MONDAY, minutesAgo(90));
            final RegisterBatch rendered = generated(TUESDAY, minutesAgo(90));

            softly.assertThat(repository.generatingSince(minutesAgo(30)))
                    .as("002's read still answers entities, and still answers the same rows the "
                            + "reconciler's own suite expects of it")
                    .containsExactly(requested);
            softly.assertThat(repository.generatedSince(minutesAgo(30)))
                    .as("likewise the parked read, unrenamed and unwidened")
                    .containsExactly(rendered);
            softly.assertThat(repository.pendingSince(minutesAgo(30)))
                    .as("and its own predicate is untouched: the reconciler can only ask about a "
                            + "payload, so a batch that never minted one is invisible to it")
                    .isEmpty();
            softly.assertThat(latePending(minutesAgo(30)))
                    .as("which is exactly why the report needs a read of its own - that batch has "
                            + "been waiting longest of all")
                    .extracting(BatchException::batchId)
                    .containsExactly(waiting.batchId());
        }
    }

    // --- the reads, made so that a seam's refusal is recorded rather than thrown ----------------

    private List<BatchException> latePending(final Instant assembledBefore) {
        return answered(() -> repository.latePending(assembledBefore));
    }

    private List<BatchException> lateGenerating(final Instant requestedBefore) {
        return answered(() -> repository.lateGenerating(requestedBefore));
    }

    private List<BatchException> lateGenerated(final Instant generatedBefore) {
        return answered(() -> repository.lateGenerated(generatedBefore));
    }

    private List<BatchException> failedSince(final Instant since) {
        return answered(() -> repository.failedSince(since));
    }

    private List<BatchException> answered(final Supplier<List<BatchException>> read) {
        final AtomicReference<List<BatchException>> answer = new AtomicReference<>(List.of());
        softly.assertThatCode(() -> answer.set(read.get())).as(SEAM).doesNotThrowAnyException();
        return answer.get();
    }

    /** The one age each of the four reads answers with, in the order the four are listed. */
    private List<Long> everyAge() {
        return List.of(firstAge(latePending(minutesAgo(10))),
                firstAge(lateGenerating(minutesAgo(10))),
                firstAge(lateGenerated(minutesAgo(10))),
                firstAge(failedSince(hoursAgo(4))));
    }

    /** The statement each of the four really prepared, in the same order. */
    private List<String> everyStatement() {
        return List.of(statementOf(() -> latePending(minutesAgo(10))),
                statementOf(() -> lateGenerating(minutesAgo(10))),
                statementOf(() -> lateGenerated(minutesAgo(10))),
                statementOf(() -> failedSince(hoursAgo(4))));
    }

    /**
     * The SQL one read was prepared with, taken off the driver rather than spelled again here.
     *
     * @param read the read to make
     * @return its statement, or an empty string where the seam refused before preparing one
     */
    private String statementOf(final Runnable read) {
        database.forgetStatements();
        read.run();
        final List<String> executed = database.statements();
        return executed.isEmpty() ? "" : executed.get(0);
    }

    private static long firstAge(final List<BatchException> answered) {
        return answered.isEmpty() ? -1 : answered.get(0).ageSeconds();
    }

    // --- seeding: every batch walks the states the diagram draws --------------------------------

    /** A batch of its own court centre, where a batch enters the table. */
    private RegisterBatch pending(final LocalDate registerDate, final Instant assembledAt) {
        final RegisterBatch assembled = new RegisterBatch(UUID.randomUUID(), UUID.randomUUID(),
                OU_CODE, COURT_HOUSE, registerDate, fileName(registerDate), null, null,
                BatchStatus.PENDING, null, null, true, null, assembledAt, null, null, null, null,
                0, null, 0);
        repository.insert(assembled);
        return assembled;
    }

    /** The same batch after systemdocgenerator accepted its render request. */
    private RegisterBatch generating(final LocalDate registerDate, final Instant requestedAt) {
        final RegisterBatch assembled = pending(registerDate, requestedAt.minusSeconds(4));
        final RegisterBatch requested = new RegisterBatch(assembled.batchId(),
                assembled.courtCentreId(), OU_CODE, COURT_HOUSE, registerDate,
                assembled.fileName(), UUID.randomUUID(), null, BatchStatus.GENERATING, null, null,
                true, null, assembled.assembledAt(), requestedAt, null, null, null, 1, null, 0);
        repository.compareAndSet(requested, BatchStatus.PENDING);
        return requested;
    }

    /** And again once its document exists and nobody has been told yet. */
    private RegisterBatch generated(final LocalDate registerDate, final Instant generatedAt) {
        final RegisterBatch requested = generating(registerDate, generatedAt.minusSeconds(60));
        final RegisterBatch rendered = new RegisterBatch(requested.batchId(),
                requested.courtCentreId(), OU_CODE, COURT_HOUSE, registerDate,
                requested.fileName(), requested.payloadFileId(), DOCUMENT_FILE_ID,
                BatchStatus.GENERATED, null, null, true, CompletedBy.EVENT,
                requested.assembledAt(), requested.requestedAt(), generatedAt, null, null, 1, null,
                0);
        repository.compareAndSet(rendered, BatchStatus.GENERATING);
        return rendered;
    }

    /**
     * A batch the renderer said had failed, which is the one ending that carries an
     * {@code sdg_reason}.
     *
     * <p>GENERATION_FAILED deliberately: it is the reason whose row holds systemdocgenerator's own
     * words, so the column the four reads may never select is really populated in this table.
     */
    private RegisterBatch failed(final LocalDate registerDate, final Instant failedAt) {
        final RegisterBatch requested = generating(registerDate, failedAt.minusSeconds(120));
        final RegisterBatch ended = new RegisterBatch(requested.batchId(),
                requested.courtCentreId(), OU_CODE, COURT_HOUSE, registerDate,
                requested.fileName(), requested.payloadFileId(), null, BatchStatus.FAILED,
                BatchFailureReason.GENERATION_FAILED, SDG_REASON, true, CompletedBy.EVENT,
                requested.assembledAt(), requested.requestedAt(), null, null, failedAt, 1, null, 0);
        repository.compareAndSet(ended, BatchStatus.GENERATING);
        return ended;
    }

    private static String fileName(final LocalDate registerDate) {
        return "court-register_" + registerDate + '_' + OU_CODE + ".pdf";
    }

    /**
     * A moment in the past, at the precision the column holds.
     *
     * <p>Truncated to microseconds because {@code timestamptz} is: a nanosecond this JVM minted and
     * the database rounded away would make the entity cases fail on how precisely Postgres stores a
     * timestamp rather than on what the read answers.
     */
    private static Instant hoursAgo(final long hours) {
        return stored(Instant.now().minus(Duration.ofHours(hours)));
    }

    private static Instant minutesAgo(final long minutes) {
        return stored(Instant.now().minus(Duration.ofMinutes(minutes)));
    }

    private static Instant stored(final Instant moment) {
        return moment.truncatedTo(ChronoUnit.MICROS);
    }
}
