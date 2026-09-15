-- V4 - the reads the exception report and the intake sweep make.
--
-- Neither index changes what the store holds. They exist because two of this service's reads are
-- now made on a schedule rather than by a support engineer with time to wait: the sweep's read runs
-- every gauge-refresh interval for the life of every pod, and the morning report's failed-since read
-- runs over whatever a week of deliveries left behind.

-- The sweep's read, and the report's REQUEST_LATE read. Partial, because the rows it serves are the
-- handful that are still in flight: a full index on created_at would be the size of the table and
-- would be scanned past every terminal row in it. The predicate is written exactly as the query's
-- own WHERE clause writes it, because Postgres matches a partial index by proving the query's
-- predicate implies the index's, and a differently spelled equivalent is a planner coin toss.
CREATE INDEX idx_request_non_terminal_created
    ON processed_request (created_at)
 WHERE status IN ('RECEIVED', 'RETRYING');

-- The report's REQUEST_FAILED read, and the support query that asks what failed yesterday. Not
-- partial: it serves every status, and the window boundary is on updated_at because that is when a
-- row reached the state being asked about - created_at would answer "which requests that ARRIVED
-- yesterday failed", which is a different and less useful question on a morning after an outage.
CREATE INDEX idx_request_status_updated
    ON processed_request (status, updated_at);
