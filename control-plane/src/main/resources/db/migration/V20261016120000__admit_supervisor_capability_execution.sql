-- Attributed browser admission and explicit attempts for the existing invocation
-- pipeline. Consumption is the irrevocable effect reservation, not its outcome.
-- Commands and attempts are append-only; receipts/transitions remain authoritative
-- for results. No backfill. Write grant/consumption, command, then first attempt
-- in one transaction; commit before connector I/O. Recovery appends an attempt
-- against the same reservation under a fresh current supervision/authority gate.
-- Supporting UNIQUE constraints scan/lock existing consumptions and grants;
-- assess lock duration, disk and WAL before rollout. Deploy schema/code before
-- enabling the separate execution flag. Disable it to roll back application code,
-- retaining reservations and immutable audit; never undo an admitted effect.

ALTER TABLE capability_authorization_consumptions
    ADD CONSTRAINT uq_consumption_browser_execution_scope
    UNIQUE (tenant_id, authorization_consumption_id, authorization_grant_id, run_id);

ALTER TABLE capability_authorization_grants
    ADD CONSTRAINT uq_grant_browser_execution_approval
    UNIQUE (tenant_id, authorization_grant_id, approval_decision_id, approval_request_id, run_id);

CREATE TABLE resolution_run_execution_commands (
    tenant_id UUID NOT NULL,
    command_id UUID NOT NULL,
    run_id UUID NOT NULL,
    actor_id UUID NOT NULL,
    supervision_revision BIGINT NOT NULL,
    supervised_at TIMESTAMPTZ NOT NULL,
    expected_run_version BIGINT NOT NULL,
    approval_request_id UUID NOT NULL,
    approval_decision_id UUID NOT NULL,
    authorization_grant_id UUID NOT NULL,
    authorization_consumption_id UUID NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_run_execution_commands PRIMARY KEY (tenant_id, command_id),
    CONSTRAINT uq_run_execution_command_run UNIQUE (tenant_id, run_id),
    CONSTRAINT uq_run_execution_command_actor UNIQUE (tenant_id, command_id, run_id, actor_id),
    CONSTRAINT fk_run_execution_command_supervision
        FOREIGN KEY (tenant_id, run_id, supervision_revision, actor_id, supervised_at)
        REFERENCES resolution_run_supervision_events (
            tenant_id, run_id, supervision_revision, supervisor_actor_id, occurred_at
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_run_execution_command_consumption
        FOREIGN KEY (tenant_id, authorization_consumption_id, authorization_grant_id, run_id)
        REFERENCES capability_authorization_consumptions (
            tenant_id, authorization_consumption_id, authorization_grant_id, run_id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_run_execution_command_approval
        FOREIGN KEY (tenant_id, authorization_grant_id, approval_decision_id, approval_request_id, run_id)
        REFERENCES capability_authorization_grants (
            tenant_id, authorization_grant_id, approval_decision_id, approval_request_id, run_id
        ) ON DELETE RESTRICT,
    CONSTRAINT ck_run_execution_command_version CHECK (expected_run_version = 0),
    CONSTRAINT ck_run_execution_command_revision CHECK (supervision_revision BETWEEN 0 AND 9007199254740990)
);

CREATE TABLE resolution_run_execution_attempts (
    tenant_id UUID NOT NULL,
    attempt_id UUID NOT NULL,
    command_id UUID NOT NULL,
    run_id UUID NOT NULL,
    actor_id UUID NOT NULL,
    supervision_revision BIGINT NOT NULL,
    supervised_at TIMESTAMPTZ NOT NULL,
    expected_run_version BIGINT NOT NULL,
    authority_evidence_id UUID NOT NULL,
    authority TEXT GENERATED ALWAYS AS ('RESOLVER') STORED,
    admitted_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_run_execution_attempts PRIMARY KEY (tenant_id, attempt_id),
    CONSTRAINT fk_run_execution_attempt_command
        FOREIGN KEY (tenant_id, command_id, run_id, actor_id)
        REFERENCES resolution_run_execution_commands (tenant_id, command_id, run_id, actor_id) ON DELETE RESTRICT,
    CONSTRAINT fk_run_execution_attempt_supervision
        FOREIGN KEY (tenant_id, run_id, supervision_revision, actor_id, supervised_at)
        REFERENCES resolution_run_supervision_events (
            tenant_id, run_id, supervision_revision, supervisor_actor_id, occurred_at
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_run_execution_attempt_authority
        FOREIGN KEY (tenant_id, authority_evidence_id, actor_id, authority)
        REFERENCES approval_authority_evidence (tenant_id, evidence_id, actor_id, authority) ON DELETE RESTRICT,
    CONSTRAINT ck_run_execution_attempt_version CHECK (expected_run_version = 0),
    CONSTRAINT ck_run_execution_attempt_revision CHECK (supervision_revision BETWEEN 0 AND 9007199254740990)
);

CREATE FUNCTION prevent_run_execution_audit_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'browser execution audit is immutable; append an explicit attempt instead of %', TG_OP
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_run_execution_commands_prevent_mutation
BEFORE UPDATE OR DELETE ON resolution_run_execution_commands
FOR EACH ROW EXECUTE FUNCTION prevent_run_execution_audit_mutation();
CREATE TRIGGER trg_run_execution_attempts_prevent_mutation
BEFORE UPDATE OR DELETE ON resolution_run_execution_attempts
FOR EACH ROW EXECUTE FUNCTION prevent_run_execution_audit_mutation();

COMMENT ON TABLE resolution_run_execution_commands IS
    'Immutable browser intent bound to one irrevocable capability consumption; not proof of invocation or outcome.';
COMMENT ON TABLE resolution_run_execution_attempts IS
    'Explicit attributed dispatch admissions using the original consumption key. A repeated attempt UUID is acknowledgement only, never another dispatch.';
COMMENT ON COLUMN resolution_run_execution_attempts.admitted_at IS
    'Application instant after lock waits when current supervision and resolver evidence were checked; not connector start or completion.';
