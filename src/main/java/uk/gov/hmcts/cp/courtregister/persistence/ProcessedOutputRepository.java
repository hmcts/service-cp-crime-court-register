package uk.gov.hmcts.cp.courtregister.persistence;

import java.sql.Types;
import org.springframework.jdbc.core.simple.JdbcClient;
import uk.gov.hmcts.cp.courtregister.domain.ProcessedOutputClaim;
import uk.gov.hmcts.cp.courtregister.domain.RunClaim;

/**
 * The submission half of the processed log: what was sent for a request, and how it went.
 *
 * <p>The request-level log answers "has this request been dealt with"; this one answers "has this
 * hearing's register already gone", which is the only question that makes a redelivery safe.
 * {@code add-court-register} is not idempotent — every POST appends a {@code CourtRegisterRecorded}
 * event and a {@code court_register_request} row in progression — so a redelivery that could not
 * tell must either risk a duplicate or risk a loss.
 *
 * <p>The court register has no fan-out dimension: one hearing, one register, one POST. The row is
 * therefore keyed by the request itself, and the informant service's per-authority uniqueness gives
 * way to {@code UNIQUE (source, request_id)}. Should a fan-out dimension ever appear the constraint
 * widens rather than being rewritten, and so does the conflict target below.
 *
 * <p>Written in the same idiom as {@link ProcessedRequestRepository}, and for the same reason:
 * hand-written SQL, one method per statement, and <strong>the affected-row count is the
 * decision</strong>. In particular the claim and the skip are one statement, not a read followed by
 * a write. Two deliveries of the same request can be in flight at once, and a {@code SELECT status}
 * that came back {@code PENDING} would already be stale by the time the caller acted on it; a
 * conditional upsert cannot be, because the database decides.
 *
 * <p>Every timestamp comes from the database, so no row's age depends on how well two pods' clocks
 * agree.
 */
public class ProcessedOutputRepository {

    private static final String SOURCE = "source";
    private static final String REQUEST_ID = "requestId";
    private static final String RESPONSE_CODE = "responseCode";

    /**
     * Statement 1 — claim this request for a POST, or discover its register has already gone.
     *
     * <p>The row is written <em>before</em> the POST so that an ambiguous outcome — a timeout, a
     * dropped connection — still leaves evidence that something was attempted and what was in it.
     *
     * <p>The {@code DO UPDATE ... WHERE status <> 'POSTED'} is the skip rule of the design rules
     * expressed as a predicate rather than as a branch in Java: a conflicting row that is already
     * POSTED matches nothing, so the statement affects no rows and the caller is told, in the same
     * breath, both that the row exists and that it must not send again. A row in any other state —
     * PENDING left by a crash, FAILED left by a refusal — is re-claimed and its contents replaced,
     * because they describe the body that is about to be sent rather than the one that was.
     * {@code response_code} is cleared with them: a PENDING row still carrying the status line of
     * the attempt before it would read as though this attempt had already been answered.
     *
     * <p>{@code output_id} is supplied by the caller and survives a re-claim untouched: the conflict
     * branch leaves it alone, so the identity of an output row is fixed the first time it is
     * written.
     */
    private static final String CLAIM_PENDING = """
            INSERT INTO processed_output (
                output_id, source, request_id, court_centre_id, court_centre_ou_code,
                register_date, file_name, status, request_digest, anomaly_summary,
                created_at, updated_at)
            VALUES (
                :outputId, :source, :requestId, :courtCentreId, :courtCentreOuCode,
                :registerDate, :fileName, 'PENDING', :digest, :anomalySummary,
                now(), now())
            ON CONFLICT (source, request_id) DO UPDATE
               SET status = 'PENDING',
                   court_centre_id = EXCLUDED.court_centre_id,
                   court_centre_ou_code = EXCLUDED.court_centre_ou_code,
                   register_date = EXCLUDED.register_date,
                   file_name = EXCLUDED.file_name,
                   request_digest = EXCLUDED.request_digest,
                   anomaly_summary = EXCLUDED.anomaly_summary,
                   response_code = NULL,
                   updated_at = now()
             WHERE processed_output.status <> 'POSTED'
            """;

