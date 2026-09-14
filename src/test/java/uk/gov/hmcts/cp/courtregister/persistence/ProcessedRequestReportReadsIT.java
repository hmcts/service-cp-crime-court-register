package uk.gov.hmcts.cp.courtregister.persistence;

import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.data.Offset.offset;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
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
import uk.gov.hmcts.cp.courtregister.domain.ProcessedRequestSummary;
import uk.gov.hmcts.cp.courtregister.domain.RequestStatus;
import uk.gov.hmcts.cp.courtregister.domain.StoreUnavailableException;
import uk.gov.hmcts.cp.courtregister.support.AdjustableClock;
import uk.gov.hmcts.cp.courtregister.support.PostgresTestSupport;
import uk.gov.hmcts.cp.courtregister.support.ReportReadsDatabase;

/**
 * The three reads the exception report and the intake sweep make of the processed log.
 *
 * <p>Two of them are the report's intake kinds - the requests parked inside the window, and the
 * requests still in flight past the threshold - and the third is the sweep's first gauge. All three
 * answer a projection carrying an age, and the age is the property most of these cases are about:
 * it is computed by the database in the same statement that selects the row, so nothing here
 * subtracts a stored timestamp from a reading of a JVM clock.
 *
 * <p><strong>A database of this suite's own</strong>, migrated in full. Almost every case here is a
 * claim about what the read does <em>not</em> return - the terminal states, the rows outside the
 * window, the rows inside the cut-off - and one of them is about an empty table. None of those is
 * observable against the container the other persistence suites share, where what else is in the
 * table is whatever ran first.
 *
 * <p>Soft assertions throughout, for the reason {@code RegisterStoreIT} gives: under the red-run
 * convention these cases are written against seams that refuse, and a hard assertion would stop
 * each case at the refusal so the recorded red would be a stack trace from the arrangement rather
 * than the property under test. Each read is made inside {@code assertThatCode(...)}, which records
 * the refusal, and the case then asserts on what came back.
 */
@ExtendWith(SoftAssertionsExtension.class)
@DisplayName("the processed log's report reads")
class ProcessedRequestReportReadsIT {

    private static final String DATABASE = "courtregister_request_report_reads";

    private static final String REQUEST_TABLE = "processed_request";

    private static final String SOURCE = "RESULTS";

    private static final LocalDate HEARING_DAY = LocalDate.of(2026, 9, 14);

    /** The claim lease is irrelevant to a read, and is stated rather than defaulted. */
    private static final Duration LEASE = Duration.ofMinutes(5);

    /** The description every read made against an unwritten statement carries. */
    private static final String SEAM =
            "the report's own reads implement these statements; this is their red run";

    /** How far the clock case moves the JVM on, which is far more than a run's real duration. */
    private static final Duration A_LONG_WAY = Duration.ofHours(1);

    /** How much the two ages of one row may differ and still be the database's own reading. */
    private static final long SECONDS_OF_SLACK = 5;

    /** Enough rows that the planner has a table worth choosing an index for. */
    private static final int SEEDED_ROWS = 2000;

    private static ReportReadsDatabase database;
    private static ProcessedRequestRepository repository;

    @InjectSoftAssertions
    private SoftAssertions softly;

    @BeforeAll
    static void migrate() {
        database = ReportReadsDatabase.migrated(DATABASE);
        repository = new ProcessedRequestRepository(database.jdbcClient(), LEASE);
    }

    @BeforeEach
    void emptyTheLog() {
        database.empty(REQUEST_TABLE);
    }

    /**
     * The window read, which is bounded, and the cut-off read, which deliberately is not.
     */
    @Nested
    @DisplayName("what each read answers with")
    class WhatIsAnswered {

