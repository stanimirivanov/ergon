-- ============================================================================
-- Explicit retry attempts with fresh authorization boundaries
-- ============================================================================
--
-- A failed receipt remains terminal for its provider idempotency key. Retrying
-- the business operation therefore creates a new immutable run, not another
-- receipt on the old run. A command inserts the successor and its initial state,
-- appends RETRY_STARTED to the failed predecessor, and marks it SUPERSEDED in
-- one transaction. No authorization or connector invocation is copied.
--
-- Existing rows become attempt one without an UPDATE, preserving the immutable
-- start trigger. Root uniqueness remains; only directly linked successors can
-- extend the case's chain. Composite foreign keys preserve operation meaning.
-- ============================================================================


ALTER TABLE resolution_runs
    DROP CONSTRAINT uq_resolution_runs_case,
    ADD COLUMN attempt_number INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN predecessor_run_id UUID,
    ADD COLUMN predecessor_attempt_number INTEGER
        GENERATED ALWAYS AS (attempt_number - 1) STORED;

ALTER TABLE resolution_runs
    ADD CONSTRAINT ck_resolution_runs_attempt
        CHECK (
            attempt_number > 0
            AND ((attempt_number = 1) = (predecessor_run_id IS NULL))
        ),
    ADD CONSTRAINT uq_resolution_runs_retry_source
        UNIQUE (
            tenant_id, run_id, case_id, attempt_number,
            contract_key, contract_revision, step_id, capability
        ),
    ADD CONSTRAINT uq_resolution_runs_retry_link
        UNIQUE (tenant_id, run_id, predecessor_run_id),
    ADD CONSTRAINT fk_resolution_runs_retry_predecessor
        FOREIGN KEY (
            tenant_id, predecessor_run_id, case_id, predecessor_attempt_number,
            contract_key, contract_revision, step_id, capability
        )
        REFERENCES resolution_runs (
            tenant_id, run_id, case_id, attempt_number,
            contract_key, contract_revision, step_id, capability
        )
        ON DELETE RESTRICT;

CREATE UNIQUE INDEX uq_resolution_runs_root_case
    ON resolution_runs (tenant_id, case_id)
    WHERE predecessor_run_id IS NULL;

CREATE UNIQUE INDEX uq_resolution_runs_direct_successor
    ON resolution_runs (tenant_id, predecessor_run_id)
    WHERE predecessor_run_id IS NOT NULL;


ALTER TABLE resolution_run_states
    DROP CONSTRAINT ck_resolution_run_states_state,
    DROP CONSTRAINT ck_resolution_run_states_transition_shape;

ALTER TABLE resolution_run_states
    ADD CONSTRAINT ck_resolution_run_states_state
        CHECK (state IN (
            'WAITING_FOR_APPROVAL', 'READY_FOR_AUTHORIZATION',
            'VERIFYING', 'ACTION_FAILED', 'VERIFIED_RESOLVED', 'SUPERSEDED'
        )),
    ADD CONSTRAINT ck_resolution_run_states_transition_shape
        CHECK (
            (version = 0 AND state IN ('WAITING_FOR_APPROVAL', 'READY_FOR_AUTHORIZATION'))
            OR (version = 1 AND state IN ('VERIFYING', 'ACTION_FAILED'))
            OR (version = 2 AND state IN ('VERIFIED_RESOLVED', 'SUPERSEDED'))
        );


ALTER TABLE resolution_run_events
    DROP CONSTRAINT ck_resolution_run_events_state,
    DROP CONSTRAINT ck_resolution_run_events_shape,
    ADD COLUMN replacement_run_id UUID,
    ADD COLUMN retry_source_sequence BIGINT
        GENERATED ALWAYS AS (
            CASE WHEN event_type = 'RETRY_STARTED' THEN 1::BIGINT END
        ) STORED;

ALTER TABLE resolution_run_events
    ADD CONSTRAINT uq_resolution_run_events_retry_source
        UNIQUE (tenant_id, run_id, sequence, to_state),
    ADD CONSTRAINT fk_resolution_run_events_retry_failure
        FOREIGN KEY (tenant_id, run_id, retry_source_sequence, from_state)
        REFERENCES resolution_run_events (tenant_id, run_id, sequence, to_state)
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_resolution_run_events_retry_replacement
        FOREIGN KEY (tenant_id, replacement_run_id, run_id)
        REFERENCES resolution_runs (tenant_id, run_id, predecessor_run_id)
        ON DELETE RESTRICT,
    ADD CONSTRAINT ck_resolution_run_events_shape
        CHECK (
            (
                sequence = 1
                AND event_type = 'CAPABILITY_SUCCEEDED'
                AND receipt_outcome = 'SUCCEEDED'
                AND authorization_consumption_id IS NOT NULL
                AND replacement_run_id IS NULL
                AND from_state IN ('WAITING_FOR_APPROVAL', 'READY_FOR_AUTHORIZATION')
                AND to_state = 'VERIFYING'
            )
            OR (
                sequence = 1
                AND event_type = 'CAPABILITY_FAILED'
                AND receipt_outcome = 'FAILED'
                AND authorization_consumption_id IS NOT NULL
                AND replacement_run_id IS NULL
                AND from_state IN ('WAITING_FOR_APPROVAL', 'READY_FOR_AUTHORIZATION')
                AND to_state = 'ACTION_FAILED'
            )
            OR (
                sequence = 2
                AND event_type = 'OUTCOME_PROOF_ACCEPTED'
                AND receipt_outcome IS NULL
                AND authorization_consumption_id IS NULL
                AND replacement_run_id IS NULL
                AND from_state = 'VERIFYING'
                AND to_state = 'VERIFIED_RESOLVED'
            )
            OR (
                sequence = 2
                AND event_type = 'RETRY_STARTED'
                AND receipt_outcome IS NULL
                AND authorization_consumption_id IS NULL
                AND replacement_run_id IS NOT NULL
                AND from_state = 'ACTION_FAILED'
                AND to_state = 'SUPERSEDED'
            )
        );


COMMENT ON COLUMN resolution_runs.attempt_number IS
    'One-based position in the case retry chain; each successor increments by exactly one.';

COMMENT ON COLUMN resolution_runs.predecessor_run_id IS
    'Failed attempt replaced by this run; NULL only for the original attempt.';

COMMENT ON COLUMN resolution_runs.predecessor_attempt_number IS
    'Generated prior position used to constrain retry-chain adjacency and prevent cycles.';

COMMENT ON COLUMN resolution_run_events.replacement_run_id IS
    'Direct successor started by RETRY_STARTED; NULL for all other event variants.';

COMMENT ON COLUMN resolution_run_events.retry_source_sequence IS
    'Generated sequence-one failure reference; binds retry to the predecessor failure event.';
