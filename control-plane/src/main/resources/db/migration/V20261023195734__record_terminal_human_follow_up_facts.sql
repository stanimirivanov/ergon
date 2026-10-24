-- Administrative completion is an immutable terminal fact, never a rewrite of
-- the OPEN creation snapshot, a release event or verified case resolution.
-- Empty additive table: no backfill or new index on existing history. Its PK
-- serves every active-work exclusion. Creation takes ordinary referenced-table
-- DDL locks; deploy all guarded readers/writers before any completion producer.
CREATE TABLE human_follow_up_completions (
    tenant_id UUID NOT NULL,
    work_item_id UUID NOT NULL,
    command_id UUID NOT NULL,
    claim_id UUID NOT NULL,
    ownership_revision BIGINT NOT NULL,
    actor_id UUID NOT NULL,
    authority_evidence_id UUID NOT NULL,
    authority TEXT GENERATED ALWAYS AS ('RESOLVER') STORED,
    ownership_event_type TEXT GENERATED ALWAYS AS ('CLAIMED') STORED,
    completed_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT pk_human_follow_up_completions PRIMARY KEY (tenant_id, work_item_id),
    CONSTRAINT fk_human_follow_up_completions_claim
        FOREIGN KEY (tenant_id, work_item_id, ownership_revision, claim_id, actor_id, ownership_event_type)
        REFERENCES human_follow_up_ownership_events
            (tenant_id, work_item_id, ownership_revision, claim_id, resolver_actor_id, event_type)
        ON DELETE RESTRICT,
    CONSTRAINT fk_human_follow_up_completions_authority
        FOREIGN KEY (tenant_id, authority_evidence_id, actor_id, authority)
        REFERENCES approval_authority_evidence (tenant_id, evidence_id, actor_id, authority)
        ON DELETE RESTRICT,
    CONSTRAINT ck_human_follow_up_completions_revision CHECK (ownership_revision > 0)
);

-- Serialize trusted producers with claim/release/note/handover writers. The
-- second statement observes ownership after any lock wait at READ COMMITTED.
-- Current authority and authenticated intent remain application responsibilities.
CREATE FUNCTION require_current_human_follow_up_completion()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    PERFORM 1 FROM human_follow_up_work_items
        WHERE tenant_id = NEW.tenant_id AND work_item_id = NEW.work_item_id
        FOR UPDATE;
    IF NOT EXISTS (
        SELECT 1 FROM human_follow_up_current_ownership
        WHERE tenant_id = NEW.tenant_id AND work_item_id = NEW.work_item_id
            AND current_claim_id = NEW.claim_id AND ownership_revision = NEW.ownership_revision
            AND current_resolver_actor_id = NEW.actor_id
    ) THEN
        RAISE EXCEPTION 'completion requires exact current ownership' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_human_follow_up_completions_require_current
BEFORE INSERT ON human_follow_up_completions
FOR EACH ROW EXECUTE FUNCTION require_current_human_follow_up_completion();

CREATE TRIGGER trg_human_follow_up_completions_prevent_mutation
BEFORE UPDATE OR DELETE ON human_follow_up_completions
FOR EACH ROW EXECUTE FUNCTION prevent_human_follow_up_ownership_event_mutation();

COMMENT ON TABLE human_follow_up_completions IS
    'One immutable administrative completion per work item; ends active follow-up access, never case/run/proof resolution.';
COMMENT ON COLUMN human_follow_up_completions.command_id IS
    'Original client intent within tenant/work; historical acknowledgement requires a separate authenticated application gate.';
COMMENT ON COLUMN human_follow_up_completions.ownership_revision IS
    'Exact authoring CLAIMED revision, retained without fabricating a release or changing ownership history.';
