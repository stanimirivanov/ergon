-- ============================================================================
-- Resolution-run capability-result events and current-state projection
-- ============================================================================
--
-- A connector receipt can advance its exact run once. Immutable events retain
-- the transition source and meaning; resolution_run_states is a replaceable
-- projection used for serialized command decisions. Connector success advances
-- only to VERIFYING and never constitutes verified case resolution.
--
-- The current vertical slice executes one action per run. Receipt uniqueness by
-- run makes lookup deterministic until multi-step execution defines step order.
-- ============================================================================


ALTER TABLE capability_invocation_receipts
    ADD CONSTRAINT uq_capability_invocation_receipts_run
        UNIQUE (tenant_id, run_id),
    ADD CONSTRAINT uq_capability_invocation_receipts_run_event_scope
        UNIQUE (
            tenant_id, authorization_consumption_id, run_id,
            outcome, completed_at
        );


CREATE TABLE resolution_run_states (
    tenant_id UUID NOT NULL,
    run_id UUID NOT NULL,
    state TEXT NOT NULL,
    version BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_resolution_run_states
        PRIMARY KEY (tenant_id, run_id),

    CONSTRAINT fk_resolution_run_states_run
        FOREIGN KEY (tenant_id, run_id)
        REFERENCES resolution_runs (tenant_id, run_id)
        ON DELETE RESTRICT,

    CONSTRAINT ck_resolution_run_states_state
        CHECK (
            state IN (
                'WAITING_FOR_APPROVAL', 'READY_FOR_AUTHORIZATION',
                'VERIFYING', 'ACTION_FAILED'
            )
        ),

    CONSTRAINT ck_resolution_run_states_version
        CHECK (version BETWEEN 0 AND 1),

    CONSTRAINT ck_resolution_run_states_transition_shape
        CHECK (
            (version = 0 AND state IN ('WAITING_FOR_APPROVAL', 'READY_FOR_AUTHORIZATION'))
            OR
            (version = 1 AND state IN ('VERIFYING', 'ACTION_FAILED'))
        )
);


INSERT INTO resolution_run_states (tenant_id, run_id, state, version, updated_at)
SELECT tenant_id, run_id, initial_state, 0, recorded_at
FROM resolution_runs;


CREATE TABLE resolution_run_events (
    tenant_id UUID NOT NULL,
    run_id UUID NOT NULL,
    sequence BIGINT NOT NULL,
    event_id UUID NOT NULL,
    event_type TEXT NOT NULL,
    from_state TEXT NOT NULL,
    to_state TEXT NOT NULL,
    authorization_consumption_id UUID NOT NULL,
    receipt_outcome TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT pk_resolution_run_events
        PRIMARY KEY (tenant_id, run_id, sequence),

    CONSTRAINT uq_resolution_run_events_identity
        UNIQUE (tenant_id, event_id),

    CONSTRAINT uq_resolution_run_events_receipt
        UNIQUE (tenant_id, authorization_consumption_id),

    CONSTRAINT fk_resolution_run_events_run
        FOREIGN KEY (tenant_id, run_id)
        REFERENCES resolution_runs (tenant_id, run_id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_resolution_run_events_receipt
        FOREIGN KEY (
            tenant_id, authorization_consumption_id, run_id,
            receipt_outcome, occurred_at
        )
        REFERENCES capability_invocation_receipts (
            tenant_id, authorization_consumption_id, run_id,
            outcome, completed_at
        )
        ON DELETE RESTRICT,

    CONSTRAINT ck_resolution_run_events_first_sequence
        CHECK (sequence = 1),

    CONSTRAINT ck_resolution_run_events_from_state
        CHECK (from_state IN ('WAITING_FOR_APPROVAL', 'READY_FOR_AUTHORIZATION')),

    CONSTRAINT ck_resolution_run_events_capability_result
        CHECK (
            (
                event_type = 'CAPABILITY_SUCCEEDED'
                AND receipt_outcome = 'SUCCEEDED'
                AND to_state = 'VERIFYING'
            )
            OR
            (
                event_type = 'CAPABILITY_FAILED'
                AND receipt_outcome = 'FAILED'
                AND to_state = 'ACTION_FAILED'
            )
        )
);


CREATE FUNCTION prevent_resolution_run_event_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'resolution run events are immutable; append a later event instead of using %',
        TG_OP
        USING ERRCODE = '55000';
END;
$$;


CREATE TRIGGER trg_resolution_run_events_prevent_mutation
BEFORE UPDATE OR DELETE
ON resolution_run_events
FOR EACH ROW
EXECUTE FUNCTION prevent_resolution_run_event_mutation();


COMMENT ON TABLE resolution_run_states IS
    'Current resolution-run execution state projected transactionally from immutable run events.';

COMMENT ON COLUMN resolution_run_states.version IS
    'Last projected run-event sequence; zero identifies the immutable run-start state.';

COMMENT ON TABLE resolution_run_events IS
    'Immutable ordered resolution-run transitions with exact source evidence.';

COMMENT ON COLUMN resolution_run_events.authorization_consumption_id IS
    'Identifies the terminal connector receipt that caused this run transition.';

COMMENT ON COLUMN resolution_run_events.occurred_at IS
    'Connector completion time copied and foreign-key bound to the source receipt.';

COMMENT ON COLUMN resolution_run_events.recorded_at IS
    'Database instant at which the immutable run event and its projection became durable.';
