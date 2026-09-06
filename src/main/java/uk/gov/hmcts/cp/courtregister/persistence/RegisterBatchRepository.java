package uk.gov.hmcts.cp.courtregister.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import uk.gov.hmcts.cp.courtregister.domain.BatchFailureReason;
import uk.gov.hmcts.cp.courtregister.domain.BatchStatus;
import uk.gov.hmcts.cp.courtregister.domain.RegisterBatch;

/**
 * The {@code register_batch} table: one row per court centre per register day.
 *
 * <p>Separate from {@link ProcessedOutputRepository} because the two answer different questions. The
 * output rows say what was recorded for a hearing; this one says what was asked of the renderer for
 * a group of them, and it is the row a public event correlates back to. The progression leg had
 * neither, which is why a failed generation there left nothing behind to look at.
 *
 * <p>Written in the same idiom as {@link ProcessedOutputRepository}: hand-written SQL, one method
 * per statement, and the affected-row count is the decision.
 *
 * <p><strong>The single-table half of the batch's life.</strong> {@link JdbcRegisterStore} owns the
 * writes that have to move {@code processed_output} in the same statement - assembly and every
 * {@code mark} - because those are atomic or they are wrong. What is left is what the other
 * collaborators need and can do alone: the reconciler's overdue read, the listener's fallback
 * lookup, the operations CLI's own assembly, and the whole-row write that carries the facts the
 * port's {@code mark} signatures do not, {@code completed_by} above all.
 */
public class RegisterBatchRepository {

    private static final String BATCH_ID = "batchId";

    /**
     * Statement 1 - a batch written whole, by a caller that already holds every fact about it.
     *
     * <p>The CLI's assembly, where {@code system_generated} is false, and the re-assembly of a
     * FAILED batch under a fresh identity. Nothing is defaulted here: a row this repository writes
     * says what its caller decided, which is what makes it possible to tell a run started by the
     * schedule from one a person started.
     */
    private static final String INSERT_BATCH = """
            INSERT INTO register_batch (
                batch_id, court_centre_id, court_centre_ou_code, court_house, register_date,
                file_name, payload_file_id, document_file_id, status, failure_reason, sdg_reason,
                system_generated, completed_by, assembled_at, requested_at, generated_at,
                notified_at, failed_at, attempts)
            VALUES (
                :batchId, :courtCentreId, :courtCentreOuCode, :courtHouse, :registerDate,
                :fileName, :payloadFileId, :documentFileId, :status, :failureReason, :sdgReason,
                :systemGenerated, :completedBy, :assembledAt, :requestedAt, :generatedAt,
                :notifiedAt, :failedAt, :attempts)
            """;

    private static final String SELECT_BATCH = """
            SELECT batch_id, court_centre_id, court_centre_ou_code, court_house, register_date,
                   file_name, payload_file_id, document_file_id, status, failure_reason, sdg_reason,
                   system_generated, completed_by, assembled_at, requested_at, generated_at,
                   notified_at, failed_at, attempts
              FROM register_batch
            """;

    /** Statement 2 - one batch by the identity every downstream call correlates on. */
    private static final String FIND_BY_ID = SELECT_BATCH + " WHERE batch_id = :batchId";

    /** Statement 3 - one batch by the payload it was rendered from. */
    private static final String FIND_BY_PAYLOAD_FILE_ID =
            SELECT_BATCH + " WHERE payload_file_id = :payloadFileId";

    /**
     * Statement 4 - the batches whose outcome is overdue, oldest first.
     *
     * <p>Ordered so that a run that cannot reconcile all of them reconciles the ones that have been
     * waiting longest, which are the ones a Youth Offending Team is already missing a register for.
     */
    private static final String GENERATING_SINCE = SELECT_BATCH + """
             WHERE status = 'GENERATING' AND requested_at < :requestedBefore
             ORDER BY requested_at, batch_id
            """;

    /**
     * Statement 5 - the batch as it should now stand.
     *
     * <p>The whole mutable row, so a caller that read a batch, decided about it and writes it back
     * cannot leave half of its decision behind. The key and the assembly facts are not among the
     * columns set: what a batch is for was decided when it was assembled, and only where it has got
     * to changes afterwards.
     */
    private static final String UPDATE_BATCH = """
            UPDATE register_batch
               SET payload_file_id = :payloadFileId,
                   document_file_id = :documentFileId,
                   status = :status,
                   failure_reason = :failureReason,
                   sdg_reason = :sdgReason,
                   completed_by = :completedBy,
                   requested_at = :requestedAt,
                   generated_at = :generatedAt,
                   notified_at = :notifiedAt,
                   failed_at = :failedAt,
                   attempts = :attempts
             WHERE batch_id = :batchId
            """;

    private final JdbcClient jdbcClient;

