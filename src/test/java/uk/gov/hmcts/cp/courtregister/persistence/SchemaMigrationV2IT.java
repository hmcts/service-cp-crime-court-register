package uk.gov.hmcts.cp.courtregister.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.courtregister.domain.BatchFailureReason;
import uk.gov.hmcts.cp.courtregister.domain.BatchStatus;
import uk.gov.hmcts.cp.courtregister.domain.CompletedBy;
import uk.gov.hmcts.cp.courtregister.domain.NotificationStatus;
import uk.gov.hmcts.cp.courtregister.domain.RecordedFlagState;
import uk.gov.hmcts.cp.courtregister.support.PostgresTestSupport;

/**
 * Pins the V2 register-store schema against {@code data-model.md}.
 *
 * <p>V2 is where {@code processed_output} stops being a record of a POST and becomes the register
 * store itself: it gains the document it recorded, the hearing facts progression kept in its own
 * table, the batch it was assembled into, the supersession pair that replaces progression's
 * read-side {@code max(register_time)} sweep, and the flag state it was recorded under. Three
 * tables arrive beside it - {@code register_batch}, {@code register_notification} and
 * {@code shedlock}. Every fact the data model states about all four is asserted here, structurally
 * from what Postgres reports and, for the rules a row can break, behaviourally by offering the
 * database a row that breaks it and requiring a refusal.
 *
 * <p>The bounded vocabularies are asserted <strong>against the domain enumerations rather than
 * against copies of them</strong>. {@code BatchStatus}, {@code BatchFailureReason},
 * {@code NotificationStatus}, {@code RecordedFlagState} and {@code CompletedBy} each
 * say in their own javadoc that their constant names are the values the check constraint
 * enumerates; reading them here is what makes that true, so a constant added in Java without the
 * matching migration fails this suite rather than failing at the first insert in production.
 *
 * <p><strong>The exhaustive column list for {@code processed_output} stays in
 * {@code SchemaMigrationIT}</strong>, which asserts it with {@code containsOnlyKeys} over the whole
 * of {@code db/migration}. This suite asserts that V2 <em>adds</em> its ten columns and that none
 * of V1's thirteen were taken away; the "and nothing else" half remains a single pin in one place,
 * and widens there when V2 lands.
 *
 * <p><strong>Note for the migration author (T012).</strong> A V1 database may already hold rows
 * written in {@code progression-post} mode, which carry no document and no register instant, and
 * during a rolling deployment a pod on the previous release goes on writing that shape after this
 * migration has run - Flyway is deferred to the new pod's startup, so the old pod is still live and
 * still inserting. The four register columns are therefore left NULLABLE and the new shape is
 * required by {@code processed_output_recorded_shape_chk}, which binds only the statuses the
 * recorder writes. {@code recorded_flag_state} is the one exception: it keeps NOT NULL with a
 * DEFAULT of UNKNOWN, which is safe for an old insert precisely because UNKNOWN is what the backfill
 * chooses for a row that never read the flag. This suite asserts both halves - the nullability the
 * rollout needs and the check that makes it an invariant anyway.
 */
class SchemaMigrationV2IT {

    private static final String REQUEST_TABLE = "processed_request";
    private static final String OUTPUT_TABLE = "processed_output";
    private static final String BATCH_TABLE = "register_batch";
    private static final String NOTIFICATION_TABLE = "register_notification";
    private static final String LOCK_TABLE = "shedlock";

    private static final String TIMESTAMPTZ = "timestamp with time zone";

    @BeforeAll
    static void migrate() {
        PostgresTestSupport.applyFlyway();
    }

    // --- helpers ---------------------------------------------------------------------------

    private record Column(String dataType, boolean nullable, String columnDefault) {
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(
                PostgresTestSupport.jdbcUrl(),
                PostgresTestSupport.username(),
                PostgresTestSupport.password());
    }

    private static Column columnOf(final ResultSet rows) throws SQLException {
        return new Column(
                rows.getString("data_type"),
                "YES".equals(rows.getString("is_nullable")),
                rows.getString("column_default"));
    }

