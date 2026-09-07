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
import uk.gov.hmcts.cp.courtregister.domain.StoreRefusedRowException;

/**
 * The {@code register_notification} table: one row per distinct recipient of a batch.
 *
 * <p>The row is written PENDING before the POST and settled after it, so what was attempted is on
 * record whether or not it was answered - the same discipline 001 applies to
 * {@code processed_output.request_digest}, and for the same reason.
 *
 * <p>Written in the same idiom as {@link ProcessedOutputRepository}: hand-written SQL, one method
 * per statement, the affected-row count as the decision, and every statement made through
 * {@code StoreOutage} so that what reaches the application core is a domain signal and never a
 * {@code org.springframework.dao} type (constitution Principle V) carrying a driver's words.
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

    /**
     * Statement 3 - the recipient rows a resend attempts, which are the ones never accepted.
     *
     * <p>PENDING as well as FAILED, because the two are the same debt. A row is minted PENDING
     * before its POST and settled after it, so a run that stopped in between - the pod died, the
     * store blipped on the settlement, the listener's session rolled the delivery back after the
     * mark had committed - leaves a row that cannot say whether the e-mail was asked for. Reading
     * only the refusals left such a row untouched for ever: nothing re-requests it, the batch is
     * settled PARTIALLY_NOTIFIED against a team that can never be told, and {@code notify} cannot
     * be run again because it would mint a second row for every address
     * {@code UNIQUE (batch_id, email_address)} refuses. An ambiguous outcome is retried
     * (constitution's Idempotency bullet), and it is safe to retry because the retry goes out under
     * the identity the row already holds.
     *
     * <p>ACCEPTED is the only state left out, and it is the whole selection: a team that was told
     * is not told twice.
     *
     * <p><strong>It answers what is outstanding; it is not what the sending path reads.</strong>
     * {@code RegisterNotifierService} reads the whole batch through statement 2, because it has to
     * know which addresses the batch <em>holds</em> a row for as well as which of them are owed one:
     * a recipient with no row at all is owed an e-mail too, and this statement cannot see one. So
     * this is the read for asking a batch what is still outstanding - the {@code notify-register}
     * CLI's report and an operator's question - over one index and without the accepted rows.
     */
    private static final String FIND_UNSETTLED_BY_BATCH_ID = SELECT_NOTIFICATION + """
             WHERE batch_id = :batchId AND status <> 'ACCEPTED'
             ORDER BY email_address
            """;

    /**
     * Statement 4 - one recipient's row settled on what notificationnotify answered.
     *
     * <p>The address, the batch and the template are not among the columns set. What was sent, and
     * to whom, is decided when the row is minted; a settlement may only say how that attempt ended.
     *
     * <p><strong>ACCEPTED is terminal at the row level, and this predicate is what makes it
     * so.</strong> Two mechanisms can reach one generated batch at the same moment, so a run can
     * read a row as unsettled, POST for it, and only then find that the other run's POST was
     * accepted in between. An unconditional settlement would write FAILED over that ACCEPTED row:
     * the team that has been told reads as untold, the batch goes back to PARTIALLY_NOTIFIED, and
     * the resend that follows sends a register about children to a team that already has it. Nought
     * rows changed is the answer instead, and the caller counts it rather than believing the write
     * landed. An ACCEPTED write onto an ACCEPTED row is refused by the same predicate and is not a
     * loss: the row already says what that write was going to say.
     *
     * <p><strong>And the attempt total is computed here rather than by the caller.</strong> Two
     * runs that each read the row at nought and each write an absolute total both write the same
     * number, so one run's attempts are simply lost - a row POSTed for four times reads as two, and
     * the count support tells an exhausted budget from a broken route by is wrong in the direction
     * that hides work. What arrives is how many POSTs the call made; what is written is that added
     * to whatever the row holds at the moment of the write.
     */
    private static final String UPDATE_NOTIFICATION = """
            UPDATE register_notification
               SET status = :status,
                   response_code = :responseCode,
                   sent_at = :sentAt,
                   attempts = attempts + :posts
             WHERE notification_id = :notificationId
               AND status <> 'ACCEPTED'
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
     * <p>Made through {@code StoreOutage} like every other statement in this package, in the form
     * for a write the store may refuse: a lost {@code UNIQUE (batch_id, email_address)} reaches the
     * caller as {@link StoreRefusedRowException} carrying this repository's own words. Postgres
     * reports that violation with a detail line quoting the key it refused, and this key is an
     * e-mail address - a component that may never reach a log index (constitution Principle VII) -
     * so neither the driver's message nor the cause travels with it. The refusal itself has to
     * travel: the operator's resend and the outcome sink can derive the same row for one batch at
     * the same moment, and the one that loses the key reads back the row that won.
     *
     * @param notification the row, carrying the identity the POST goes out under
     * @throws StoreRefusedRowException where the batch already holds a row for this address
     */
    public void insert(final RegisterNotification notification) {
        StoreOutage.translatingWrite("mint a recipient's notification row",
                "the store refused a notification row for one recipient of batch "
                        + notification.batchId() + ", which "
                        + "register_notification_unique_recipient does when the row is already held",
                () -> settlement(jdbcClient.sql(INSERT_NOTIFICATION)
                        .param("notificationId", notification.notificationId())
                        .param(BATCH_ID, notification.batchId())
                        .param("emailAddress", notification.emailAddress())
                        .param("recipientName", notification.recipientName(), Types.VARCHAR)
                        .param("templateName", notification.templateName())
                        .param("templateId", notification.templateId()), notification)
                        .param("attempts", notification.attempts())
                        .update());
    }

    /**
     * Statement 2 - every recipient row of a batch, whatever state it is in.
     *
     * @param batchId the batch
     * @return its notification rows
     */
    public List<RegisterNotification> findByBatchId(final UUID batchId) {
        return StoreOutage.translating("read a batch's notification rows",
                () -> jdbcClient.sql(FIND_BY_BATCH_ID)
                        .param(BATCH_ID, batchId)
                        .query((rs, rowNumber) -> notification(rs))
                        .list());
    }

    /**
     * Statement 3 - the recipient rows of a batch that notificationnotify never accepted.
     *
     * <p>FAILED and PENDING alike: a refusal and an attempt that reached no verdict are the same
     * debt to the same team, and only the row's own identity makes re-requesting either of them
     * safe. A recipient the batch holds no row for is owed an e-mail as well and is not here to be
     * seen, which is why the sending path reads {@link #findByBatchId} and this answers what is
     * outstanding.
     *
     * @param batchId the batch
     * @return its unsettled notification rows, each under the identity it was first attempted with
     */
    public List<RegisterNotification> findUnsettledByBatchId(final UUID batchId) {
        return StoreOutage.translating("read a batch's outstanding notification rows",
                () -> jdbcClient.sql(FIND_UNSETTLED_BY_BATCH_ID)
                        .param(BATCH_ID, batchId)
                        .query((rs, rowNumber) -> notification(rs))
                        .list());
    }

    /**
     * Statement 4 - settles one recipient's row on what notificationnotify answered.
     *
     * <p>The attempts this call made are handed over as a count rather than as a total, and the
     * total the row reaches is the statement's business. What the column accumulates is the POSTs
     * made for the row and not the runs that made them, so what a caller knows is how many it
     * made; the number it should be added to is whatever the row says at the moment of the write.
     *
     * @param notification the row as it should now stand, carrying the identity it was minted under
     * @param posts        how many POSTs this call made for the row
     * @return how many rows the statement changed, which is the decision and never a read-back;
     *     nought is a row an acceptance has already made terminal
     */
    public int update(final RegisterNotification notification, final int posts) {
        return StoreOutage.translating("settle a recipient's notification row",
                () -> settlement(jdbcClient.sql(UPDATE_NOTIFICATION)
                        .param("notificationId", notification.notificationId()), notification)
                        .param("posts", posts)
                        .update());
    }

    /**
     * The three columns an attempt's verdict writes, bound once for the two statements that write
     * them.
     *
     * <p>{@code attempts} is not among them: the insert states the count a minted row starts at and
     * a settlement states how many POSTs to add to whatever the row already holds, which are two
     * different numbers, so each of the two callers binds its own.
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
                .param("sentAt", offsetOf(notification.sentAt()), Types.TIMESTAMP_WITH_TIMEZONE);
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