        @Test
        void failed_since_returns_failed_rows_inside_the_window_and_nothing_else() {
            final UUID parked = seed(RequestStatus.FAILED, "store-unavailable",
                    Duration.ofHours(4), Duration.ofHours(1));
            seed(RequestStatus.FAILED, "store-unavailable", Duration.ofHours(9),
                    Duration.ofHours(8));
            seed(RequestStatus.COMPLETED, null, Duration.ofHours(4), Duration.ofHours(1));
            seed(RequestStatus.RETRYING, "store-unavailable", Duration.ofHours(4),
                    Duration.ofHours(1));

            final List<ProcessedRequestSummary> failed = failedSince(hoursAgo(2));

            softly.assertThat(failed)
                    .as("a report is a statement about a period: a request parked before the "
                            + "window belongs to the report that already named it, and a request "
                            + "that is not parked at all is not this kind")
                    .extracting(ProcessedRequestSummary::requestId)
                    .containsExactly(parked);
            softly.assertThat(failed)
                    .as("the row as the report reads it, and the columns the entry is built from")
                    .extracting(ProcessedRequestSummary::source,
                            ProcessedRequestSummary::hearingDay,
                            ProcessedRequestSummary::status,
                            ProcessedRequestSummary::attempts,
                            ProcessedRequestSummary::failureReason)
                    .containsExactly(tuple(
                            SOURCE, HEARING_DAY, RequestStatus.FAILED, 5, "store-unavailable"));
        }

        @Test
        void non_terminal_older_than_returns_received_and_retrying_oldest_first() {
            final UUID oldest = seed(RequestStatus.RECEIVED, null, Duration.ofHours(4),
                    Duration.ofHours(4));
            final UUID next = seed(RequestStatus.RETRYING, "store-unavailable",
                    Duration.ofHours(3), Duration.ofMinutes(20));
            seed(RequestStatus.RECEIVED, null, Duration.ofMinutes(5), Duration.ofMinutes(5));

            softly.assertThat(nonTerminalOlderThan(hoursAgo(1)))
                    .as("oldest first, because the worst problem is the one read first on a "
                            + "screen and in a table, and both states are the same debt")
                    .extracting(ProcessedRequestSummary::requestId)
                    .containsExactly(oldest, next);
        }

        @Test
        void non_terminal_older_than_excludes_terminal_rows() {
            final UUID waiting = seed(RequestStatus.RECEIVED, null, Duration.ofHours(4),
                    Duration.ofHours(4));
            seed(RequestStatus.COMPLETED, null, Duration.ofHours(4), Duration.ofHours(4));
            seed(RequestStatus.FAILED, "store-unavailable", Duration.ofHours(4),
                    Duration.ofHours(4));

            softly.assertThat(nonTerminalOlderThan(hoursAgo(1)))
                    .as("the two intake predicates are disjoint by construction, which is what "
                            + "makes a request that was late and has since failed appear once")
                    .extracting(ProcessedRequestSummary::requestId)
                    .containsExactly(waiting);
        }

        @Test
        void oldest_non_terminal_answers_empty_on_an_empty_table() {
            softly.assertThat(oldestNonTerminal())
                    .as("a service with nothing unfinished is the ordinary case, and the gauge "
                            + "reads zero from an absence rather than from a row that is not there")
                    .isEmpty();
        }
    }

    /**
     * Where the ages come from, and what they are not derived from.
     */
    @Nested
    @DisplayName("the age of a row")
    class TheAge {

        @Test
        void age_seconds_is_computed_by_the_database_not_the_jvm() {
            seed(RequestStatus.RECEIVED, null, Duration.ofMinutes(10), Duration.ofMinutes(10));
            final AdjustableClock jvm = AdjustableClock.startingAt(Instant.now());

            final List<ProcessedRequestSummary> before = nonTerminalOlderThan(hoursAgo(-1));
            final long jvmBefore = jvmAge(before, jvm);
            jvm.advance(A_LONG_WAY);
            final List<ProcessedRequestSummary> after = nonTerminalOlderThan(hoursAgo(-1));
            final long jvmAfter = jvmAge(after, jvm);

            softly.assertThat(jvmAfter - jvmBefore)
                    .as("the counterfactual: an age derived in this JVM from the stored timestamp "
                            + "moves by exactly as much as the JVM's clock was moved")
                    .isEqualTo(A_LONG_WAY.toSeconds());
            softly.assertThat(ageOf(after))
                    .as("the database's own reading did not move, which is why two pods reading "
                            + "one row agree about how old it is")
                    .isCloseTo(ageOf(before), offset(SECONDS_OF_SLACK));
            softly.assertThat(ageOf(before))
                    .as("and it is the real age of the row, measured from when it arrived")
                    .isCloseTo(Duration.ofMinutes(10).toSeconds(),
                            offset(SECONDS_OF_SLACK));
        }
    }

    /**
     * What an unreachable store answers with, and what the planner does with the two scheduled
     * reads.
     */
    @Nested
    @DisplayName("how the reads behave as statements")
    class AsStatements {

