-- ============================================================================
-- Confirmed exhausted-run escalation: exact browser intent, not new runtime policy
-- ============================================================================
-- Runtime events and immutable follow-up creation remain authoritative. This
-- append-only audit ties one browser command to its actual supervisor epoch,
-- resolver attestation, exhausted decision and sole created work item.
-- Write order: runtime event/projection, follow-up, command, in one transaction.
-- No backfill, history rewrite, owner assignment or new case transition occurs.
-- Existing writers omit this audit and remain compatible. UNIQUE/FK validation
-- scans events/work under write-conflicting locks; budget table/index sizes,
-- lock duration, WAL/replication lag and disk before deployment. Disable commands
-- for behavioral rollback; preserve audit and recover forward after first use.

-- Exact supporting keys bind semantic attribution, not merely nearby UUIDs.
ALTER TABLE resolution_run_events
    ADD CONSTRAINT uq_run_browser_escalation_event UNIQUE (
        tenant_id, run_id, event_id, escalation_reason, escalation_actor_id,
        escalation_authority_evidence_id, escalation_policy_revision,
        escalation_source_attempt_number, escalation_maximum_attempts, occurred_at
    );

ALTER TABLE human_follow_up_work_items
    ADD CONSTRAINT uq_browser_escalation_follow_up UNIQUE (
        tenant_id, work_item_id, run_id, escalation_event_id, reason, queue_key, status, opened_at
    );

CREATE TABLE resolution_run_escalation_commands (
    tenant_id UUID NOT NULL,
    command_id UUID NOT NULL,
    run_id UUID NOT NULL,
    actor_id UUID NOT NULL,
    supervision_revision BIGINT NOT NULL,
    supervised_at TIMESTAMPTZ NOT NULL,
    expected_run_version BIGINT NOT NULL,
    source_attempt_number INTEGER NOT NULL,
    retry_policy_revision TEXT NOT NULL,
    maximum_attempts INTEGER NOT NULL,
    reason TEXT NOT NULL,
    authority_evidence_id UUID NOT NULL,
    escalation_event_id UUID NOT NULL,
    follow_up_work_item_id UUID NOT NULL,
    follow_up_queue_key TEXT NOT NULL DEFAULT 'access-restoration',
    creation_status TEXT NOT NULL DEFAULT 'OPEN',
    requested_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT pk_resolution_run_escalation_commands PRIMARY KEY (tenant_id, command_id),
    CONSTRAINT uq_run_escalation_command_run UNIQUE (tenant_id, run_id),
    CONSTRAINT ck_run_escalation_command_version CHECK (expected_run_version = 1),
    CONSTRAINT ck_run_escalation_command_revision CHECK (supervision_revision BETWEEN 0 AND 9007199254740990),
    CONSTRAINT ck_run_escalation_command_exhaustion CHECK (maximum_attempts > 0 AND source_attempt_number >= maximum_attempts),
    CONSTRAINT ck_run_escalation_command_policy CHECK (LENGTH(BTRIM(retry_policy_revision)) BETWEEN 1 AND 200),
    CONSTRAINT ck_run_escalation_command_reason CHECK (reason = 'RETRY_ATTEMPT_LIMIT_REACHED'),
    CONSTRAINT ck_run_escalation_command_creation CHECK (follow_up_queue_key = 'access-restoration' AND creation_status = 'OPEN'),
    CONSTRAINT fk_run_escalation_command_supervision FOREIGN KEY (
        tenant_id, run_id, supervision_revision, actor_id, supervised_at
    ) REFERENCES resolution_run_supervision_events (
        tenant_id, run_id, supervision_revision, supervisor_actor_id, occurred_at
    ) ON DELETE RESTRICT,
    CONSTRAINT fk_run_escalation_command_event FOREIGN KEY (
        tenant_id, run_id, escalation_event_id, reason, actor_id, authority_evidence_id,
        retry_policy_revision, source_attempt_number, maximum_attempts, requested_at
    ) REFERENCES resolution_run_events (
        tenant_id, run_id, event_id, escalation_reason, escalation_actor_id,
        escalation_authority_evidence_id, escalation_policy_revision,
        escalation_source_attempt_number, escalation_maximum_attempts, occurred_at
    ) ON DELETE RESTRICT,
    CONSTRAINT fk_run_escalation_command_follow_up FOREIGN KEY (
        tenant_id, follow_up_work_item_id, run_id, escalation_event_id, reason, follow_up_queue_key, creation_status, requested_at
    ) REFERENCES human_follow_up_work_items (
        tenant_id, work_item_id, run_id, escalation_event_id, reason, queue_key, status, opened_at
    ) ON DELETE RESTRICT
);

CREATE FUNCTION prevent_run_escalation_command_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'browser escalation commands are immutable; cannot %', TG_OP USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_run_escalation_commands_prevent_mutation
BEFORE UPDATE OR DELETE ON resolution_run_escalation_commands
FOR EACH ROW EXECUTE FUNCTION prevent_run_escalation_command_mutation();

COMMENT ON TABLE resolution_run_escalation_commands IS
    'Immutable exact browser exhaustion confirmation and real runtime/follow-up provenance; not assignment or execution authority.';
COMMENT ON COLUMN resolution_run_escalation_commands.supervised_at IS
    'Historical supervisor epoch instant; not current ownership or command occurrence.';
COMMENT ON COLUMN resolution_run_escalation_commands.creation_status IS
    'Original OPEN creation snapshot; claiming never changes this acknowledgement into live ownership state.';
COMMENT ON COLUMN resolution_run_escalation_commands.recorded_at IS
    'Database audit recording instant, preserved unchanged on exact historical replay.';
