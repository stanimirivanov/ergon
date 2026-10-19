-- Read-only Console admission history uses the existing immutable audit; no
-- rows, timestamps or causal sequence are backfilled. Find the run's command
-- through its existing tenant/run UNIQUE key, then take at most 101 attempts.
-- The UUID tie-breaker is deterministic presentation order, not execution order.
-- This transactional index build scans attempts and blocks their writes while
-- building; schedule a bounded maintenance window and budget index/WAL space.
-- Compatible with existing writers. Application rollback retains this index.
CREATE INDEX ix_run_execution_attempts_history
    ON resolution_run_execution_attempts (tenant_id, command_id, recorded_at, attempt_id);

COMMENT ON INDEX ix_run_execution_attempts_history IS
    'Supports bounded tenant/command admission history ordered by recording time and private UUID, not causal dispatch order.';