    /**
     * Statement 2 — the POST was accepted.
     *
     * <p>The {@code status <> 'POSTED'} predicate is the same rule as the claim's, applied to the
     * outcome: <strong>POSTED is terminal</strong>. It costs nothing on the ordinary path and it
     * says, in the statement rather than in a comment, that no later write may move a row out of the
     * state that stops it being sent again.
     */
    private static final String RECORD_POSTED = """
            UPDATE processed_output
               SET status = 'POSTED', response_code = :responseCode, updated_at = now()
             WHERE source = :source AND request_id = :requestId
               AND status <> 'POSTED'
            """;

    /**
     * Statement 3 — the POST did not succeed, however it failed.
     *
     * <p>{@code request_digest} and {@code anomaly_summary} are deliberately left in place. What was
     * attempted is the reconciliation evidence, and it is worth more after a failure than after a
     * success.
     *
     * <p>{@code response_code} is nullable here and not in statement 2, because a connect failure or
     * a timeout has no status line to record — and an ambiguous POST is retried, so the row saying
     * an attempt happened without saying how it ended is exactly the state that warns a duplicate is
     * possible.
     *
     * <p>The {@code status <> 'POSTED'} predicate is the one that has to be there. Two deliveries of
     * a request can overlap — a runner whose claim was reclaimed while it worked is still running —
     * and without the predicate that runner's late failure would move a register the winner had
     * already POSTED back to FAILED. The next delivery would then re-claim it and POST a second,
     * non-idempotent {@code add-court-register}: a duplicate register created by the very log that
     * exists to prevent one.
     */
    private static final String RECORD_FAILED = """
            UPDATE processed_output
               SET status = 'FAILED', response_code = :responseCode, updated_at = now()
             WHERE source = :source AND request_id = :requestId
               AND status <> 'POSTED'
            """;

    private final JdbcClient jdbcClient;

    /**
     * Creates the repository over the processed log's connection.
     *
     * @param jdbcClient the processed log's connection
     */
    public ProcessedOutputRepository(final JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    /**
     * Statement 1 — claim this request for submission, writing the row the POST will be judged by.
     *
     * @param runClaim the claim the run was made under; its key is the row written
     * @param claim    what is about to be sent, and what it was assembled from
     * @return whether this delivery may POST. False means the register is already POSTED and is
     *         skipped, which is how a replay of a request that succeeded avoids sending it twice.
     */
    public boolean claimPending(final RunClaim runClaim, final ProcessedOutputClaim claim) {
        return affected(jdbcClient.sql(CLAIM_PENDING)
                .param("outputId", claim.outputId())
                .param(SOURCE, runClaim.source())
                .param(REQUEST_ID, runClaim.requestId())
                .param("courtCentreId", claim.courtCentreId())
                .param("courtCentreOuCode", claim.courtCentreOuCode(), Types.VARCHAR)
                .param("registerDate", claim.registerDate())
                .param("fileName", claim.fileName())
                .param("digest", claim.requestDigest(), Types.VARCHAR)
                .param("anomalySummary", claim.anomalySummary(), Types.VARCHAR)
                .update());
    }

    /**
     * Statement 2 — record that progression accepted the register.
     *
     * @param runClaim     the claim the run was made under; its key is the row settled
     * @param responseCode the status line progression answered with
     * @return whether a row was moved to POSTED; false means no row was ever claimed for this
     *         request, or it was already POSTED by an overlapping delivery
     */
    public boolean recordPosted(final RunClaim runClaim, final int responseCode) {
        return affected(outcome(RECORD_POSTED, runClaim, responseCode));
    }

    /**
     * Statement 3 — record that the register did not go.
     *
     * @param runClaim     the claim the run was made under; its key is the row settled
     * @param responseCode the status line progression answered with, or {@code null} where there
     *                     was no answer to record
     * @return whether a row was moved to FAILED; false means no row was ever claimed for this
     *         request, or it is POSTED and must not be moved out of it
     */
    public boolean recordFailed(final RunClaim runClaim, final Integer responseCode) {
        return affected(outcome(RECORD_FAILED, runClaim, responseCode));
    }

    /** The two outcome writes differ only in the status they set; the key predicate is common. */
    private int outcome(
            final String sql, final RunClaim runClaim, final Integer responseCode) {
        return jdbcClient.sql(sql)
                .param(SOURCE, runClaim.source())
                .param(REQUEST_ID, runClaim.requestId())
                .param(RESPONSE_CODE, responseCode, Types.INTEGER)
                .update();
    }

    private static boolean affected(final int rows) {
        return rows > 0;
    }
}
