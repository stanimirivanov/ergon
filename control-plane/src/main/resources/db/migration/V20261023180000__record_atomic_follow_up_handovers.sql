-- Direct owner-to-owner transfer: immutable intent/authority receipt plus a new
-- CLAIMED event, never a fabricated RELEASED event or committed unowned state.
-- Ownership revisions are fencing values, not event counts: handover advances
-- by two to preserve existing claimed/released revision parity for old clients.
-- Additive, no backfill. The supporting unique constraint scans ownership
-- history under ALTER TABLE's exclusive lock; budget its index/WAL and lock
-- window before deployment. Deploy schema/readers before enabling handover.
ALTER TABLE human_follow_up_ownership_events
    ADD CONSTRAINT uq_human_follow_up_ownership_events_handover_reference
    UNIQUE (tenant_id, work_item_id, ownership_revision, claim_id, resolver_actor_id, event_type);

CREATE TABLE human_follow_up_handovers (
    tenant_id UUID NOT NULL,
    work_item_id UUID NOT NULL,
    command_id UUID NOT NULL,
    sender_actor_id UUID NOT NULL,
    sender_authority_evidence_id UUID NOT NULL,
    authority TEXT GENERATED ALWAYS AS ('RESOLVER') STORED,
    ownership_event_type TEXT GENERATED ALWAYS AS ('CLAIMED') STORED,
    original_claim_id UUID NOT NULL,
    expected_ownership_revision BIGINT NOT NULL,
    recipient_actor_id UUID NOT NULL,
    successor_claim_id UUID NOT NULL,
    ownership_revision BIGINT NOT NULL,
    handed_over_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT pk_human_follow_up_handovers PRIMARY KEY (tenant_id, work_item_id, command_id),
    CONSTRAINT uq_human_follow_up_handovers_successor UNIQUE (tenant_id, successor_claim_id),
    CONSTRAINT fk_human_follow_up_handovers_original
        FOREIGN KEY (tenant_id, work_item_id, expected_ownership_revision, original_claim_id, sender_actor_id, ownership_event_type)
        REFERENCES human_follow_up_ownership_events
            (tenant_id, work_item_id, ownership_revision, claim_id, resolver_actor_id, event_type)
        ON DELETE RESTRICT,
    CONSTRAINT fk_human_follow_up_handovers_successor
        FOREIGN KEY (tenant_id, work_item_id, ownership_revision, successor_claim_id, recipient_actor_id, ownership_event_type)
        REFERENCES human_follow_up_ownership_events
            (tenant_id, work_item_id, ownership_revision, claim_id, resolver_actor_id, event_type)
        ON DELETE RESTRICT,
    CONSTRAINT fk_human_follow_up_handovers_sender_authority
        FOREIGN KEY (tenant_id, sender_authority_evidence_id, sender_actor_id, authority)
        REFERENCES approval_authority_evidence (tenant_id, evidence_id, actor_id, authority)
        ON DELETE RESTRICT,
    CONSTRAINT ck_human_follow_up_handovers_distinct_actors CHECK (sender_actor_id <> recipient_actor_id),
    CONSTRAINT ck_human_follow_up_handovers_revision
        CHECK (expected_ownership_revision > 0
            AND expected_ownership_revision <= 9223372036854775805
            AND ownership_revision - 2 = expected_ownership_revision)
);

CREATE TRIGGER trg_human_follow_up_handovers_prevent_mutation
BEFORE UPDATE OR DELETE ON human_follow_up_handovers
FOR EACH ROW EXECUTE FUNCTION prevent_human_follow_up_ownership_event_mutation();

COMMENT ON TABLE human_follow_up_handovers IS
    'Immutable exact handover intent and sender authority. Historical acknowledgement, not current ownership or runtime authorization.';
COMMENT ON COLUMN human_follow_up_handovers.command_id IS
    'Client retry identity within tenant/work item; actor, original claim/revision and recipient must match on replay.';
COMMENT ON COLUMN human_follow_up_handovers.ownership_revision IS
    'Successor CLAIMED revision, two above predecessor. The unused intermediate revision is not a release or missing event.';
