-- ============================================================================
-- Immutable resolver ownership of human follow-up work
-- ============================================================================
--
-- Each row records the first authenticated resolver to claim one open work
-- item and the exact current tenant-wide RESOLVER attestation accepted by the
-- application. The unique work-item key makes first-writer ownership durable;
-- application locking gives competing requests deterministic conflict or
-- replay behavior before insertion.
--
-- Claims do not mutate the immutable creation snapshot or change its OPEN
-- lifecycle state. Release, reassignment, completion, priority, and named
-- queues require later attributable transitions and are deliberately absent.
-- ============================================================================


CREATE TABLE human_follow_up_claims (
    tenant_id UUID NOT NULL,
    claim_id UUID NOT NULL,
    work_item_id UUID NOT NULL,
    resolver_actor_id UUID NOT NULL,
    authority_evidence_id UUID NOT NULL,
    authority TEXT
        GENERATED ALWAYS AS ('RESOLVER') STORED,
    claimed_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT pk_human_follow_up_claims
        PRIMARY KEY (tenant_id, claim_id),

    CONSTRAINT uq_human_follow_up_claims_work_item
        UNIQUE (tenant_id, work_item_id),

    CONSTRAINT fk_human_follow_up_claims_work_item
        FOREIGN KEY (tenant_id, work_item_id)
        REFERENCES human_follow_up_work_items (tenant_id, work_item_id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_human_follow_up_claims_authority
        FOREIGN KEY (
            tenant_id, authority_evidence_id, resolver_actor_id, authority
        )
        REFERENCES approval_authority_evidence (
            tenant_id, evidence_id, actor_id, authority
        )
        ON DELETE RESTRICT
);


CREATE INDEX ix_human_follow_up_claims_resolver_claimed_at
    ON human_follow_up_claims (tenant_id, resolver_actor_id, claimed_at, claim_id);


CREATE FUNCTION prevent_human_follow_up_claim_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'human follow-up claims are immutable; append a later ownership transition instead of using %',
        TG_OP
        USING ERRCODE = '55000';
END;
$$;


CREATE TRIGGER trg_human_follow_up_claims_prevent_mutation
BEFORE UPDATE OR DELETE
ON human_follow_up_claims
FOR EACH ROW
EXECUTE FUNCTION prevent_human_follow_up_claim_mutation();


COMMENT ON TABLE human_follow_up_claims IS
    'Immutable first-resolver ownership records for open human follow-up work.';

COMMENT ON COLUMN human_follow_up_claims.work_item_id IS
    'Open work item claimed at most once in this ownership lifecycle version.';

COMMENT ON COLUMN human_follow_up_claims.resolver_actor_id IS
    'Authenticated human actor who acquired ownership of the work item.';

COMMENT ON COLUMN human_follow_up_claims.authority_evidence_id IS
    'Current tenant-wide RESOLVER attestation used to authorize the claim.';

COMMENT ON COLUMN human_follow_up_claims.authority IS
    'Generated RESOLVER discriminator binding claim attribution to matching evidence.';

COMMENT ON COLUMN human_follow_up_claims.claimed_at IS
    'Application-clock instant at which ownership was acquired.';

COMMENT ON COLUMN human_follow_up_claims.recorded_at IS
    'Database instant at which the immutable claim became durable.';

COMMENT ON INDEX ix_human_follow_up_work_items_open_inbox IS
    'Supports tenant-scoped oldest-first candidates for the unclaimed OPEN resolver inbox.';
