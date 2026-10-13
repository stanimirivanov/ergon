-- ============================================================================
-- Runtime measurement of the existing single capability call
-- ============================================================================
-- Receipt scope already binds tenant, run, step, capability and connector to the
-- exact authorization consumption. These nullable fields add one caller's
-- gateway timing to that same immutable fact; outcome and timing commit together.
-- Concurrent losers replay the winner's receipt, not their own duration.
--
-- NULL in both fields means no measurement was recorded (historical/old writer).
-- Do not backfill timestamps or infer duration from consumption/completion time:
-- waiting time and wall-clock correction are not connector-call latency.
-- Cost is not measured by this runtime and is not added to storage.
--
-- Additive mixed-version expansion: older writers leave both fields NULL. This
-- ALTER takes ACCESS EXCLUSIVE and validates a CHECK by scanning existing rows;
-- it does not rewrite receipt payloads. Schedule the lock/scan for table size.
-- Application rollback leaves measurements intact under the existing mutation
-- trigger. Apply this migration before deploying the new reader/writer.
-- ============================================================================

ALTER TABLE capability_invocation_receipts
    ADD COLUMN invocation_started_at TIMESTAMPTZ,
    ADD COLUMN invocation_elapsed_nanoseconds BIGINT,
    ADD CONSTRAINT ck_capability_invocation_receipts_timing
        CHECK (
            (invocation_started_at IS NULL AND invocation_elapsed_nanoseconds IS NULL)
            OR
            (
                invocation_started_at IS NOT NULL
                AND invocation_elapsed_nanoseconds IS NOT NULL
                AND invocation_elapsed_nanoseconds >= 0
            )
        );

COMMENT ON COLUMN capability_invocation_receipts.invocation_started_at IS
    'Runtime wall-clock observation immediately before connector I/O; NULL with elapsed duration means unmeasured. Clock corrections can place this after completed_at.';

COMMENT ON COLUMN capability_invocation_receipts.invocation_elapsed_nanoseconds IS
    'Non-negative monotonic elapsed nanoseconds around the connector gateway call, excluding database work; NULL means unmeasured, not zero or provider cost.';