    /**
     * Creates the repository over the register store's connection.
     *
     * @param jdbcClient the register store's connection
     */
    public RegisterBatchRepository(final JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * Statement 1 - inserts a newly assembled batch.
     *
     * @param batch the batch, carrying the identity minted at assembly
     */
    public void insert(final RegisterBatch batch) {
        mutable(jdbcClient.sql(INSERT_BATCH)
                .param(BATCH_ID, batch.batchId())
                .param("courtCentreId", batch.courtCentreId())
                .param("courtCentreOuCode", batch.courtCentreOuCode(), Types.VARCHAR)
                .param("courtHouse", batch.courtHouse(), Types.VARCHAR)
                .param("registerDate", batch.registerDate())
                .param("fileName", batch.fileName())
                .param("systemGenerated", batch.systemGenerated())
                .param("assembledAt", offsetOf(batch.assembledAt()),
                        Types.TIMESTAMP_WITH_TIMEZONE), batch)
                .update();
    }

    /**
     * Statement 2 - reads one batch by its identity.
     *
     * @param batchId the batch identity
     * @return the batch, or empty where this service recorded no such batch
     */
    public Optional<RegisterBatch> findById(final UUID batchId) {
        return jdbcClient.sql(FIND_BY_ID)
                .param(BATCH_ID, batchId)
                .query((rs, rowNumber) -> batch(rs))
                .optional();
    }

    /**
     * Statement 3 - reads one batch by the payload it was rendered from.
     *
     * <p>The reconciler's read, and the listener's fallback: an outcome names the payload as well as
     * the correlation, so a batch is still findable when only one of the two is trustworthy.
     *
     * @param payloadFileId the file-service id the payload was stored under
     * @return the batch, or empty where no batch owns that payload
     */
    public Optional<RegisterBatch> findByPayloadFileId(final UUID payloadFileId) {
        return jdbcClient.sql(FIND_BY_PAYLOAD_FILE_ID)
                .param("payloadFileId", payloadFileId)
                .query((rs, rowNumber) -> batch(rs))
                .optional();
    }

    /**
     * Statement 4 - the batches that have been GENERATING since before the given instant.
     *
     * @param requestedBefore the far edge of the grace period
     * @return every batch whose outcome is overdue, oldest first
     */
    public List<RegisterBatch> generatingSince(final Instant requestedBefore) {
        return jdbcClient.sql(GENERATING_SINCE)
                .param("requestedBefore", offsetOf(requestedBefore))
                .query((rs, rowNumber) -> batch(rs))
                .list();
    }

    /**
     * Statement 5 - writes a batch's new state.
     *
     * <p>The transition itself is refused before the write, by {@code BatchStatus}: the state
     * machine belongs to the domain, and a caller that got here has already been told whether the
     * move it is making is one the machine draws.
     *
     * @param batch the batch as it should now stand
     * @return how many rows the statement changed, which is the decision and never a read-back
     */
    public int update(final RegisterBatch batch) {
        return mutable(jdbcClient.sql(UPDATE_BATCH).param(BATCH_ID, batch.batchId()), batch)
                .update();
    }

    /**
     * The columns that change after assembly, bound once for the two statements that write them.
     *
     * <p>Every one of them is nullable and every one is typed: an untyped {@code null} leaves the
     * driver to guess a type from a parameter it can see nothing about, which Postgres refuses
     * rather than guesses.
     */
    private static JdbcClient.StatementSpec mutable(
            final JdbcClient.StatementSpec statement, final RegisterBatch batch) {
        return statement
                .param("payloadFileId", batch.payloadFileId(), Types.OTHER)
                .param("documentFileId", batch.documentFileId(), Types.OTHER)
                .param("status", batch.status().name())
                .param("failureReason", name(batch.failureReason()), Types.VARCHAR)
                .param("sdgReason", batch.sdgReason(), Types.VARCHAR)
                .param("completedBy", name(batch.completedBy()), Types.VARCHAR)
                .param("requestedAt", offsetOf(batch.requestedAt()),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .param("generatedAt", offsetOf(batch.generatedAt()),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .param("notifiedAt", offsetOf(batch.notifiedAt()),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .param("failedAt", offsetOf(batch.failedAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("attempts", batch.attempts());
    }

    private static RegisterBatch batch(final ResultSet rs) throws SQLException {
        return new RegisterBatch(
                rs.getObject("batch_id", UUID.class),
                rs.getObject("court_centre_id", UUID.class),
                rs.getString("court_centre_ou_code"),
                rs.getString("court_house"),
                rs.getObject("register_date", LocalDate.class),
                rs.getString("file_name"),
                rs.getObject("payload_file_id", UUID.class),
                rs.getObject("document_file_id", UUID.class),
                BatchStatus.valueOf(rs.getString("status")),
                failureReason(rs.getString("failure_reason")),
                rs.getString("sdg_reason"),
                rs.getBoolean("system_generated"),
                completedBy(rs.getString("completed_by")),
                instant(rs.getObject("assembled_at", OffsetDateTime.class)),
                instant(rs.getObject("requested_at", OffsetDateTime.class)),
                instant(rs.getObject("generated_at", OffsetDateTime.class)),
                instant(rs.getObject("notified_at", OffsetDateTime.class)),
                instant(rs.getObject("failed_at", OffsetDateTime.class)),
                rs.getInt("attempts"));
    }

    private static BatchFailureReason failureReason(final String value) {
        return value == null ? null : BatchFailureReason.valueOf(value);
    }

    private static RegisterBatch.CompletedBy completedBy(final String value) {
        return value == null ? null : RegisterBatch.CompletedBy.valueOf(value);
    }

    private static String name(final Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static OffsetDateTime offsetOf(final Instant value) {
        return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static Instant instant(final OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
