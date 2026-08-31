package uk.gov.hmcts.cp.courtregister.persistence;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import uk.gov.hmcts.cp.courtregister.domain.DistributionCommand;
import uk.gov.hmcts.cp.courtregister.domain.ProcessedRequestRecord;
import uk.gov.hmcts.cp.courtregister.domain.RunClaim;

/**
 * The processed log's statements, one method per statement, written out by hand.
 *
 * <p>Hand-written SQL rather than an ORM because the guard's correctness rests on what each
 * statement affects: <strong>the affected-row count is the decision</strong>. One row from the
 * insert means this delivery owns a fresh request; one row from the reclaim means it won the race
 * for an abandoned claim; one row from an outcome write means the claim it is settling is still its
 * own. A session cache and dirty-checking would sit between the code and exactly the property under
 * test.
 *
 * <p>Zero rows is never a signal to look again. The caller hands the delivery back and lets broker
 * redelivery re-enter the state machine, which already carries back-off and a delivery budget; a
 * re-read loop would burn CPU holding a lock and could starve the runner that won.
 *
 * <p>Every timestamp in these statements comes from the database. Expiry is written as
 * {@code now() + lease} and compared against {@code now()}, both inside the database, so no claim
 * decision anywhere depends on how well two pods' clocks agree.
 */
public class ProcessedRequestRepository {

    private final JdbcClient jdbcClient;
    private final Duration claimLease;

    /**
     * Creates the repository over the processed log's connection.
     *
     * @param jdbcClient the processed log's connection
     * @param claimLease {@code courtregister.claim.lease} — how long an acquired claim stays live
     */
    public ProcessedRequestRepository(final JdbcClient jdbcClient, final Duration claimLease) {
        this.jdbcClient = jdbcClient;
        this.claimLease = claimLease;
    }

    /**
     * Statement 1 — insert a new request, taking the claim and the first attempt in one statement.
     *
     * @param command     the validated request
     * @param fingerprint the fingerprint of its immutable fields
     * @param runClaim    the claim this delivery would run under
     * @return whether this delivery created the record
     */
    public boolean insertNew(
            final DistributionCommand command,
            final String fingerprint,
            final RunClaim runClaim) {
        throw new UnsupportedOperationException("T018 implements the processed-log statements");
    }

    /**
     * Statement 2 — read the record the branch decision is made from.
     *
     * @param source    the record's key, part 1
     * @param requestId the record's key, part 2
     * @return the record, or empty where there is none
     */
    public Optional<ProcessedRequestRecord> read(final String source, final UUID requestId) {
        throw new UnsupportedOperationException("T018 implements the processed-log statements");
    }

    /**
     * Statement 3 — reclaim an absent or expired claim on a non-terminal record.
     *
     * @param runClaim the claim this delivery would run under
     * @return whether this delivery acquired the claim
     */
    public boolean reclaimStaleClaim(final RunClaim runClaim) {
        throw new UnsupportedOperationException("T018 implements the processed-log statements");
    }

    /**
     * Statement 4 — record a completed run, releasing the claim.
     *
     * @param runClaim         the claim the run was made under
     * @param completionReason the bounded reason the run completed for
     * @return whether the write was admitted by the owner-and-token predicate
     */
    public boolean recordCompleted(final RunClaim runClaim, final String completionReason) {
        throw new UnsupportedOperationException("T018 implements the processed-log statements");
    }

    /**
     * Statement 4 — record a transient failure, releasing the claim.
     *
     * @param runClaim      the claim the run was made under
     * @param failureReason the bounded reason the run failed for
     * @return whether the write was admitted by the owner-and-token predicate
     */
    public boolean recordRetrying(final RunClaim runClaim, final String failureReason) {
        throw new UnsupportedOperationException("T018 implements the processed-log statements");
    }

    /**
     * Statement 4 — park the request, recording the identity that exhausted the deliveries.
     *
     * @param runClaim      the claim the run was made under; its message identity parks the row
     * @param failureReason the bounded reason the run failed for
     * @return whether the write was admitted by the owner-and-token predicate
     */
    public boolean recordFailed(final RunClaim runClaim, final String failureReason) {
        throw new UnsupportedOperationException("T018 implements the processed-log statements");
    }

    /**
     * Statement 5 — replay a parked request under a fresh message identity, the claim's own.
     *
     * @param runClaim  the claim the replayed run would be made under
     * @param auditNote the bounded note recording the replay
     * @return whether the replay was admitted
     */
    public boolean replayFailed(final RunClaim runClaim, final String auditNote) {
        throw new UnsupportedOperationException("T018 implements the processed-log statements");
    }
}
