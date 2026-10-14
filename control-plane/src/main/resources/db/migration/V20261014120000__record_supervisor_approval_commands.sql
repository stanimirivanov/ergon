-- Browser approval replay receipts, not another approval or execution authority.
-- Existing bearer decisions remain unchanged. The new table binds a browser
-- command to one immutable decision and its exact historical supervisor epoch.
-- No backfill, runtime transition or grant is created. The supporting UNIQUE
-- scans/locks the decision table; assess size and lock time before deployment.
-- Deploy the additive schema before enabling browser approval commands. Older
-- binaries ignore this table; disabling commands permits application rollback
-- without deleting either decisions or receipts. Retain all audit history.

ALTER TABLE resolution_approval_decisions
    ADD CONSTRAINT uq_approval_decision_supervisor_subject
    UNIQUE (tenant_id, approval_decision_id, run_id, actor_id);

CREATE TABLE resolution_run_approval_commands (
    tenant_id UUID NOT NULL,
    command_id UUID NOT NULL,
    approval_decision_id UUID NOT NULL,
    run_id UUID NOT NULL,
    actor_id UUID NOT NULL,
    supervision_revision BIGINT NOT NULL,
    supervised_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_run_approval_commands PRIMARY KEY (tenant_id, command_id),
    CONSTRAINT uq_run_approval_command_decision UNIQUE (tenant_id, approval_decision_id),
    CONSTRAINT fk_run_approval_command_decision
        FOREIGN KEY (tenant_id, approval_decision_id, run_id, actor_id)
        REFERENCES resolution_approval_decisions (tenant_id, approval_decision_id, run_id, actor_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_run_approval_command_supervision
        FOREIGN KEY (tenant_id, run_id, supervision_revision, actor_id, supervised_at)
        REFERENCES resolution_run_supervision_events (
            tenant_id, run_id, supervision_revision, supervisor_actor_id, occurred_at
        ) ON DELETE RESTRICT
);

CREATE TRIGGER trg_run_approval_commands_prevent_mutation
BEFORE UPDATE OR DELETE ON resolution_run_approval_commands
FOR EACH ROW EXECUTE FUNCTION prevent_resolution_approval_decision_mutation();

COMMENT ON TABLE resolution_run_approval_commands IS
    'Immutable exact browser decision replay intent; decision content and timestamps are owned by resolution_approval_decisions. Not a grant or runtime command.';
COMMENT ON COLUMN resolution_run_approval_commands.supervised_at IS
    'Exact event instant needed to bind the deciding actor to the historical supervision epoch; not the approval decision time.';
