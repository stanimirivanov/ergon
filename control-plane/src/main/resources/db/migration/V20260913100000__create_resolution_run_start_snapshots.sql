-- ============================================================================
-- Immutable resolution-run start snapshots
-- ============================================================================
--
-- Each row fixes the case evidence boundary, exact contract and policy
-- revisions, first step, and effective safeguards used to begin one resolution
-- attempt. The row is an immutable start record, not current execution state.
-- Later transitions must append run events and may update a separate projection;
-- they must never rewrite this decision.
--
-- The application locks the cases projection row, verifies stream_version, and
-- inserts this snapshot in one transaction. The foreign keys independently
-- prove tenant ownership and that the referenced evidence and contract existed.
-- ============================================================================


CREATE TABLE resolution_runs (
    tenant_id UUID NOT NULL,
    run_id UUID NOT NULL,
    case_id UUID NOT NULL,
    case_stream_version BIGINT NOT NULL,
    contract_key TEXT NOT NULL,
    contract_revision INTEGER NOT NULL,
    policy_revision TEXT NOT NULL,
    step_id TEXT NOT NULL,
    capability TEXT NOT NULL,
    effective_risk TEXT NOT NULL,
    required_approval TEXT NOT NULL,
    initial_state TEXT NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT pk_resolution_runs
        PRIMARY KEY (tenant_id, run_id),

    -- The first runtime slice supports one immutable attempt per case. A later
    -- retry design must define attempt identity before relaxing this constraint.
    CONSTRAINT uq_resolution_runs_case
        UNIQUE (tenant_id, case_id),

    CONSTRAINT fk_resolution_runs_case
        FOREIGN KEY (tenant_id, case_id)
        REFERENCES cases (tenant_id, case_id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_resolution_runs_case_snapshot
        FOREIGN KEY (tenant_id, case_id, case_stream_version)
        REFERENCES case_events (tenant_id, case_id, stream_version)
        ON DELETE RESTRICT,

    CONSTRAINT fk_resolution_runs_contract_revision
        FOREIGN KEY (tenant_id, contract_key, contract_revision)
        REFERENCES resolution_contract_revisions (tenant_id, contract_key, revision)
        ON DELETE RESTRICT,

    CONSTRAINT ck_resolution_runs_case_stream_version_positive
        CHECK (case_stream_version > 0),

    CONSTRAINT ck_resolution_runs_policy_revision
        CHECK (
            char_length(policy_revision) BETWEEN 1 AND 200
            AND policy_revision ~ '^[a-z][a-z0-9.-]*(/[a-z0-9][a-z0-9.-]*)+$'
        ),

    CONSTRAINT ck_resolution_runs_step_id
        CHECK (
            char_length(step_id) BETWEEN 1 AND 100
            AND step_id ~ '^[a-z0-9][a-z0-9-]{0,99}$'
        ),

    CONSTRAINT ck_resolution_runs_capability
        CHECK (
            char_length(capability) BETWEEN 1 AND 200
            AND capability ~ '^[a-z][a-z0-9]*(\.[a-z][a-z0-9-]*)+$'
        ),

    -- These closed sets mirror Kotlin enums and require paired migrations when
    -- their durable vocabulary changes.
    CONSTRAINT ck_resolution_runs_effective_risk
        CHECK (effective_risk IN ('LOW', 'MEDIUM', 'HIGH')),

    CONSTRAINT ck_resolution_runs_required_approval
        CHECK (required_approval IN ('NONE', 'REQUESTER', 'RESOLVER')),

    CONSTRAINT ck_resolution_runs_initial_state
        CHECK (initial_state IN ('WAITING_FOR_APPROVAL', 'READY_FOR_AUTHORIZATION')),

    CONSTRAINT ck_resolution_runs_high_risk_approval
        CHECK (effective_risk <> 'HIGH' OR required_approval <> 'NONE'),

    CONSTRAINT ck_resolution_runs_state_matches_approval
        CHECK (
            (required_approval = 'NONE' AND initial_state = 'READY_FOR_AUTHORIZATION')
            OR
            (required_approval <> 'NONE' AND initial_state = 'WAITING_FOR_APPROVAL')
        )
);


CREATE FUNCTION prevent_resolution_run_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'resolution run start snapshots are immutable; append a run event instead of using %',
        TG_OP
        USING ERRCODE = '55000';
END;
$$;


CREATE TRIGGER trg_resolution_runs_prevent_mutation
BEFORE UPDATE OR DELETE
ON resolution_runs
FOR EACH ROW
EXECUTE FUNCTION prevent_resolution_run_mutation();


COMMENT ON TABLE resolution_runs IS
    'Immutable start snapshot for each tenant-scoped resolution attempt; not a current-state projection.';

COMMENT ON COLUMN resolution_runs.case_stream_version IS
    'Last immutable case event included in the evidence snapshot used to start this run.';

COMMENT ON COLUMN resolution_runs.policy_revision IS
    'Exact immutable policy rules used to derive the effective safeguards.';

COMMENT ON COLUMN resolution_runs.initial_state IS
    'Initial workflow requirement only; it does not represent capability authorization.';

COMMENT ON COLUMN resolution_runs.recorded_at IS
    'Database instant at which the immutable run start became durable.';