    private static Map<String, Column> columnsOf(final String table) throws SQLException {
        final Map<String, Column> columns = new LinkedHashMap<>();
        final String sql = """
                SELECT column_name, data_type, is_nullable, column_default
                  FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = ?
                 ORDER BY ordinal_position
                """;
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    columns.put(rows.getString("column_name"), columnOf(rows));
                }
            }
        }
        return columns;
    }

    /**
     * The declared length of a character column, empty where there is no such length-bounded column.
     */
    private static Optional<Integer> declaredLengthOf(final String table, final String column)
            throws SQLException {
        final List<Integer> lengths = new ArrayList<>();
        final String sql = """
                SELECT character_maximum_length
                  FROM information_schema.columns
                 WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
                """;
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    final int declared = rows.getInt(1);
                    if (!rows.wasNull()) {
                        lengths.add(declared);
                    }
                }
            }
        }
        return lengths.stream().findFirst();
    }

    private static Map<String, String> constraintsOf(final String table) throws SQLException {
        final Map<String, String> constraints = new LinkedHashMap<>();
        final String sql = """
                SELECT c.conname, pg_get_constraintdef(c.oid) AS definition
                  FROM pg_constraint c
                  JOIN pg_class t ON t.oid = c.conrelid
                  JOIN pg_namespace n ON n.oid = t.relnamespace
                 WHERE n.nspname = 'public' AND t.relname = ?
                """;
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    constraints.put(rows.getString("conname"), rows.getString("definition"));
                }
            }
        }
        return constraints;
    }

    private static Map<String, String> indexesOf(final String table) throws SQLException {
        final Map<String, String> indexes = new LinkedHashMap<>();
        final String sql = """
                SELECT indexname, indexdef FROM pg_indexes
                 WHERE schemaname = 'public' AND tablename = ?
                """;
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    indexes.put(rows.getString("indexname"), rows.getString("indexdef"));
                }
            }
        }
        return indexes;
    }

    private static List<String> tableNames() throws SQLException {
        final List<String> tables = new ArrayList<>();
        final String sql = """
                SELECT table_name FROM information_schema.tables
                 WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
                """;
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                tables.add(rows.getString("table_name"));
            }
        }
        return tables;
    }

    /**
     * The constant names of a bounded vocabulary, as the check constraint must enumerate them.
     */
    private static String[] vocabularyOf(final Class<? extends Enum<?>> bounded) {
        return Arrays.stream(bounded.getEnumConstants()).map(Enum::name).toArray(String[]::new);
    }

    /**
     * Runs one statement inside a transaction that is always rolled back, so behavioural probes
     * leave no rows behind for the guard suites that share this container.
     */
    private static void inRolledBackTransaction(final String... statements) throws SQLException {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                for (final String sql : statements) {
                    statement.executeUpdate(sql);
                }
            } finally {
                connection.rollback();
            }
        }
    }

    /**
     * The smallest valid {@code processed_request} row, for probes that need a parent to exist.
     */
    private static String insertValidRequest(final UUID requestId) {
        return "INSERT INTO " + REQUEST_TABLE + " (source, request_id, hearing_id, hearing_day, "
                + "shared_time, event_type, request_fingerprint, status, attempts) VALUES ("
                + "'RESULTS', '" + requestId + "', '" + UUID.randomUUID() + "', DATE '2026-08-20', "
                + "TIMESTAMPTZ '2026-08-20T09:00:00Z', 'Hearing_Resulted', 'fingerprint', "
                + "'RECEIVED', 1)";
    }

    /**
     * The smallest valid V2 {@code processed_output} row: everything the data model marks NOT NULL
     * after the migration, and nothing it marks optional.
     */
    private static String insertOutput(final UUID requestId, final String status) {
        return insertOutput(requestId, status, "ON", "", "");
    }

    private static String insertOutput(final UUID requestId, final String status,
                                       final String extraColumns, final String extraValues) {
        return insertOutput(requestId, status, "ON", extraColumns, extraValues);
    }

    private static String insertOutput(final UUID requestId, final String status,
                                       final String flagState, final String extraColumns,
                                       final String extraValues) {
        return "INSERT INTO " + OUTPUT_TABLE
                + " (output_id, source, request_id, court_centre_id, register_date, file_name, "
                + "status, document, hearing_id, hearing_date, register_time, recorded_flag_state"
                + extraColumns + ") VALUES ('"
                + UUID.randomUUID() + "', 'RESULTS', '" + requestId + "', '" + UUID.randomUUID()
                + "', DATE '2026-08-20', 'courtregister_2026-08-20.json', '" + status + "', "
                + "'{\"documentType\": \"CourtRegister\"}'::jsonb, '" + UUID.randomUUID() + "', "
                + "TIMESTAMPTZ '2026-08-20T09:00:00Z', TIMESTAMPTZ '2026-08-20T17:00:00Z', '"
                + flagState + "'" + extraValues + ")";
    }

    /**
     * The row the <em>previous</em> release writes: V1's columns and not one of V2's ten.
     *
     * <p>Word for word the shape {@code ProcessedOutputRepository} carried before this increment,
     * because that is what a pod that has not been replaced yet is still executing while V2 is
     * already applied.
     */
    private static String insertV1ShapedOutput(final UUID requestId) {
        return "INSERT INTO " + OUTPUT_TABLE
                + " (output_id, source, request_id, court_centre_id, register_date, file_name, "
                + "status, request_digest, response_code) VALUES ('"
                + UUID.randomUUID() + "', 'RESULTS', '" + requestId + "', '" + UUID.randomUUID()
                + "', DATE '2026-08-20', 'courtregister_2026-08-20.json', 'POSTED', "
                + "'fingerprint', 202)";
    }

    /**
     * A RECORDED row that names every register column except the register itself.
     */
    private static String insertOutputWithoutDocument(final UUID requestId) {
        return "INSERT INTO " + OUTPUT_TABLE
                + " (output_id, source, request_id, court_centre_id, register_date, file_name, "
                + "status, hearing_id, hearing_date, register_time, recorded_flag_state) VALUES ('"
                + UUID.randomUUID() + "', 'RESULTS', '" + requestId + "', '" + UUID.randomUUID()
                + "', DATE '2026-08-20', 'courtregister_2026-08-20.json', 'RECORDED', '"
                + UUID.randomUUID() + "', TIMESTAMPTZ '2026-08-20T09:00:00Z', "
                + "TIMESTAMPTZ '2026-08-20T17:00:00Z', 'ON')";
    }

    /**
     * The smallest valid {@code register_batch} row for the given key and state.
     */
    private static String insertBatch(final UUID batchId, final UUID courtCentreId,
                                      final String status) {
        return "INSERT INTO " + BATCH_TABLE + " (batch_id, court_centre_id, register_date, "
                + "file_name, status, system_generated) VALUES ('" + batchId + "', '"
                + courtCentreId + "', DATE '2026-08-20', 'courtregister_2026-08-20.json', '"
                + status + "', true)";
    }

    /**
     * The smallest valid {@code register_notification} row for the given batch and recipient.
     */
    private static String insertNotification(final UUID batchId, final String emailAddress) {
        return "INSERT INTO " + NOTIFICATION_TABLE + " (notification_id, batch_id, email_address, "
                + "template_name, template_id, status) VALUES ('" + UUID.randomUUID() + "', '"
                + batchId + "', '" + emailAddress + "', 'cr_standard', '" + UUID.randomUUID()
                + "', 'PENDING')";
    }

    private static String insertLock(final String name) {
        return "INSERT INTO " + LOCK_TABLE + " (name, lock_until, locked_at, locked_by) VALUES ('"
                + name + "', now() + interval '5 minutes', now(), 'runner-1')";
    }

    // --- tables ----------------------------------------------------------------------------

    @Test
    @DisplayName("V2 creates the batch, notification and lock tables")
    void migration_should_create_the_three_new_tables() throws SQLException {
        assertThat(tableNames()).contains(BATCH_TABLE, NOTIFICATION_TABLE, LOCK_TABLE);
    }

    @Nested
    @DisplayName("processed_output becomes the register store")
    class ProcessedOutput {

        @Test
        void v1_columns_should_all_survive_the_widening() throws SQLException {
            // V2 adds; it never replaces. The processed log's own evidence - what was posted, what
            // came back, which anomalies were guarded - is the 001 differential audit's input and
            // is still what `progression-post` mode writes.
            assertThat(columnsOf(OUTPUT_TABLE)).containsKeys(
                    "output_id", "source", "request_id", "court_centre_id", "court_centre_ou_code",
                    "register_date", "file_name", "status", "response_code", "request_digest",
                    "anomaly_summary", "created_at", "updated_at");
        }

        @Test
        void document_should_be_nullable_jsonb_the_recorded_shape_check_requires()
                throws SQLException {
            // The validated CourtRegisterDocument itself, not a reference to one: on a recorded row
            // this is the only place the register exists, and `request_digest` is its SHA-256. The
            // column is nullable so that a pod on the previous release can still insert the old
            // shape while this migration is already applied; the shape check is what requires it of
            // every row the recorder writes.
            assertThat(columnsOf(OUTPUT_TABLE).get("document"))
                    .isEqualTo(new Column("jsonb", true, null));
        }

        @Test
        void hearing_facts_should_be_nullable_columns_taken_from_the_document() throws SQLException {
            final Map<String, Column> columns = columnsOf(OUTPUT_TABLE);
            assertThat(columns.get("hearing_id")).isEqualTo(new Column("uuid", true, null));
            assertThat(columns.get("hearing_date")).isEqualTo(new Column(TIMESTAMPTZ, true, null));
        }

        @Test
        void register_time_should_be_the_nullable_instant_beside_the_london_register_date()
                throws SQLException {
            // progression's `register_time`. `register_date` stays the London date part and stays
            // the batch key; the instant is what supersession orders rows by.
            assertThat(columnsOf(OUTPUT_TABLE).get("register_time"))
                    .isEqualTo(new Column(TIMESTAMPTZ, true, null));
        }

        @Test
        void court_house_and_defendant_type_should_be_nullable_descriptive_text()
                throws SQLException {
            // court_house because the hearing venue may carry none; defendant_type because a
            // hearing with no court application resolves to no type at all (FR-002).
            final Map<String, Column> columns = columnsOf(OUTPUT_TABLE);
            assertThat(columns.get("court_house")).isEqualTo(new Column("text", true, null));
            assertThat(columns.get("defendant_type")).isEqualTo(new Column("text", true, null));
        }

        @Test
        void batch_id_should_be_a_nullable_uuid_until_the_row_is_assembled() throws SQLException {
            assertThat(columnsOf(OUTPUT_TABLE).get("batch_id"))
                    .isEqualTo(new Column("uuid", true, null));
        }

        @Test
        void supersession_pair_should_be_nullable_and_carry_no_default() throws SQLException {
            // Written inside the recording transaction that superseded the row (research §8), so
            // both are absent on a row that is still the active one for its key.
            final Map<String, Column> columns = columnsOf(OUTPUT_TABLE);
            assertThat(columns.get("superseded_at")).isEqualTo(new Column(TIMESTAMPTZ, true, null));
            assertThat(columns.get("superseded_by")).isEqualTo(new Column("uuid", true, null));
        }

        @Test
        void recorded_flag_state_should_be_non_null_defaulting_to_the_backfills_own_value()
                throws SQLException {
            // The one added column that keeps NOT NULL, because the only value a default could
            // choose is the value the backfill already chooses for a row that never read the flag.
            // A pod on the previous release inserts without naming the column and inherits UNKNOWN,
            // which is true of it; the recorder always states its own answer (research §12).
            assertThat(columnsOf(OUTPUT_TABLE).get("recorded_flag_state"))
                    .isEqualTo(new Column("text", false, "'UNKNOWN'::text"));
        }

        @Test
        void recorded_shape_check_should_require_the_register_columns_of_a_recorded_row()
                throws SQLException {
            // The invariant the four nullable columns lost when they stopped being NOT NULL, put
            // back where it binds only the statuses this service's recorder writes. PENDING,
            // POSTED and FAILED are outside it, which is what leaves the previous release's
            // progression-post insert legal during a rolling deployment.
            assertThat(constraintsOf(OUTPUT_TABLE).get("processed_output_recorded_shape_chk"))
                    .isNotNull()
                    .contains("RECORDED", "GENERATED", "NOTIFIED", "SUPERSEDED")
                    .contains("document IS NOT NULL", "hearing_id IS NOT NULL",
                            "hearing_date IS NOT NULL", "register_time IS NOT NULL");
        }

        @Test
        void a_pre_002_pod_should_still_be_able_to_insert_the_v1_shaped_posted_row() {
            // The rollout case. Flyway runs at the new pod's startup while the old pod is still
            // serving the queue, so for the length of the deployment the previous release goes on
            // writing this exact statement against a schema that has already moved. If V2 made the
            // register columns NOT NULL, every one of those inserts would fail and every register
            // in flight would be lost for as long as the rollout took.
            final UUID requestId = UUID.randomUUID();
            assertThatCode(() -> inRolledBackTransaction(
                    insertValidRequest(requestId),
                    insertV1ShapedOutput(requestId)))
                    .doesNotThrowAnyException();
        }

        @Test
        void a_recorded_row_without_its_document_should_be_refused_by_the_shape_check() {
            // And the other half: nullable columns are not a licence to record a register that is
            // not there. A row this service's own recorder writes carries all four or it is not a
            // row at all.
            final UUID requestId = UUID.randomUUID();
            assertThatThrownBy(() -> inRolledBackTransaction(
                    insertValidRequest(requestId),
                    insertOutputWithoutDocument(requestId)))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("processed_output_recorded_shape_chk");
        }

        @Test
        void status_check_should_name_the_register_store_states_and_keep_the_posting_ones()
                throws SQLException {
            // RECORDED -> GENERATED -> NOTIFIED with SUPERSEDED and FAILED beside them; PENDING and
            // POSTED stay valid because `courtregister.output=progression-post` still writes them.
            assertThat(constraintsOf(OUTPUT_TABLE).get("processed_output_status_chk"))
                    .isNotNull()
                    .contains("RECORDED", "GENERATED", "NOTIFIED", "SUPERSEDED", "FAILED",
                            "PENDING", "POSTED");
        }

        @Test
        void status_check_should_accept_a_recorded_row() {
            final UUID requestId = UUID.randomUUID();
            assertThatCode(() -> inRolledBackTransaction(
                    insertValidRequest(requestId),
                    insertOutput(requestId, "RECORDED")))
                    .doesNotThrowAnyException();
        }

        @Test
        void status_check_should_reject_a_state_outside_the_widened_enumeration() {
            final UUID requestId = UUID.randomUUID();
            assertThatThrownBy(() -> inRolledBackTransaction(
                    insertValidRequest(requestId),
                    insertOutput(requestId, "SUBMITTED")))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("processed_output_status_chk");
        }

        @Test
        void flag_state_check_should_name_exactly_the_recorded_flag_states() throws SQLException {
            assertThat(constraintsOf(OUTPUT_TABLE).get("processed_output_flag_state_chk"))
                    .isNotNull()
                    .contains(vocabularyOf(RecordedFlagState.class));
        }

        @Test
        void flag_state_check_should_reject_a_state_outside_the_enumeration() {
            final UUID requestId = UUID.randomUUID();
            assertThatThrownBy(() -> inRolledBackTransaction(
                    insertValidRequest(requestId),
                    insertOutput(requestId, "RECORDED", "UNREADABLE", "", "")))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("processed_output_flag_state_chk");
        }

        @Test
        void batch_foreign_key_should_point_at_the_batch_table() throws SQLException {
            assertThat(constraintsOf(OUTPUT_TABLE).get("processed_output_batch_fk"))
                    .isNotNull()
                    .contains("FOREIGN KEY (batch_id) REFERENCES register_batch(batch_id)");
        }

        @Test
        void a_row_stamped_with_a_batch_that_does_not_exist_should_be_refused() {
            // The batch is minted and persisted before anything is asked of another system, so a
            // row can only ever be stamped with a batch this service already recorded.
            final UUID requestId = UUID.randomUUID();
            assertThatThrownBy(() -> inRolledBackTransaction(
                    insertValidRequest(requestId),
                    insertOutput(requestId, "RECORDED", ", batch_id",
                            ", '" + UUID.randomUUID() + "'")))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("processed_output_batch_fk");
        }

        @Test
        void superseded_by_foreign_key_should_point_at_another_output_row() throws SQLException {
            assertThat(constraintsOf(OUTPUT_TABLE).get("processed_output_superseded_by_fk"))
                    .isNotNull()
                    .contains("FOREIGN KEY (superseded_by) REFERENCES processed_output(output_id)");
        }

        @Test
        void a_row_superseded_by_an_output_that_does_not_exist_should_be_refused() {
            final UUID requestId = UUID.randomUUID();
            assertThatThrownBy(() -> inRolledBackTransaction(
                    insertValidRequest(requestId),
                    insertOutput(requestId, "SUPERSEDED", ", superseded_by, superseded_at",
                            ", '" + UUID.randomUUID() + "', now()")))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("processed_output_superseded_by_fk");
        }

        @Test
        void active_unbatched_index_should_be_partial_on_exactly_the_nightly_sweep_predicate()
                throws SQLException {
            // The nightly job's only read. Partial rather than plain: the sweep is interested in a
            // vanishing fraction of the table, and defect P8 is what a full scan of an unindexed
            // register table costs.
            assertThat(indexesOf(OUTPUT_TABLE).get("idx_output_active_unbatched"))
                    .isNotNull()
                    .contains("court_centre_id, register_date")
                    .contains("'RECORDED'")
                    .contains("superseded_at IS NULL")
                    .contains("batch_id IS NULL");
        }

        @Test
        void hearing_index_should_cover_the_per_hearing_support_read() throws SQLException {
            assertThat(indexesOf(OUTPUT_TABLE).get("idx_output_hearing"))
                    .isNotNull()
                    .contains("(hearing_id)");
        }
    }

    @Nested
    @DisplayName("register_batch")
    class RegisterBatchTable {

        @Test
        void columns_should_be_exactly_the_documented_set() throws SQLException {
            // No created_at/updated_at: a batch's timeline is the five stamps below, each of which
            // names the event it records, and a generic "updated" would say less than any of them.
            assertThat(columnsOf(BATCH_TABLE)).containsOnlyKeys(
                    "batch_id", "court_centre_id", "court_centre_ou_code", "court_house",
                    "register_date", "file_name", "payload_file_id", "document_file_id", "status",
                    "failure_reason", "sdg_reason", "system_generated", "completed_by",
                    "assembled_at", "requested_at", "generated_at", "notified_at", "failed_at",
                    "attempts");
        }

        @Test
        void batch_id_should_be_a_non_null_uuid_with_no_database_default() throws SQLException {
            // Application-minted at assembly and sent to systemdocgenerator as sourceCorrelationId,
            // so the code that writes the row already holds the identifier it will correlate on.
            assertThat(columnsOf(BATCH_TABLE).get("batch_id"))
                    .isEqualTo(new Column("uuid", false, null));
        }

        @Test
        void key_columns_should_have_the_documented_types_and_nullability() throws SQLException {
            final Map<String, Column> columns = columnsOf(BATCH_TABLE);
            assertThat(columns.get("court_centre_id")).isEqualTo(new Column("uuid", false, null));
            assertThat(columns.get("register_date")).isEqualTo(new Column("date", false, null));
            assertThat(columns.get("file_name")).isEqualTo(new Column("text", false, null));
            assertThat(columns.get("status")).isEqualTo(new Column("text", false, null));
        }

        @Test
        void descriptive_columns_should_be_nullable_because_the_records_may_carry_none()
                throws SQLException {
            final Map<String, Column> columns = columnsOf(BATCH_TABLE);
            assertThat(columns.get("court_centre_ou_code")).isEqualTo(new Column("text", true, null));
            assertThat(columns.get("court_house")).isEqualTo(new Column("text", true, null));
        }

        @Test
        void file_identifiers_should_be_nullable_uuids_written_as_the_batch_progresses()
                throws SQLException {
            // payload_file_id is minted before the file-service insert; document_file_id arrives
            // with the public event or the query API and is null until the document exists.
            final Map<String, Column> columns = columnsOf(BATCH_TABLE);
            assertThat(columns.get("payload_file_id")).isEqualTo(new Column("uuid", true, null));
            assertThat(columns.get("document_file_id")).isEqualTo(new Column("uuid", true, null));
        }

        @Test
        void failure_columns_should_be_nullable_and_kept_apart() throws SQLException {
            // failure_reason is this service's bounded code; sdg_reason is systemdocgenerator's own
            // words about a document whose every defendant is a child, and is never logged at INFO.
            final Map<String, Column> columns = columnsOf(BATCH_TABLE);
            assertThat(columns.get("failure_reason")).isEqualTo(new Column("text", true, null));
            assertThat(columns.get("sdg_reason"))
                    .isEqualTo(new Column("character varying", true, null));
        }

        @Test
        void sdg_reason_should_be_bounded_at_the_length_the_data_model_states() throws SQLException {
            // Bounded because it is the one column here holding somebody else's free text, and an
            // unbounded one is a row of any size at all written from a message this service does
            // not author. The domain truncates to the same bound before the write.
            assertThat(declaredLengthOf(BATCH_TABLE, "sdg_reason")).hasValue(512);
        }

        @Test
        void system_generated_should_be_a_non_null_boolean() throws SQLException {
            // progression's own flag: true from the nightly schedule, false from the operations CLI.
            assertThat(columnsOf(BATCH_TABLE).get("system_generated"))
                    .isEqualTo(new Column("boolean", false, null));
        }

        @Test
        void completed_by_should_be_nullable_text_while_the_batch_is_pending() throws SQLException {
            assertThat(columnsOf(BATCH_TABLE).get("completed_by"))
                    .isEqualTo(new Column("text", true, null));
        }

        @Test
        void lifecycle_stamps_should_be_nullable_timestamps() throws SQLException {
            final Map<String, Column> columns = columnsOf(BATCH_TABLE);
            assertThat(columns.get("assembled_at")).isEqualTo(new Column(TIMESTAMPTZ, true, null));
            assertThat(columns.get("requested_at")).isEqualTo(new Column(TIMESTAMPTZ, true, null));
            assertThat(columns.get("generated_at")).isEqualTo(new Column(TIMESTAMPTZ, true, null));
            assertThat(columns.get("notified_at")).isEqualTo(new Column(TIMESTAMPTZ, true, null));
            assertThat(columns.get("failed_at")).isEqualTo(new Column(TIMESTAMPTZ, true, null));
        }

        @Test
        void attempts_should_be_a_non_null_integer_defaulting_to_zero() throws SQLException {
            assertThat(columnsOf(BATCH_TABLE).get("attempts"))
                    .isEqualTo(new Column("integer", false, "0"));
        }

        @Test
        void attempts_check_should_require_a_non_negative_count() throws SQLException {
            assertThat(constraintsOf(BATCH_TABLE).get("register_batch_attempts_chk"))
                    .isNotNull()
                    .contains("attempts >= 0");
        }

        @Test
        void primary_key_should_be_the_single_batch_id_column() throws SQLException {
            assertThat(constraintsOf(BATCH_TABLE).values())
                    .anySatisfy(definition ->
                            assertThat(definition).isEqualTo("PRIMARY KEY (batch_id)"));
        }

        @Test
        void status_check_should_name_exactly_the_batch_states() throws SQLException {
            assertThat(constraintsOf(BATCH_TABLE).get("register_batch_status_chk"))
                    .isNotNull()
                    .contains(vocabularyOf(BatchStatus.class));
        }

        @Test
        void status_check_should_reject_a_state_outside_the_enumeration() {
            assertThatThrownBy(() -> inRolledBackTransaction(
                    insertBatch(UUID.randomUUID(), UUID.randomUUID(), "SENT")))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("register_batch_status_chk");
        }

        @Test
        void failure_reason_check_should_name_exactly_the_bounded_reasons() throws SQLException {
            assertThat(constraintsOf(BATCH_TABLE).get("register_batch_failure_reason_chk"))
                    .isNotNull()
                    .contains(vocabularyOf(BatchFailureReason.class));
        }

        @Test
        void failure_reason_check_should_reject_a_reason_outside_the_enumeration() {
            // Systemdocgenerator's own text belongs in sdg_reason. This column is what the batches
            // counter labels its outcome with, so an unbounded value would be an unbounded series.
            assertThatThrownBy(() -> inRolledBackTransaction(
                    "INSERT INTO " + BATCH_TABLE + " (batch_id, court_centre_id, register_date, "
                            + "file_name, status, system_generated, failure_reason) VALUES ('"
                            + UUID.randomUUID() + "', '" + UUID.randomUUID() + "', "
                            + "DATE '2026-08-20', 'courtregister_2026-08-20.json', 'FAILED', true, "
                            + "'connection reset by peer')"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("register_batch_failure_reason_chk");
        }

        @Test
        void completed_by_check_should_name_exactly_the_two_completion_mechanisms()
                throws SQLException {
            assertThat(constraintsOf(BATCH_TABLE).get("register_batch_completed_by_chk"))
                    .isNotNull()
                    .contains(vocabularyOf(CompletedBy.class));
        }

        @Test
        void live_key_index_should_be_unique_and_partial_on_the_unfailed_batches() throws SQLException {
            // One live batch per (court centre, register day). Partial rather than a plain unique
            // constraint because a FAILED batch is re-assemblable under a new batch_id, and a total
            // constraint would make the first failure permanent for that day.
            assertThat(indexesOf(BATCH_TABLE).get("idx_register_batch_live_key"))
                    .isNotNull()
                    .contains("UNIQUE")
                    .contains("court_centre_id, register_date")
                    .contains("'FAILED'");
        }

        @Test
        void a_second_live_batch_for_the_same_court_centre_and_day_should_be_rejected() {
            final UUID courtCentreId = UUID.randomUUID();
            assertThatThrownBy(() -> inRolledBackTransaction(
                    insertBatch(UUID.randomUUID(), courtCentreId, "PENDING"),
                    insertBatch(UUID.randomUUID(), courtCentreId, "GENERATING")))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("idx_register_batch_live_key");
        }

        @Test
        void a_fresh_batch_should_be_allowed_beside_a_failed_one_for_the_same_key() {
            final UUID courtCentreId = UUID.randomUUID();
            assertThatCode(() -> inRolledBackTransaction(
                    insertBatch(UUID.randomUUID(), courtCentreId, "FAILED"),
                    insertBatch(UUID.randomUUID(), courtCentreId, "PENDING")))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("register_notification")
    class RegisterNotificationTable {

        @Test
        void columns_should_be_exactly_the_documented_set() throws SQLException {
            assertThat(columnsOf(NOTIFICATION_TABLE)).containsOnlyKeys(
                    "notification_id", "batch_id", "email_address", "recipient_name",
                    "template_name", "template_id", "status", "response_code", "sent_at",
                    "attempts");
        }

        @Test
        void identity_columns_should_be_non_null_uuids_with_no_database_default()
                throws SQLException {
            // notification_id is minted and persisted before the POST and reused on every retry:
            // notificationnotify keys its aggregate on it, so a fresh one would send a second mail.
            final Map<String, Column> columns = columnsOf(NOTIFICATION_TABLE);
            assertThat(columns.get("notification_id")).isEqualTo(new Column("uuid", false, null));
            assertThat(columns.get("batch_id")).isEqualTo(new Column("uuid", false, null));
        }

        @Test
        void recipient_columns_should_carry_the_address_required_and_the_name_optional()
                throws SQLException {
            // Both are personal data and this row is the only place they may live; the name is
            // optional because a subscription may carry an address and nothing else.
            final Map<String, Column> columns = columnsOf(NOTIFICATION_TABLE);
            assertThat(columns.get("email_address")).isEqualTo(new Column("text", false, null));
            assertThat(columns.get("recipient_name")).isEqualTo(new Column("text", true, null));
        }

        @Test
        void template_columns_should_be_non_null_name_and_identifier() throws SQLException {
            // Resolved at startup and refused there when blank or malformed (defect fix P9), so a
            // row can never record an e-mail sent under a template nobody can name.
            final Map<String, Column> columns = columnsOf(NOTIFICATION_TABLE);
            assertThat(columns.get("template_name")).isEqualTo(new Column("text", false, null));
            assertThat(columns.get("template_id")).isEqualTo(new Column("uuid", false, null));
        }

        @Test
        void settlement_columns_should_be_nullable_until_notificationnotify_answers()
                throws SQLException {
            final Map<String, Column> columns = columnsOf(NOTIFICATION_TABLE);
            assertThat(columns.get("response_code")).isEqualTo(new Column("integer", true, null));
            assertThat(columns.get("sent_at")).isEqualTo(new Column(TIMESTAMPTZ, true, null));
        }

        @Test
        void attempts_should_be_a_non_null_integer_defaulting_to_zero() throws SQLException {
            assertThat(columnsOf(NOTIFICATION_TABLE).get("attempts"))
                    .isEqualTo(new Column("integer", false, "0"));
        }

        @Test
        void attempts_check_should_require_a_non_negative_count() throws SQLException {
            assertThat(constraintsOf(NOTIFICATION_TABLE).get("register_notification_attempts_chk"))
                    .isNotNull()
                    .contains("attempts >= 0");
        }

        @Test
        void primary_key_should_be_the_single_notification_id_column() throws SQLException {
            assertThat(constraintsOf(NOTIFICATION_TABLE).values())
                    .anySatisfy(definition ->
                            assertThat(definition).isEqualTo("PRIMARY KEY (notification_id)"));
        }

        @Test
        void status_check_should_name_exactly_the_notification_states() throws SQLException {
            assertThat(constraintsOf(NOTIFICATION_TABLE).get("register_notification_status_chk"))
                    .isNotNull()
                    .contains(vocabularyOf(NotificationStatus.class));
        }

        @Test
        void status_check_should_reject_a_state_outside_the_enumeration() {
            // No DELIVERED: notificationnotify's delivery events are out of scope, and the furthest
            // this service can honestly record is that the command was accepted.
            final UUID batchId = UUID.randomUUID();
            assertThatThrownBy(() -> inRolledBackTransaction(
                    insertBatch(batchId, UUID.randomUUID(), "GENERATED"),
                    "INSERT INTO " + NOTIFICATION_TABLE + " (notification_id, batch_id, "
                            + "email_address, template_name, template_id, status) VALUES ('"
                            + UUID.randomUUID() + "', '" + batchId + "', 'yot@example.gov.uk', "
                            + "'cr_standard', '" + UUID.randomUUID() + "', 'DELIVERED')"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("register_notification_status_chk");
        }

        @Test
        void one_notification_per_batch_and_address_should_be_unique() throws SQLException {
            // The persistence half of defect fix P4: the recipient set is the de-duplicated union
            // across the batch, so the same Youth Offending Team on ten hearings is told once.
            assertThat(constraintsOf(NOTIFICATION_TABLE)
                    .get("register_notification_unique_recipient"))
                    .isNotNull()
                    .isEqualTo("UNIQUE (batch_id, email_address)");
        }

        @Test
        void a_second_notification_for_the_same_batch_and_address_should_be_rejected() {
            final UUID batchId = UUID.randomUUID();
            assertThatThrownBy(() -> inRolledBackTransaction(
                    insertBatch(batchId, UUID.randomUUID(), "GENERATED"),
                    insertNotification(batchId, "yot@example.gov.uk"),
                    insertNotification(batchId, "yot@example.gov.uk")))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("register_notification_unique_recipient");
        }

        @Test
        void batch_foreign_key_should_point_at_the_batch_table() throws SQLException {
            assertThat(constraintsOf(NOTIFICATION_TABLE).get("register_notification_batch_fk"))
                    .isNotNull()
                    .contains("FOREIGN KEY (batch_id) REFERENCES register_batch(batch_id)");
        }

        @Test
        void a_notification_without_its_batch_should_be_refused() {
            assertThatThrownBy(() -> inRolledBackTransaction(
                    insertNotification(UUID.randomUUID(), "yot@example.gov.uk")))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("register_notification_batch_fk");
        }
    }

    @Nested
    @DisplayName("shedlock")
    class ShedLock {

        @Test
        void columns_should_be_the_standard_shedlock_jdbc_schema() throws SQLException {
            // Standard because ShedLock's own JdbcTemplateLockProvider writes it: any deviation is
            // a lock the library cannot take, and an untaken lock is two pods running the job.
            final Map<String, Column> columns = columnsOf(LOCK_TABLE);

            assertThat(columns).containsOnlyKeys("name", "lock_until", "locked_at", "locked_by");
            assertThat(columns.get("name"))
                    .isEqualTo(new Column("character varying", false, null));
            assertThat(columns.get("lock_until")).isEqualTo(new Column(TIMESTAMPTZ, false, null));
            assertThat(columns.get("locked_at")).isEqualTo(new Column(TIMESTAMPTZ, false, null));
            assertThat(columns.get("locked_by"))
                    .isEqualTo(new Column("character varying", false, null));
        }

        @Test
        void character_columns_should_have_the_documented_lengths() throws SQLException {
            assertThat(declaredLengthOf(LOCK_TABLE, "name")).hasValue(64);
            assertThat(declaredLengthOf(LOCK_TABLE, "locked_by")).hasValue(255);
        }

        @Test
        void primary_key_should_be_the_lock_name() throws SQLException {
            assertThat(constraintsOf(LOCK_TABLE).values())
                    .anySatisfy(definition ->
                            assertThat(definition).isEqualTo("PRIMARY KEY (name)"));
        }

        @Test
        void a_second_row_for_the_same_lock_name_should_be_rejected() {
            // The whole point of the table: the nightly job is one run across the replica set.
            assertThatThrownBy(() -> inRolledBackTransaction(
                    insertLock("register-generation"),
                    insertLock("register-generation")))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("shedlock_pkey");
        }
    }

    @Nested
    @DisplayName("a V1 progression-post row carried through the migration")
    class BackfillOfADeployedV1Row {

        private static final String HEARING_DAY = "2026-08-20";
        private static final String DIGEST =
                "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";

        /**
         * The London start of {@link #HEARING_DAY}, which falls in British Summer Time, so a
         * backfill that cast the date at UTC rather than at Europe/London would land an hour late
         * and this assertion would say so.
         */
        private static final Instant LONDON_MIDNIGHT = LocalDate.parse(HEARING_DAY)
                .atStartOfDay(ZoneId.of("Europe/London"))
                .toInstant();

        private static FluentConfiguration flywayAgainst(final String jdbcUrl) {
            return Flyway.configure()
                    .dataSource(jdbcUrl, PostgresTestSupport.username(), PostgresTestSupport.password())
                    .locations("classpath:db/migration");
        }

        private static Connection connectionTo(final String jdbcUrl) throws SQLException {
            return DriverManager.getConnection(
                    jdbcUrl, PostgresTestSupport.username(), PostgresTestSupport.password());
        }

        /**
         * The parent request as the intake half wrote it: the hearing the register was built from
         * and the day it was received under, which are the two facts the backfill reads.
         */
        private static String insertV1Request(final UUID requestId, final UUID hearingId) {
            return "INSERT INTO " + REQUEST_TABLE + " (source, request_id, hearing_id, hearing_day, "
                    + "shared_time, event_type, request_fingerprint, status, attempts) VALUES ("
                    + "'RESULTS', '" + requestId + "', '" + hearingId + "', DATE '" + HEARING_DAY
                    + "', TIMESTAMPTZ '2026-08-20T09:00:00Z', 'Hearing_Resulted', 'fingerprint', "
                    + "'COMPLETED', 1)";
        }

        /**
         * A settled {@code progression-post} row, in V1's shape: none of the ten columns V2 adds,
         * because at the moment this row was written none of them existed.
         */
        private static String insertV1Output(final UUID outputId, final UUID requestId) {
            return "INSERT INTO " + OUTPUT_TABLE + " (output_id, source, request_id, "
                    + "court_centre_id, register_date, file_name, status, response_code, "
                    + "request_digest) VALUES ('" + outputId + "', 'RESULTS', '" + requestId
                    + "', '" + UUID.randomUUID() + "', DATE '" + HEARING_DAY + "', "
                    + "'courtregister_" + HEARING_DAY + ".json', 'POSTED', 202, '" + DIGEST + "')";
        }

        @Test
        @DisplayName("V2 backfills the four NOT NULL columns and leaves the POST's own evidence alone")
        void a_progression_post_row_should_survive_the_widening_with_the_documented_values()
                throws SQLException {
            // The migration's own promise, made in V2__register_store.sql's backfill comment and in
            // af16dad's message, where it was checked by hand once. A V1 database that already holds
            // `courtregister.output=progression-post` rows is what production would be at cutover,
            // and "add, backfill, then constrain" is only correct if the values it chooses are
            // there afterwards - which nothing asserted until this case.
            final String database =
                    "courtregister_v1_backfill_" + UUID.randomUUID().toString().replace("-", "");
            final String jdbcUrl = PostgresTestSupport.createEmptyDatabase(database);
            final UUID requestId = UUID.randomUUID();
            final UUID hearingId = UUID.randomUUID();
            final UUID outputId = UUID.randomUUID();

            flywayAgainst(jdbcUrl).target("1").load().migrate();

            try (Connection connection = connectionTo(jdbcUrl);
                 Statement statement = connection.createStatement()) {
                statement.executeUpdate(insertV1Request(requestId, hearingId));
                statement.executeUpdate(insertV1Output(outputId, requestId));
            }

            flywayAgainst(jdbcUrl).load().migrate();

            final String sql = """
                    SELECT document::text AS document_json, hearing_id, hearing_date, register_time,
                           recorded_flag_state, status, request_digest, created_at
                      FROM processed_output
                     WHERE output_id = ?
                    """;
            try (Connection connection = connectionTo(jdbcUrl);
                 PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setObject(1, outputId);
                try (ResultSet rows = statement.executeQuery()) {
                    final boolean theRowIsStillThere = rows.next();
                    assertThat(theRowIsStillThere)
                            .as("the row a V1 database already held is still there after V2")
                            .isTrue();

                    // An empty object no reader can mistake for a CourtRegisterDocument, which
                    // always carries at least a documentType.
                    assertThat(rows.getString("document_json")).isEqualTo("{}");
                    assertThat(rows.getObject("hearing_id", UUID.class)).isEqualTo(hearingId);
                    assertThat(rows.getObject("hearing_date", OffsetDateTime.class).toInstant())
                            .isEqualTo(LONDON_MIDNIGHT);
                    // The nearest thing a POST row has to a register instant is when it was written.
                    assertThat(rows.getObject("register_time", OffsetDateTime.class))
                            .isEqualTo(rows.getObject("created_at", OffsetDateTime.class));
                    // The flag did not exist when this row was written, and UNKNOWN says exactly
                    // that rather than guessing which side of the cutover it was on.
                    assertThat(rows.getString("recorded_flag_state")).isEqualTo("UNKNOWN");

                    // Untouched: the 001 differential audit reads both, and on such a row the
                    // digest is still the digest of the bytes posted, not of `document`.
                    assertThat(rows.getString("status")).isEqualTo("POSTED");
                    assertThat(rows.getString("request_digest")).isEqualTo(DIGEST);

                    final boolean thereIsASecondRow = rows.next();
                    assertThat(thereIsASecondRow)
                            .as("and the backfill added no rows of its own")
                            .isFalse();
                }
            }
        }
    }
}
