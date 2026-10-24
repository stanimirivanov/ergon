-- Administrative priority is independent of ownership, case/run state and risk.
-- No assignment means NORMAL at priority revision zero; do not backfill or
-- rewrite immutable opening rows. Each accepted set appends one independent
-- revision, including a repeated value under a new command identity.
-- Empty additive table: ordinary referenced-table DDL locks, no existing-table
-- scan/rewrite or extra index build. The PK supports the later latest-priority
-- read; command uniqueness supports exact application-level replay lookup.
-- Deploy before the separately gated producer/read projection. Keep audit and
-- terminal guards on rollback. No HTTP producer is introduced by this migration.
CREATE TABLE human_follow_up_priority_assignments (
    tenant_id UUID NOT NULL,
    work_item_id UUID NOT NULL,
    priority_revision BIGINT NOT NULL,
    command_id UUID NOT NULL,
    priority TEXT NOT NULL,
    claim_id UUID NOT NULL,
    ownership_revision BIGINT NOT NULL,
    actor_id UUID NOT NULL,
    authority_evidence_id UUID NOT NULL,
    authority TEXT GENERATED ALWAYS AS ('RESOLVER') STORED,
    ownership_event_type TEXT GENERATED ALWAYS AS ('CLAIMED') STORED,
    assigned_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT pk_human_follow_up_priority_assignments
        PRIMARY KEY (tenant_id, work_item_id, priority_revision),
    CONSTRAINT uq_human_follow_up_priority_assignments_command
        UNIQUE (tenant_id, work_item_id, command_id),
    CONSTRAINT fk_human_follow_up_priority_assignments_claim
        FOREIGN KEY (tenant_id, work_item_id, ownership_revision, claim_id, actor_id, ownership_event_type)
        REFERENCES human_follow_up_ownership_events
            (tenant_id, work_item_id, ownership_revision, claim_id, resolver_actor_id, event_type)
        ON DELETE RESTRICT,
    CONSTRAINT fk_human_follow_up_priority_assignments_authority
        FOREIGN KEY (tenant_id, authority_evidence_id, actor_id, authority)
        REFERENCES approval_authority_evidence (tenant_id, evidence_id, actor_id, authority)
        ON DELETE RESTRICT,
    CONSTRAINT ck_human_follow_up_priority_assignments_value
        CHECK (priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT')),
    CONSTRAINT ck_human_follow_up_priority_assignments_revisions
        CHECK (priority_revision > 0 AND ownership_revision > 0)
);

CREATE FUNCTION require_current_human_follow_up_priority_assignment()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
    current_priority_revision BIGINT;
BEGIN
    -- Shared serialization order: work row first, then fresh terminal/ownership
    -- and priority reads. The locking statement's pre-wait snapshot is not a gate.
    PERFORM 1 FROM human_follow_up_work_items
        WHERE tenant_id = NEW.tenant_id AND work_item_id = NEW.work_item_id
        FOR UPDATE;
    IF NOT FOUND OR EXISTS (
        SELECT 1 FROM human_follow_up_completions
        WHERE tenant_id = NEW.tenant_id AND work_item_id = NEW.work_item_id
    ) OR NOT EXISTS (
        SELECT 1 FROM human_follow_up_current_ownership
        WHERE tenant_id = NEW.tenant_id AND work_item_id = NEW.work_item_id
            AND current_claim_id = NEW.claim_id AND ownership_revision = NEW.ownership_revision
            AND current_resolver_actor_id = NEW.actor_id
    ) THEN
        RAISE EXCEPTION 'priority requires exact active ownership' USING ERRCODE = '23514';
    END IF;

    SELECT COALESCE(MAX(priority_revision), 0) INTO current_priority_revision
        FROM human_follow_up_priority_assignments
        WHERE tenant_id = NEW.tenant_id AND work_item_id = NEW.work_item_id;
    IF NEW.priority_revision <= 0 OR NEW.priority_revision - 1 <> current_priority_revision THEN
        RAISE EXCEPTION 'priority requires the next independent revision' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_human_follow_up_priority_assignments_require_current
BEFORE INSERT ON human_follow_up_priority_assignments
FOR EACH ROW EXECUTE FUNCTION require_current_human_follow_up_priority_assignment();

CREATE TRIGGER trg_human_follow_up_priority_assignments_prevent_mutation
BEFORE UPDATE OR DELETE ON human_follow_up_priority_assignments
FOR EACH ROW EXECUTE FUNCTION prevent_human_follow_up_ownership_event_mutation();

COMMENT ON TABLE human_follow_up_priority_assignments IS
    'Immutable administrative priority assignments. Never changes queue order, risk, authority, ownership or case/run/proof state.';
COMMENT ON COLUMN human_follow_up_priority_assignments.priority_revision IS
    'Contiguous per-work priority position independent of ownership. No row means NORMAL at revision zero.';
COMMENT ON COLUMN human_follow_up_priority_assignments.command_id IS
    'Original intent within tenant/work. A later authenticated command must compare full intent before historical acknowledgement.';
COMMENT ON COLUMN human_follow_up_priority_assignments.authority_evidence_id IS
    'Attributable resolver evidence, not authentication or proof of freshness/scope. The application checks current tenant-wide authority after locking.';
