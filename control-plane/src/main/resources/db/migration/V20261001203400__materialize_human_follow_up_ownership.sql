-- Materialize current ownership without changing immutable first claims.
-- The trigger mirrors first-claim inserts from both old and new application
-- versions. CREATE TRIGGER takes a write-conflicting table lock before the
-- backfill, so a concurrent old writer cannot slip between the two steps.
-- The backfill scans existing claims once; size and lock duration must be
-- assessed before deployment to a populated environment.

CREATE TABLE human_follow_up_ownership_events (
    tenant_id UUID NOT NULL,
    work_item_id UUID NOT NULL,
    ownership_revision BIGINT NOT NULL,
    event_type TEXT NOT NULL,
    claim_id UUID NOT NULL,
    resolver_actor_id UUID NOT NULL,
    authority_evidence_id UUID NOT NULL,
    authority TEXT GENERATED ALWAYS AS ('RESOLVER') STORED,
    occurred_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT pk_human_follow_up_ownership_events
        PRIMARY KEY (tenant_id, work_item_id, ownership_revision),
    CONSTRAINT fk_human_follow_up_ownership_events_work_item
        FOREIGN KEY (tenant_id, work_item_id)
        REFERENCES human_follow_up_work_items (tenant_id, work_item_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_human_follow_up_ownership_events_authority
        FOREIGN KEY (tenant_id, authority_evidence_id, resolver_actor_id, authority)
        REFERENCES approval_authority_evidence (tenant_id, evidence_id, actor_id, authority)
        ON DELETE RESTRICT,
    CONSTRAINT ck_human_follow_up_ownership_events_revision
        CHECK (ownership_revision > 0),
    CONSTRAINT ck_human_follow_up_ownership_events_type
        CHECK (event_type IN ('CLAIMED', 'RELEASED'))
);

CREATE UNIQUE INDEX uq_human_follow_up_ownership_events_claim
    ON human_follow_up_ownership_events (tenant_id, claim_id)
    WHERE event_type = 'CLAIMED';

CREATE UNIQUE INDEX uq_human_follow_up_ownership_events_release
    ON human_follow_up_ownership_events (tenant_id, claim_id)
    WHERE event_type = 'RELEASED';

CREATE FUNCTION prevent_human_follow_up_ownership_event_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'human follow-up ownership events are immutable; append a transition instead of using %',
        TG_OP
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_human_follow_up_ownership_events_prevent_mutation
BEFORE UPDATE OR DELETE
ON human_follow_up_ownership_events
FOR EACH ROW
EXECUTE FUNCTION prevent_human_follow_up_ownership_event_mutation();

CREATE TABLE human_follow_up_current_ownership (
    tenant_id UUID NOT NULL,
    work_item_id UUID NOT NULL,
    ownership_revision BIGINT NOT NULL,
    current_claim_id UUID,
    current_resolver_actor_id UUID,
    current_claimed_at TIMESTAMPTZ,

    CONSTRAINT pk_human_follow_up_current_ownership
        PRIMARY KEY (tenant_id, work_item_id),
    CONSTRAINT fk_human_follow_up_current_ownership_event
        FOREIGN KEY (tenant_id, work_item_id, ownership_revision)
        REFERENCES human_follow_up_ownership_events (
            tenant_id, work_item_id, ownership_revision
        )
        ON DELETE RESTRICT,
    CONSTRAINT ck_human_follow_up_current_ownership_claim
        CHECK (
            (current_claim_id IS NULL
                AND current_resolver_actor_id IS NULL
                AND current_claimed_at IS NULL)
            OR
            (current_claim_id IS NOT NULL
                AND current_resolver_actor_id IS NOT NULL
                AND current_claimed_at IS NOT NULL)
        )
);

CREATE INDEX ix_human_follow_up_current_ownership_resolver_claimed_at
    ON human_follow_up_current_ownership (
        tenant_id, current_resolver_actor_id, current_claimed_at, current_claim_id
    )
    WHERE current_claim_id IS NOT NULL;

CREATE FUNCTION mirror_human_follow_up_first_claim()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO human_follow_up_ownership_events (
        tenant_id, work_item_id, ownership_revision, event_type, claim_id,
        resolver_actor_id, authority_evidence_id, occurred_at, recorded_at
    ) VALUES (
        NEW.tenant_id, NEW.work_item_id, 1, 'CLAIMED', NEW.claim_id,
        NEW.resolver_actor_id, NEW.authority_evidence_id, NEW.claimed_at,
        NEW.recorded_at
    );

    INSERT INTO human_follow_up_current_ownership (
        tenant_id, work_item_id, ownership_revision, current_claim_id,
        current_resolver_actor_id, current_claimed_at
    ) VALUES (
        NEW.tenant_id, NEW.work_item_id, 1, NEW.claim_id,
        NEW.resolver_actor_id, NEW.claimed_at
    );

    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_human_follow_up_claims_mirror_first_claim
AFTER INSERT
ON human_follow_up_claims
FOR EACH ROW
EXECUTE FUNCTION mirror_human_follow_up_first_claim();

INSERT INTO human_follow_up_ownership_events (
    tenant_id, work_item_id, ownership_revision, event_type, claim_id,
    resolver_actor_id, authority_evidence_id, occurred_at, recorded_at
)
SELECT
    tenant_id, work_item_id, 1, 'CLAIMED', claim_id,
    resolver_actor_id, authority_evidence_id, claimed_at, recorded_at
FROM human_follow_up_claims;

INSERT INTO human_follow_up_current_ownership (
    tenant_id, work_item_id, ownership_revision, current_claim_id,
    current_resolver_actor_id, current_claimed_at
)
SELECT
    tenant_id, work_item_id, 1, claim_id, resolver_actor_id, claimed_at
FROM human_follow_up_claims;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM human_follow_up_claims claim
        LEFT JOIN human_follow_up_current_ownership ownership
            ON ownership.tenant_id = claim.tenant_id
            AND ownership.work_item_id = claim.work_item_id
        LEFT JOIN human_follow_up_ownership_events event
            ON event.tenant_id = claim.tenant_id
            AND event.work_item_id = claim.work_item_id
            AND event.ownership_revision = 1
        WHERE ownership.current_claim_id IS DISTINCT FROM claim.claim_id
            OR ownership.current_resolver_actor_id IS DISTINCT FROM claim.resolver_actor_id
            OR ownership.current_claimed_at IS DISTINCT FROM claim.claimed_at
            OR event.claim_id IS DISTINCT FROM claim.claim_id
            OR event.authority_evidence_id IS DISTINCT FROM claim.authority_evidence_id
    ) THEN
        RAISE EXCEPTION 'human follow-up ownership backfill does not match first claims';
    END IF;
END;
$$;

COMMENT ON TABLE human_follow_up_ownership_events IS
    'Append-only ownership transitions; first-claim events mirror immutable claim records.';
COMMENT ON COLUMN human_follow_up_ownership_events.ownership_revision IS
    'Monotonic transition number within one tenant-scoped work item; first claim is revision 1.';
COMMENT ON COLUMN human_follow_up_ownership_events.claim_id IS
    'Claim acquired or released by this transition; release does not delete its claim history.';
COMMENT ON TABLE human_follow_up_current_ownership IS
    'Transactional current-ownership projection; no row means never claimed.';
COMMENT ON COLUMN human_follow_up_current_ownership.current_claim_id IS
    'NULL only after release; a present row with NULL owner is currently unclaimed.';
COMMENT ON INDEX ix_human_follow_up_current_ownership_resolver_claimed_at IS
    'Supports tenant and resolver scoped oldest-current-claim pagination.';
