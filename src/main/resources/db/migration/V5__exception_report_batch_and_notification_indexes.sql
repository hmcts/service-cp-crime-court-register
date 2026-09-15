-- V5 - the five downstream reads the exception report makes every weekday morning.
--
-- V4 gave the report's two intake reads an index each and left the other five scanning. That was
-- defensible while the batch tables were a night old and is not defensible against tables that grow
-- by every court centre's day for ever: all five run on a schedule, and the morning they are
-- slowest is the morning a support engineer is waiting for the answer. Progression's own defect P8
-- is what an unindexed register table costs.
--
-- Every one of them is PARTIAL. The rows each read wants are the handful in one state, so a total
-- index on the stage timestamp would be the size of the table and would be scanned past every
-- terminal row in it. Each predicate is written EXACTLY as the statement that uses it writes its own
-- WHERE clause: Postgres matches a partial index by proving that the query's predicate implies the
-- index's, and the proof it does cheaply is the syntactic one. A differently spelled equivalent is a
-- planner coin toss whose side changes with the table's statistics rather than with anything here.
--
-- The indexed column is in each case the one its read ORDERs BY as well as filters on, so the sort
-- comes off the index rather than out of memory - "waiting" means a different moment in each of the
-- four batch reads, which is why there are four of them and not one.

-- BATCH_LATE, stage one: a court centre's day nothing has been asked of the renderer for.
CREATE INDEX idx_batch_late_pending
    ON register_batch (assembled_at)
 WHERE status = 'PENDING';

-- BATCH_LATE, stage two: a render systemdocgenerator accepted and has not answered.
CREATE INDEX idx_batch_late_generating
    ON register_batch (requested_at)
 WHERE status = 'GENERATING';

-- BATCH_LATE, stage three: a document that exists and that nobody has been told about.
CREATE INDEX idx_batch_late_generated
    ON register_batch (generated_at)
 WHERE status = 'GENERATED';

-- BATCH_FAILED: the batches that ended inside the window, which is the read a bad morning makes
-- longest. The window is half-open on failed_at, so the index carries the ordering too.
CREATE INDEX idx_batch_failed_at
    ON register_batch (failed_at)
 WHERE status = 'FAILED';

-- NOTIFICATION_FAILED: the sends refused or unanswered inside the window. Partial on FAILED because
-- a healthy estate's rows are overwhelmingly ACCEPTED - which is exactly the shape that makes a full
-- scan expensive and a partial index small.
CREATE INDEX idx_notification_failed_sent
    ON register_notification (sent_at)
 WHERE status = 'FAILED';
