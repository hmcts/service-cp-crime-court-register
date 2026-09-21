-- V7 - the two values the grace-period reconciler wrote leave the schema with it.
--
-- The reconciler, its timer, its lock and the systemdocgenerator query it asked are deleted, so
-- nothing goes and asks what became of a render and nothing can conclude that one timed out. A
-- batch nothing is learned about is failed NOT_COMPLETED_BY_NEXT_RUN by the next scheduled run
-- instead, which is this service deciding for itself and is therefore attributed to nobody. With
-- the writer gone, GENERATION_TIMED_OUT and the RECONCILER attribution are values this repository
-- can no longer explain: BatchFailureReason.valueOf throws on the first when the 07:00 report
-- reads the row, and the second names a component that does not exist.
--
-- V6 admitted them deliberately - it widened only, because the reconciler was still writing them -
-- and SchemaMigrationV6IT, pinned to targets 5 and 6, still says so. This is the migration that
-- narrows, and it is therefore the one that can refuse to apply.
--
-- THE OPERATIONAL CAVEAT, and it belongs to this migration alone. A CHECK constraint cannot be
-- added to a table that already holds a violating row, so statements 1 and 2 refuse to apply to
-- any store still holding a batch failed GENERATION_TIMED_OUT or completed by RECONCILER: Flyway
-- stops here and the pod does not start. Nothing is deployed, so no environment anybody depends on
-- holds such a row; a developer's local volume, a seeded container or a replayed SIT snapshot may,
-- and is cleaned (`docker compose down -v`) or has those rows deleted before this runs.
-- specs/004-release-stale-batches/quickstart.md says so, and SchemaMigrationV2IT asserts both
-- halves - the refusal, and the row still being there afterwards rather than silently dropped.
--
-- Additive and forward-only in the Flyway sense: V2 and V6 are not edited, and no column, table or
-- index is added, dropped or rewritten.

-- 1. The failure vocabulary: the reason nothing produces any more.
ALTER TABLE register_batch DROP CONSTRAINT register_batch_failure_reason_chk;
ALTER TABLE register_batch ADD CONSTRAINT register_batch_failure_reason_chk
    CHECK (failure_reason IS NULL
        OR failure_reason IN ('PAYLOAD_STORE_UNAVAILABLE', 'RENDER_REQUEST_FAILED',
                              'RENDER_REQUEST_REJECTED', 'GENERATION_FAILED', 'ASSEMBLY_FAILED',
                              'NOT_COMPLETED_BY_NEXT_RUN'));

-- 2. The attribution vocabulary: the mechanism that no longer exists goes.
ALTER TABLE register_batch DROP CONSTRAINT register_batch_completed_by_chk;
ALTER TABLE register_batch ADD CONSTRAINT register_batch_completed_by_chk
    CHECK (completed_by IS NULL OR completed_by = 'EVENT');

-- 3. The shape: the attributed list narrows to the one reason somebody outside this service
--    still reports. The three-arm structure and its COALESCE are unchanged - each arm states what
--    the column must be for one family of states, so all seven states of register_batch_status_chk
--    are still covered and none is covered by omission, and a FAILED row with no reason at all
--    still cannot leave the comparison NULL and pass by evaluating to it.
ALTER TABLE register_batch DROP CONSTRAINT register_batch_completed_by_shape_chk;
ALTER TABLE register_batch ADD CONSTRAINT register_batch_completed_by_shape_chk
    CHECK ((status NOT IN ('PENDING', 'GENERATING') OR completed_by IS NULL)
       AND (status NOT IN ('GENERATED', 'NOTIFIED', 'PARTIALLY_NOTIFIED', 'NOTIFIED_NOBODY')
                OR completed_by IS NOT NULL)
       AND (status <> 'FAILED'
                OR ((COALESCE(failure_reason, '') = 'GENERATION_FAILED')
                     = (completed_by IS NOT NULL))));