        @Test
        void every_read_goes_through_store_outage_translating() {
            PostgresTestSupport.refuseConnectionsTo(DATABASE);
            try {
                softly.assertThatThrownBy(() -> repository.failedSince(hoursAgo(2)))
                        .as("an unreachable store is the intake half's own signal, and a "
                                + "org.springframework.dao type reaching the core is Principle V")
                        .isInstanceOf(StoreUnavailableException.class);
                softly.assertThatThrownBy(() -> repository.nonTerminalOlderThan(hoursAgo(2)))
                        .as("the same, for the read the sweep and the report share a predicate on")
                        .isInstanceOf(StoreUnavailableException.class);
                softly.assertThatThrownBy(repository::oldestNonTerminal)
                        .as("and for the sweep's own, which runs every refresh interval for the "
                                + "life of every pod and so meets an outage soonest")
                        .isInstanceOf(StoreUnavailableException.class);
            } finally {
                PostgresTestSupport.allowConnectionsTo(DATABASE);
            }
        }

        @Test
        void both_scheduled_reads_use_the_v4_indexes() throws SQLException {
            seedManyRows();

            softly.assertThat(planFor(hoursAgo(24), () -> repository.failedSince(hoursAgo(24))))
                    .as("the window read is served by the total index on (status, updated_at), "
                            + "which is the whole reason V4 adds it")
                    .contains("idx_request_status_updated");
            softly.assertThat(planFor(hoursAgo(1),
                            () -> repository.nonTerminalOlderThan(hoursAgo(1))))
                    .as("and the in-flight read by the partial one, whose predicate is spelled "
                            + "exactly as this statement spells it so the implication is trivial")
                    .contains("idx_request_non_terminal_created");
        }
    }

    // --- the reads, made so that a seam's refusal is recorded rather than thrown ----------------

    private List<ProcessedRequestSummary> failedSince(final Instant since) {
        return answered(() -> repository.failedSince(since));
    }

    private List<ProcessedRequestSummary> nonTerminalOlderThan(final Instant createdBefore) {
        return answered(() -> repository.nonTerminalOlderThan(createdBefore));
    }

    private Optional<ProcessedRequestSummary> oldestNonTerminal() {
        final AtomicReference<Optional<ProcessedRequestSummary>> answer =
                new AtomicReference<>(Optional.empty());
        softly.assertThatCode(() -> answer.set(repository.oldestNonTerminal()))
                .as(SEAM)
                .doesNotThrowAnyException();
        return answer.get();
    }

    private List<ProcessedRequestSummary> answered(
            final Supplier<List<ProcessedRequestSummary>> read) {
        final AtomicReference<List<ProcessedRequestSummary>> answer =
                new AtomicReference<>(List.of());
        softly.assertThatCode(() -> answer.set(read.get())).as(SEAM).doesNotThrowAnyException();
        return answer.get();
    }

    // --- the plan the database makes of the statement the repository really ran -----------------

    /**
     * Runs one read, takes the statement it prepared, and asks the database to plan that statement.
     *
     * <p>The statement is taken from the driver rather than spelled again here, because a copy in a
     * test proves what the copy can use and says nothing about what the repository runs.
     *
     * <p>Two arrangements, and both matter. {@code ANALYZE} first: a table the planner has no
     * statistics for is a table it will sequentially scan whatever indexes exist. Then
     * {@code enable_seqscan = off} for this session, so what is asserted is "this query <em>can</em>
     * use this index" - the claim V4 makes - rather than "today's row count happened to make it
     * cheapest", which is a test that goes green on a small table and red on the production one.
     */
    private String planFor(final Instant parameter, final Runnable read) throws SQLException {
        database.forgetStatements();
        softly.assertThatCode(read::run).as(SEAM).doesNotThrowAnyException();
        final List<String> executed = database.statements();
        softly.assertThat(executed)
                .as("one read is one statement, and the plan asked for below is that statement's")
                .hasSize(1);
        final String sql = executed.isEmpty() ? "SELECT 1" : executed.get(0);

        try (Connection connection = database.openConnection();
             Statement session = connection.createStatement()) {
            session.execute("ANALYZE " + REQUEST_TABLE);
            session.execute("SET enable_seqscan = off");
            return plan(connection, sql, parameter);
        }
    }

