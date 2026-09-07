package uk.gov.hmcts.cp.courtregister.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import uk.gov.hmcts.cp.courtregister.domain.NotificationStatus;
import uk.gov.hmcts.cp.courtregister.domain.RegisterNotification;

/**
 * The {@code register_notification} table: one row per distinct recipient of a batch.
 *
 * <p>The row is written PENDING before the POST and settled after it, so what was attempted is on
 * record whether or not it was answered - the same discipline 001 applies to
 * {@code processed_output.request_digest}, and for the same reason.
 *
 * <p>Written in the same idiom as {@link ProcessedOutputRepository}: hand-written SQL, one method
 * per statement, and the affected-row count is the decision.
 *
 * <p>{@code notification_id} is the caller's, minted before the insert and never reissued: it is
 * what notificationnotify keys its aggregate on, so a retry under a fresh identity would send a
 * second e-mail rather than reach the attempt it is retrying. The statements below therefore never
 * generate one and never overwrite one.
 */
public class RegisterNotificationRepository {

    private static final String BATCH_ID = "batchId";

    /** Statement 1 - one recipient's row, minted before its POST is made. */
    private static final String INSERT_NOTIFICATION = """
            INSERT INTO register_notification (
                notification_id, batch_id, email_address, recipient_name, template_name,
                template_id, status, response_code, sent_at, attempts)
            VALUES (
                :notificationId, :batchId, :emailAddress, :recipientName, :templateName,
                :templateId, :status, :responseCode, :sentAt, :attempts)
            """;

    private static final String SELECT_NOTIFICATION = """
            SELECT notification_id, batch_id, email_address, recipient_name, template_name,
                   template_id, status, response_code, sent_at, attempts
              FROM register_notification
            """;

    /**
     * Statement 2 - every recipient row of a batch, in the order the addresses read.
     *
     * <p>Ordered by address rather than by insertion, so a run report and a resend list a person
     * compares by eye come back the same way twice.
     */
    private static final String FIND_BY_BATCH_ID = SELECT_NOTIFICATION + """
             WHERE batch_id = :batchId
             ORDER BY email_address
            """;

    /** Statement 3 - the recipient rows a resend attempts, which are only the failed ones. */
    private static final String FIND_FAILED_BY_BATCH_ID = SELECT_NOTIFICATION + """
             WHERE batch_id = :batchId AND status = 'FAILED'
             ORDER BY email_address
            """;

    /**
     * Statement 4 - one recipient's row settled on what notificationnotify answered.
     *
     * <p>The address, the batch and the template are not among the columns set. What was sent, and
     * to whom, is decided when the row is minted; a settlement may only say how that attempt ended.
     */
    private static final String UPDATE_NOTIFICATION = """
            UPDATE register_notification
               SET status = :status,
                   response_code = :responseCode,
                   sent_at = :sentAt,
                   attempts = :attempts
             WHERE notification_id = :notificationId
            """;

    private final JdbcClient jdbcClient;

    /**
     * Creates the repository over the register store's connection.
     *
     * @param jdbcClient the register store's connection
     */
    public RegisterNotificationRepository(final JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * Statement 1 - mints one recipient's row before its POST is made.
     *
     * @param notification the row, carrying the identity the POST goes out under
     */
    public void insert(final RegisterNotification notification) {
        settlement(jdbcClient.sql(INSERT_NOTIFICATION)
                .param("notificationId", notification.notificationId())
                .param(BATCH_ID, notification.batchId())
                .param("emailAddress", notification.emailAddress())
                .param("recipientName", notification.recipientName(), Types.VARCHAR)
                .param("templateName", notification.templateName())
                .param("templateId", notification.templateId()), notification)
                .update();
    }

    /**
     * Statement 2 - every recipient row of a batch, whatever state it is in.
     *
     * @param batchId the batch
     * @return its notification rows
     */
    public List<RegisterNotification> findByBatchId(final UUID batchId) {
        return jdbcClient.sql(FIND_BY_BATCH_ID)
                .param(BATCH_ID, batchId)
                .query((rs, rowNumber) -> notification(rs))
                .list();
    }

    /**
     * Statement 3 - the recipient rows of a batch that ended FAILED.
     *
     * @param batchId the batch
     * @return its failed notification rows, each under the identity it was first attempted with
     */
    public List<RegisterNotification> findFailedByBatchId(final UUID batchId) {
        return jdbcClient.sql(FIND_FAILED_BY_BATCH_ID)
                .param(BATCH_ID, batchId)
                .query((rs, rowNumber) -> notification(rs))
                .list();
    }

    /**
     * The recipient rows of a batch that were never accepted, whichever way they were left.
     *
     * <p>A compile-safe seam over {@link #findFailedByBatchId(UUID)} so that the cases guarding the
     * widening read fail on their assertions rather than on a missing method. The paired fix gives
     * it a statement of its own that also answers the PENDING rows.
     *
     * @param batchId the batch
     * @return its unsettled notification rows, each under the identity it was first attempted with
     */
    public List<RegisterNotification> findUnsettledByBatchId(final UUID batchId) {
        return findFailedByBatchId(batchId);
    }

    /**
     * Statement 4 - settles one recipient's row on what notificationnotify answered.
     *
     * @param notification the row as it should now stand
     * @return how many rows the statement changed, which is the decision and never a read-back
     */
    public int update(final RegisterNotification notification) {
        return settlement(jdbcClient.sql(UPDATE_NOTIFICATION)
                .param("notificationId", notification.notificationId()), notification)
                .update();
    }

    /**
     * The four columns an attempt writes, bound once for the two statements that write them.
     *
     * <p>{@code response_code} and {@code sent_at} are typed nulls: a connect failure or a timeout
     * has no status line and no settlement instant to record, and a row that carried an invented one
     * would say an attempt was answered when nothing answered at all.
     */
    private static JdbcClient.StatementSpec settlement(
            final JdbcClient.StatementSpec statement, final RegisterNotification notification) {
        return statement
                .param("status", notification.status().name())
                .param("responseCode", notification.responseCode(), Types.INTEGER)
                .param("sentAt", offsetOf(notification.sentAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("attempts", notification.attempts());
    }

    private static RegisterNotification notification(final ResultSet rs) throws SQLException {
        return new RegisterNotification(
                rs.getObject("notification_id", UUID.class),
                rs.getObject("batch_id", UUID.class),
                rs.getString("email_address"),
                rs.getString("recipient_name"),
                rs.getString("template_name"),
                rs.getObject("template_id", UUID.class),
                NotificationStatus.valueOf(rs.getString("status")),
                rs.getObject("response_code", Integer.class),
                instant(rs.getObject("sent_at", OffsetDateTime.class)),
                rs.getInt("attempts"));
    }

    private static OffsetDateTime offsetOf(final Instant value) {
        return value == null ? null : OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static Instant instant(final OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }
}
