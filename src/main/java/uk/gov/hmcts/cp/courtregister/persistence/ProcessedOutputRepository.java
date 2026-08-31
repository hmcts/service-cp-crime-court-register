package uk.gov.hmcts.cp.courtregister.persistence;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import uk.gov.hmcts.cp.courtregister.domain.ProcessedOutputClaim;

/**
 * The submission half of the processed log: what was sent for a request, and how it went.
 *
 * <p>The request-level log answers "has this request been dealt with"; this one answers "has this
 * hearing's register already gone", which is the only question that makes a redelivery safe.
 * {@code add-court-register} is not idempotent — every POST appends an event and a row in
 * progression — so a redelivery that could not tell must either risk a duplicate or risk a loss.
 *
 * <p>The court register has no fan-out dimension: one hearing, one register, one POST. The row is
 * therefore keyed by the request itself, and the informant service's per-authority uniqueness gives
 * way to {@code UNIQUE (source, request_id)}.
 *
 * <p>Written in the same idiom as {@link ProcessedRequestRepository}, and for the same reason:
 * hand-written SQL, one method per statement, and <strong>the affected-row count is the
 * decision</strong>. In particular the claim and the skip are one statement, not a read followed by
 * a write. Two deliveries of the same request can be in flight at once, and a {@code SELECT status}
 * that came back {@code PENDING} would already be stale by the time the caller acted on it; a
 * conditional upsert cannot be, because the database decides.
 */
public class ProcessedOutputRepository {

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
     * Statement 1 — claim this request for a POST, or discover its register has already gone.
     *
     * @param claim what is about to be sent, and what it was assembled from
     * @return whether this delivery may POST; false means the register is already POSTED and is
     *         skipped
     */
    public boolean claimPending(final ProcessedOutputClaim claim) {
        throw new UnsupportedOperationException("T018 implements the processed-output statements");
    }

    /**
     * Statement 2 — record that progression accepted the register.
     *
     * @param source       the request's key, part 1
     * @param requestId    the request's key, part 2
     * @param responseCode the status line progression answered with
     * @return whether a row was moved to POSTED
     */
    public boolean recordPosted(
            final String source, final UUID requestId, final int responseCode) {
        throw new UnsupportedOperationException("T018 implements the processed-output statements");
    }

    /**
     * Statement 3 — record that the register did not go, however it failed.
     *
     * @param source       the request's key, part 1
     * @param requestId    the request's key, part 2
     * @param responseCode the status line progression answered with, or {@code null} where there
     *                     was no answer to record
     * @return whether a row was moved to FAILED
     */
    public boolean recordFailed(
            final String source, final UUID requestId, final Integer responseCode) {
        throw new UnsupportedOperationException("T018 implements the processed-output statements");
    }
}
