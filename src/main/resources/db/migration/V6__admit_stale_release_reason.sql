-- V6 - the failure vocabulary admits the ending the next run's stale-batch pass writes.
--
-- A batch still PENDING or GENERATING when the next scheduled run begins, having been in flight
-- longer than `courtregister.generation.stale-after`, is failed NOT_COMPLETED_BY_NEXT_RUN and its
-- registers are released for that same run to re-assemble. Until this migration the column's
-- constraint enumerated six values and refused that row - which would be the batch the pass could
-- not fail, on the night it was trying to give a court centre's registers back.
--
-- This migration WIDENS ONLY, and that is a deliberate split rather than a half-finished tidy-up.
-- GENERATION_TIMED_OUT and the RECONCILER attribution are still written by GenerationReconciler,
-- which is not deleted until later in this increment, so they stay in the list here; V7 removes
-- them once nothing produces them. A constraint that only widens admits every row the old one did,
-- so this applies to any store in any state and refuses to apply to none of them - there is no
-- volume to clean before it. The one that narrows, and therefore the one that can refuse on an
-- existing row, is V7.
--
-- The two attribution constraints are deliberately NOT touched. The new reason is not
-- generator-attributed, so an attributed row under it lands in register_batch_completed_by_shape_chk's
-- third arm as `true = false` and is refused with no edit at all; SchemaMigrationV2IT asserts that
-- refusal rather than reasoning about it, because "no edit was needed" and "no edit was made" are
-- the same diff. Additive and forward-only: V2 is not edited, and no column, table or index is added.

ALTER TABLE register_batch DROP CONSTRAINT register_batch_failure_reason_chk;
ALTER TABLE register_batch ADD CONSTRAINT register_batch_failure_reason_chk
    CHECK (failure_reason IS NULL
        OR failure_reason IN ('PAYLOAD_STORE_UNAVAILABLE', 'RENDER_REQUEST_FAILED',
                              'RENDER_REQUEST_REJECTED', 'GENERATION_FAILED',
                              'GENERATION_TIMED_OUT', 'ASSEMBLY_FAILED',
                              'NOT_COMPLETED_BY_NEXT_RUN'));