    private static String plan(final Connection connection, final String sql,
            final Instant parameter) throws SQLException {
        final StringBuilder lines = new StringBuilder();
        try (PreparedStatement explain = connection.prepareStatement("EXPLAIN " + sql)) {
            for (int marker = 1; marker <= placeholders(sql); marker++) {
                explain.setObject(marker, OffsetDateTime.ofInstant(parameter, ZoneOffset.UTC));
            }
            try (ResultSet rows = explain.executeQuery()) {
                while (rows.next()) {
                    lines.append(rows.getString(1)).append('\n');
                }
            }
        }
        return lines.toString();
    }

    private static int placeholders(final String sql) {
        return (int) sql.chars().filter(character -> character == '?').count();
    }

    // --- seeding ------------------------------------------------------------------------------

    /**
     * One request row, aged by the database from its own {@code now()}.
     *
     * <p>{@code attempts} is five on every row, which is the delivery budget: a report that carried
     * the tally would be wrong about it, and a fixed number makes the assertion about the column
     * rather than about the fixture.
     */
    private UUID seed(final RequestStatus status, final String failureReason,
            final Duration createdAgo, final Duration updatedAgo) {
        final UUID requestId = UUID.randomUUID();
        database.jdbcClient()
                .sql("""
                        INSERT INTO processed_request (
                            source, request_id, hearing_id, hearing_day, shared_time, event_type,
                            request_fingerprint, status, attempts, failure_reason,
                            exhausted_message_id, created_at, updated_at)
                        VALUES (
                            :source, :requestId, :hearingId, :hearingDay, now(), 'Hearing_Resulted',
                            'fingerprint', :status, 5, :failureReason, :exhaustedMessageId,
                            now() - CAST(:createdAgo AS interval),
                            now() - CAST(:updatedAgo AS interval))
                        """)
                .param("source", SOURCE)
                .param("requestId", requestId)
                .param("hearingId", UUID.randomUUID())
                .param("hearingDay", HEARING_DAY)
                .param("status", status.name())
                .param("failureReason", failureReason, Types.VARCHAR)
                .param("exhaustedMessageId",
                        status == RequestStatus.FAILED ? SOURCE + ':' + requestId : null,
                        Types.VARCHAR)
                .param("createdAgo", createdAgo.toString())
                .param("updatedAgo", updatedAgo.toString())
                .update();
        return requestId;
    }

    /**
     * A table worth planning over: two thousand rows a week of deliveries could leave behind.
     *
     * <p>Written in one statement rather than two thousand, because what the case needs is a table
     * the planner has statistics about and not two thousand round trips.
     */
    private void seedManyRows() {
        database.jdbcClient()
                .sql("""
                        INSERT INTO processed_request (
                            source, request_id, hearing_id, hearing_day, shared_time, event_type,
                            request_fingerprint, status, attempts, exhausted_message_id,
                            created_at, updated_at)
                        SELECT :source, gen_random_uuid(), gen_random_uuid(), :hearingDay, now(),
                               'Hearing_Resulted', 'fingerprint',
                               CASE WHEN g % 40 = 0 THEN 'FAILED'
                                    WHEN g % 41 = 0 THEN 'RECEIVED'
                                    ELSE 'COMPLETED' END,
                               1,
                               CASE WHEN g % 40 = 0 THEN 'msg-' || g ELSE NULL END,
                               now() - (g || ' minutes')::interval,
                               now() - (g || ' minutes')::interval
                          FROM generate_series(1, :rows) AS g
                        """)
                .param("source", SOURCE)
                .param("hearingDay", HEARING_DAY)
                .param("rows", SEEDED_ROWS)
                .update();
    }

    // --- reading the answers ------------------------------------------------------------------

    private long ageOf(final List<ProcessedRequestSummary> answered) {
        return answered.isEmpty() ? -1 : answered.get(0).ageSeconds();
    }

    /**
     * The age this JVM would compute for the same row, which is the comparison V1 forbids.
     *
     * <p>Present so the case can show the difference rather than assert an absence: the database's
     * reading stays where it was while this one moves by exactly as far as the clock was moved.
     */
    private long jvmAge(final List<ProcessedRequestSummary> answered, final AdjustableClock jvm) {
        return answered.isEmpty()
                ? 0
                : Duration.between(answered.get(0).createdAt(), jvm.instant()).toSeconds();
    }

    private static Instant hoursAgo(final long hours) {
        return Instant.now().minus(Duration.ofHours(hours));
    }
}
