-- ============================================================================
-- Confirmed browser retry and attributable supervision of its fresh successor
-- ============================================================================
-- Runtime starts/events remain authoritative. This audit binds exact reviewed
-- planning, retry policy, predecessor supervision and the new initial assignment.
-- It does not copy execution authority or introduce a connector/worker path.
-- Insert order: successor + runtime event, successor assignment, command receipt.
-- The assignment-to-command FK is deferred only to permit this atomic cycle;
-- an orphan browser assignment cannot commit. Machine writers keep their old
-- non-null subject and omit retry_command_id. Existing rows are not rewritten.
-- ALTER/UNIQUE/FK validation takes write-conflicting locks and scans existing
-- runs/events/assignments. Budget table/index size, lock time, WAL and disk.
-- Upgrade every reader before enabling commands: old non-null machine-subject
-- mappers cannot read browser assignments. After first use, recover forward.

ALTER TABLE resolution_run_supervisor_assignments
    ALTER COLUMN assigning_machine_subject DROP NOT NULL,
    ADD COLUMN retry_command_id UUID,
    ADD CONSTRAINT ck_run_supervisor_assignment_provenance CHECK (
        (assigning_machine_subject IS NOT NULL AND retry_command_id IS NULL)
        OR (assigning_machine_subject IS NULL AND retry_command_id IS NOT NULL AND retry_command_id = command_id)
    );

-- Exact supporting keys prevent audit from describing a different successor
-- snapshot, runtime decision or actor than the owners actually recorded.
ALTER TABLE resolution_runs
    ADD CONSTRAINT uq_run_browser_retry_plan UNIQUE (
        tenant_id, run_id, case_stream_version, contract_key, contract_revision,
        policy_revision, step_id, capability, effective_risk, required_approval
    );

ALTER TABLE resolution_run_events
    ADD CONSTRAINT uq_run_browser_retry_event UNIQUE (
        tenant_id, run_id, event_id, replacement_run_id, retry_actor_id,
        retry_authority_evidence_id, retry_policy_revision,
        retry_source_attempt_number, retry_maximum_attempts, occurred_at
    );

CREATE TABLE resolution_run_retry_commands (
    tenant_id UUID NOT NULL,
    command_id UUID NOT NULL,
    run_id UUID NOT NULL,
    actor_id UUID NOT NULL,
    supervision_revision BIGINT NOT NULL,
    supervised_at TIMESTAMPTZ NOT NULL,
    expected_run_version BIGINT NOT NULL,
    case_stream_version BIGINT NOT NULL,
    contract_key TEXT NOT NULL,
    contract_revision INTEGER NOT NULL,
    policy_revision TEXT NOT NULL,
    step_id TEXT NOT NULL,
    capability TEXT NOT NULL,
    effective_risk TEXT NOT NULL,
    required_approval TEXT NOT NULL,
    retry_policy_revision TEXT NOT NULL,
    maximum_attempts INTEGER NOT NULL,
    source_attempt_number INTEGER NOT NULL,
    successor_run_id UUID NOT NULL,
    assignment_id UUID NOT NULL,
    authority_evidence_id UUID NOT NULL,
    retry_event_id UUID NOT NULL,
    started_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT pk_resolution_run_retry_commands PRIMARY KEY (tenant_id, command_id),
    CONSTRAINT uq_run_retry_command_run UNIQUE (tenant_id, run_id),
    CONSTRAINT uq_run_retry_command_assignment_source
        UNIQUE (tenant_id, command_id, successor_run_id, actor_id, authority_evidence_id),
    CONSTRAINT ck_run_retry_command_run_version CHECK (expected_run_version = 1),
    CONSTRAINT ck_run_retry_command_supervision_revision CHECK (supervision_revision BETWEEN 0 AND 9007199254740990),
    CONSTRAINT ck_run_retry_command_case_version CHECK (case_stream_version BETWEEN 1 AND 9007199254740990),
    CONSTRAINT ck_run_retry_command_eligibility CHECK (source_attempt_number > 0 AND source_attempt_number < maximum_attempts),
    CONSTRAINT fk_run_retry_command_supervision FOREIGN KEY (
        tenant_id, run_id, supervision_revision, actor_id, supervised_at
    ) REFERENCES resolution_run_supervision_events (
        tenant_id, run_id, supervision_revision, supervisor_actor_id, occurred_at
    ) ON DELETE RESTRICT,
    CONSTRAINT fk_run_retry_command_plan FOREIGN KEY (
        tenant_id, successor_run_id, case_stream_version, contract_key, contract_revision,
        policy_revision, step_id, capability, effective_risk, required_approval
    ) REFERENCES resolution_runs (
        tenant_id, run_id, case_stream_version, contract_key, contract_revision,
        policy_revision, step_id, capability, effective_risk, required_approval
    ) ON DELETE RESTRICT,
    CONSTRAINT fk_run_retry_command_event FOREIGN KEY (
        tenant_id, run_id, retry_event_id, successor_run_id, actor_id,
        authority_evidence_id, retry_policy_revision, source_attempt_number, maximum_attempts, started_at
    ) REFERENCES resolution_run_events (
        tenant_id, run_id, event_id, replacement_run_id, retry_actor_id,
        retry_authority_evidence_id, retry_policy_revision, retry_source_attempt_number, retry_maximum_attempts, occurred_at
    ) ON DELETE RESTRICT,
    CONSTRAINT fk_run_retry_command_assignment FOREIGN KEY (tenant_id, successor_run_id, assignment_id)
        REFERENCES resolution_run_supervisor_assignments (tenant_id, run_id, assignment_id) ON DELETE RESTRICT
);

ALTER TABLE resolution_run_supervisor_assignments
    ADD CONSTRAINT fk_run_supervisor_assignment_retry_command FOREIGN KEY (
        tenant_id, retry_command_id, run_id, supervisor_actor_id, authority_evidence_id
    ) REFERENCES resolution_run_retry_commands (
        tenant_id, command_id, successor_run_id, actor_id, authority_evidence_id
    ) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED;

CREATE FUNCTION prevent_run_retry_command_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'browser retry commands are immutable; cannot %', TG_OP USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_run_retry_commands_prevent_mutation
BEFORE UPDATE OR DELETE ON resolution_run_retry_commands
FOR EACH ROW EXECUTE FUNCTION prevent_run_retry_command_mutation();

COMMENT ON TABLE resolution_run_retry_commands IS
    'Immutable exact browser retry intent, runtime successor and its atomic initial supervision; no execution authority.';
COMMENT ON TABLE resolution_run_supervision_events IS
    'Immutable supervision epochs from machine or atomic browser-retry initial assignments and attributable handovers; not execution grants.';
COMMENT ON COLUMN resolution_run_supervisor_assignments.assigning_machine_subject IS
    'Opaque authenticated machine principal; NULL only for a browser-retry assignment with exact command provenance.';
COMMENT ON COLUMN resolution_run_supervisor_assignments.retry_command_id IS
    'NULL for machine assignments; otherwise exact browser retry command bound to this successor, actor and attestation.';
COMMENT ON COLUMN resolution_run_retry_commands.supervised_at IS
    'Historical predecessor supervision instant, not retry occurrence or current ownership.';
COMMENT ON COLUMN resolution_run_retry_commands.started_at IS
    'Exact runtime RETRY_STARTED occurrence instant; not connector invocation time.';
COMMENT ON COLUMN resolution_run_retry_commands.recorded_at IS
    'Database recording instant of this command audit, returned unchanged on historical replay.';
