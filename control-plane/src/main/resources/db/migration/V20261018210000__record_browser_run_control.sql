-- Browser-session coordination only; not provider cancellation or execution authority.
-- Lifecycle and admission histories are authoritative append-only audit. Heads are
-- replaceable projections of lifecycle history. No existing rows are rewritten.
-- Additive tables/indexes acquire no existing-table rewrite locks; FK installation
-- briefly locks referenced tables. Quiesce browser commands before enabling the
-- default-off gate on ALL backend writers, after compatible UI deployment.
-- Older writers ignore this fence: rollback means disable explicitly or recover
-- forward, never downgrade a writer while enforcement is enabled. See ADR 0057.

CREATE TABLE resolution_run_control_events (
    tenant_id UUID NOT NULL,
    run_id UUID NOT NULL,
    sequence BIGINT NOT NULL,
    command_id UUID NOT NULL,
    action TEXT NOT NULL,
    actor_id UUID NOT NULL,
    supervision_revision BIGINT NOT NULL,
    supervised_at TIMESTAMPTZ NOT NULL,
    expected_lease_id UUID,
    expected_fence BIGINT,
    lease_id UUID NOT NULL,
    fence BIGINT NOT NULL,
    holder_id UUID NOT NULL,
    changed_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_run_control_events PRIMARY KEY (tenant_id, run_id, sequence),
    CONSTRAINT uq_run_control_command UNIQUE (tenant_id, command_id),
    CONSTRAINT uq_run_control_admission_source UNIQUE
        (tenant_id, run_id, sequence, lease_id, fence, supervision_revision, actor_id, holder_id),
    CONSTRAINT fk_run_control_run FOREIGN KEY (tenant_id, run_id)
        REFERENCES resolution_runs (tenant_id, run_id) ON DELETE RESTRICT,
    CONSTRAINT fk_run_control_supervision FOREIGN KEY (tenant_id, run_id, supervision_revision, actor_id, supervised_at)
        REFERENCES resolution_run_supervision_events (tenant_id, run_id, supervision_revision, supervisor_actor_id, occurred_at) ON DELETE RESTRICT,
    CONSTRAINT ck_run_control_sequence CHECK (sequence > 0),
    CONSTRAINT ck_run_control_fence CHECK (fence BETWEEN 1 AND 9007199254740990),
    CONSTRAINT ck_run_control_epoch CHECK (supervision_revision BETWEEN 0 AND 9007199254740990),
    CONSTRAINT ck_run_control_time CHECK (changed_at < expires_at),
    CONSTRAINT ck_run_control_duration CHECK (action = 'RELEASE' OR expires_at = changed_at + INTERVAL '120 seconds'),
    CONSTRAINT ck_run_control_action CHECK (
        (action = 'ACQUIRE' AND expected_lease_id IS NULL AND expected_fence IS NULL)
        OR (action IN ('RENEW', 'RELEASE') AND expected_lease_id IS NOT NULL
            AND expected_fence IS NOT NULL AND expected_lease_id = lease_id AND expected_fence = fence)
    )
);

CREATE TABLE resolution_run_control_heads (
    tenant_id UUID NOT NULL,
    run_id UUID NOT NULL,
    sequence BIGINT NOT NULL,
    CONSTRAINT pk_run_control_heads PRIMARY KEY (tenant_id, run_id),
    CONSTRAINT fk_run_control_head_event FOREIGN KEY (tenant_id, run_id, sequence)
        REFERENCES resolution_run_control_events (tenant_id, run_id, sequence) ON DELETE RESTRICT
);

CREATE TABLE resolution_run_control_admissions (
    tenant_id UUID NOT NULL,
    kind TEXT NOT NULL,
    command_id UUID NOT NULL,
    run_id UUID NOT NULL,
    lease_sequence BIGINT NOT NULL,
    lease_id UUID NOT NULL,
    fence BIGINT NOT NULL,
    supervision_revision BIGINT NOT NULL,
    actor_id UUID NOT NULL,
    holder_id UUID NOT NULL,
    admitted_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_run_control_admissions PRIMARY KEY (tenant_id, kind, command_id),
    CONSTRAINT ck_run_control_admission_kind CHECK (kind IN
        ('HANDOVER', 'APPROVAL', 'PROOF', 'EXECUTION', 'EXECUTION_RECOVERY', 'RETRY', 'ESCALATION', 'CANCELLATION')),
    CONSTRAINT fk_run_control_admission_event FOREIGN KEY
        (tenant_id, run_id, lease_sequence, lease_id, fence, supervision_revision, actor_id, holder_id)
        REFERENCES resolution_run_control_events
        (tenant_id, run_id, sequence, lease_id, fence, supervision_revision, actor_id, holder_id) ON DELETE RESTRICT
);

CREATE INDEX ix_run_control_admissions_source ON resolution_run_control_admissions (tenant_id, run_id, lease_sequence);

CREATE FUNCTION prevent_run_control_history_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'browser run control history is immutable; cannot %', TG_OP USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_run_control_events_immutable BEFORE UPDATE OR DELETE ON resolution_run_control_events
FOR EACH ROW EXECUTE FUNCTION prevent_run_control_history_mutation();
CREATE TRIGGER trg_run_control_admissions_immutable BEFORE UPDATE OR DELETE ON resolution_run_control_admissions
FOR EACH ROW EXECUTE FUNCTION prevent_run_control_history_mutation();

COMMENT ON TABLE resolution_run_control_events IS 'Immutable browser control lifecycle receipts; run-first locking serializes sequence and acquisition fence allocation.';
COMMENT ON COLUMN resolution_run_control_events.holder_id IS 'Server-generated session binding; not a raw cookie, session ID, provider subject or bearer credential; never disclose.';
COMMENT ON COLUMN resolution_run_control_events.fence IS 'Monotonic per-run acquisition number; renew and release retain it, new acquisition advances it across epochs.';
COMMENT ON COLUMN resolution_run_control_events.expected_lease_id IS 'NULL only on acquire; renew/release retain the original request fence for exact replay.';
COMMENT ON COLUMN resolution_run_control_events.expected_fence IS 'NULL only on acquire; paired with expected_lease_id, otherwise equal to the resulting acquisition fence.';
COMMENT ON COLUMN resolution_run_control_events.sequence IS 'Per-run lifecycle ordering, independent of acquisition fence; monotonically advances on every lifecycle command.';
COMMENT ON COLUMN resolution_run_control_events.expires_at IS 'Exclusive server-clock expiry; acquire and explicit renewal set changed_at plus 120 seconds.';
COMMENT ON TABLE resolution_run_control_heads IS 'Rebuildable latest-event projection; lifecycle history is authoritative. Application run lock serializes head movement.';
COMMENT ON TABLE resolution_run_control_admissions IS 'Immutable original fence for each browser effect admission, committed atomically with that command. Receipt replay does not acquire control.';
COMMENT ON COLUMN resolution_run_control_admissions.command_id IS 'Business command UUID; additional recovery uses its distinct attempt UUID and EXECUTION_RECOVERY namespace. The first attempt shares the EXECUTION admission.';
COMMENT ON COLUMN resolution_run_control_admissions.admitted_at IS 'Final server-clock check after provisional writes/lock waits and before commit; no external I/O is enclosed.';
