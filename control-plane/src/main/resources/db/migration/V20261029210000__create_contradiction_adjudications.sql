-- Mutable concurrency projection only. Immutable accepted reviews remain the source of candidate membership.
CREATE TABLE evidence_claim_set_revisions (
    tenant_id UUID NOT NULL,
    property TEXT NOT NULL,
    subject TEXT NOT NULL,
    scope TEXT NOT NULL,
    revision BIGINT NOT NULL,
    CONSTRAINT pk_claim_set_revisions PRIMARY KEY (tenant_id, property, subject, scope),
    CONSTRAINT ck_claim_set_revision CHECK (revision > 0)
);

-- Prevent acceptance between the backfill snapshot and trigger installation.
LOCK TABLE evidence_proposal_reviews IN SHARE ROW EXCLUSIVE MODE;

INSERT INTO evidence_claim_set_revisions (tenant_id, property, subject, scope, revision)
SELECT p.tenant_id, p.property, p.subject, p.scope, COUNT(*)
FROM evidence_claim_proposals p
JOIN evidence_proposal_reviews r ON r.tenant_id = p.tenant_id AND r.proposal_id = p.proposal_id
    AND r.proposal_kind = 'CLAIM' AND r.decision = 'ACCEPTED'
GROUP BY p.tenant_id, p.property, p.subject, p.scope;

CREATE FUNCTION advance_accepted_claim_set() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.proposal_kind = 'CLAIM' AND NEW.decision = 'ACCEPTED' THEN
        INSERT INTO evidence_claim_set_revisions (tenant_id, property, subject, scope, revision)
        SELECT tenant_id, property, subject, scope, 1 FROM evidence_claim_proposals
        WHERE tenant_id = NEW.tenant_id AND proposal_id = NEW.proposal_id
        ON CONFLICT (tenant_id, property, subject, scope)
        DO UPDATE SET revision = evidence_claim_set_revisions.revision + 1;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_accepted_claim_set AFTER INSERT ON evidence_proposal_reviews
FOR EACH ROW EXECUTE FUNCTION advance_accepted_claim_set();

CREATE TABLE evidence_contradiction_adjudications (
    tenant_id UUID NOT NULL,
    command_id UUID NOT NULL,
    anchor_id UUID NOT NULL,
    property TEXT NOT NULL,
    subject TEXT NOT NULL,
    scope TEXT NOT NULL,
    set_revision BIGINT NOT NULL,
    candidate_ids UUID[] NOT NULL,
    review_command_ids UUID[] NOT NULL,
    -- Codes follow canonical candidate-pair order (i,j), i < j. No current winner projection is maintained.
    finding_codes TEXT[] NOT NULL,
    algorithm TEXT NOT NULL DEFAULT 'documentary-contradictions-v1',
    choice TEXT NOT NULL,
    -- NULL means DEFER, never an inferred preferred claim.
    selected_claim_id UUID,
    rationale TEXT NOT NULL,
    reviewer_id UUID NOT NULL,
    authority_evidence_id UUID NOT NULL,
    adjudicated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_contradiction_adjudications PRIMARY KEY (tenant_id, command_id),
    CONSTRAINT uq_contradiction_snapshot UNIQUE (tenant_id, property, subject, scope, set_revision),
    CONSTRAINT fk_contradiction_anchor FOREIGN KEY (tenant_id, anchor_id)
        REFERENCES evidence_claim_proposals (tenant_id, proposal_id) ON DELETE RESTRICT,
    CONSTRAINT fk_contradiction_selected FOREIGN KEY (tenant_id, selected_claim_id)
        REFERENCES evidence_claim_proposals (tenant_id, proposal_id) ON DELETE RESTRICT,
    CONSTRAINT fk_contradiction_set FOREIGN KEY (tenant_id, property, subject, scope)
        REFERENCES evidence_claim_set_revisions (tenant_id, property, subject, scope) ON DELETE RESTRICT,
    CONSTRAINT fk_contradiction_reviewer FOREIGN KEY (tenant_id, reviewer_id)
        REFERENCES human_actors (tenant_id, actor_id) ON DELETE RESTRICT,
    CONSTRAINT fk_contradiction_authority FOREIGN KEY (tenant_id, authority_evidence_id)
        REFERENCES approval_authority_evidence (tenant_id, evidence_id) ON DELETE RESTRICT,
    CONSTRAINT ck_contradiction_revision CHECK (set_revision > 0 AND algorithm = 'documentary-contradictions-v1'),
    CONSTRAINT ck_contradiction_candidates CHECK (
        CARDINALITY(candidate_ids) BETWEEN 2 AND 20
        AND ARRAY_NDIMS(candidate_ids) = 1 AND ARRAY_LOWER(candidate_ids, 1) = 1
        AND ARRAY_POSITION(candidate_ids, NULL) IS NULL AND anchor_id = ANY(candidate_ids)
        AND CARDINALITY(review_command_ids) = CARDINALITY(candidate_ids)
        AND ARRAY_NDIMS(review_command_ids) = 1 AND ARRAY_LOWER(review_command_ids, 1) = 1
        AND ARRAY_POSITION(review_command_ids, NULL) IS NULL),
    CONSTRAINT ck_contradiction_findings CHECK (
        CARDINALITY(finding_codes) = CARDINALITY(candidate_ids) * (CARDINALITY(candidate_ids) - 1) / 2
        AND ARRAY_NDIMS(finding_codes) = 1 AND ARRAY_LOWER(finding_codes, 1) = 1
        AND ARRAY_POSITION(finding_codes, NULL) IS NULL
        AND finding_codes <@ ARRAY['NOT_COMPARABLE', 'DISJOINT_VALIDITY', 'COMPATIBLE', 'CONFLICT', 'UNDETERMINED']
        AND finding_codes && ARRAY['CONFLICT', 'UNDETERMINED']),
    CONSTRAINT ck_contradiction_choice CHECK (
        (choice = 'DEFER' AND selected_claim_id IS NULL)
        OR (choice = 'PREFER_CLAIM' AND selected_claim_id IS NOT NULL AND selected_claim_id = ANY(candidate_ids))),
    CONSTRAINT ck_contradiction_rationale CHECK (CHAR_LENGTH(rationale) BETWEEN 1 AND 2000 AND BTRIM(rationale) <> '')
);

CREATE FUNCTION admit_contradiction_adjudication() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    current_revision BIGINT;
    current_candidates UUID[];
    current_reviews UUID[];
BEGIN
    SELECT revision INTO current_revision FROM evidence_claim_set_revisions
    WHERE tenant_id = NEW.tenant_id AND property = NEW.property AND subject = NEW.subject AND scope = NEW.scope
    FOR UPDATE;
    SELECT ARRAY_AGG(p.proposal_id ORDER BY p.proposal_id), ARRAY_AGG(r.command_id ORDER BY p.proposal_id)
    INTO current_candidates, current_reviews
    FROM evidence_claim_proposals p
    JOIN evidence_proposal_reviews r ON r.tenant_id = p.tenant_id AND r.proposal_id = p.proposal_id
        AND r.proposal_kind = 'CLAIM' AND r.decision = 'ACCEPTED'
    WHERE p.tenant_id = NEW.tenant_id AND p.property = NEW.property AND p.subject = NEW.subject AND p.scope = NEW.scope;
    IF current_revision IS DISTINCT FROM NEW.set_revision
        OR current_candidates IS DISTINCT FROM NEW.candidate_ids
        OR current_reviews IS DISTINCT FROM NEW.review_command_ids THEN
        RAISE EXCEPTION 'stale contradiction candidates' USING ERRCODE = 'P7602';
    END IF;
    NEW.adjudicated_at := clock_timestamp();
    IF NOT EXISTS (
        SELECT 1 FROM approval_authority_evidence a
        WHERE a.tenant_id = NEW.tenant_id AND a.evidence_id = NEW.authority_evidence_id
            AND a.actor_id = NEW.reviewer_id AND a.authority = 'RESOLVER' AND a.case_id IS NULL
            AND a.attested_at <= NEW.adjudicated_at AND a.expires_at > NEW.adjudicated_at
    ) THEN
        RAISE EXCEPTION 'current resolver authority required' USING ERRCODE = 'P7601';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_contradiction_admission BEFORE INSERT ON evidence_contradiction_adjudications
FOR EACH ROW EXECUTE FUNCTION admit_contradiction_adjudication();

CREATE TRIGGER trg_contradiction_immutable BEFORE UPDATE OR DELETE ON evidence_contradiction_adjudications
FOR EACH ROW EXECUTE FUNCTION reject_evidence_review_mutation();

CREATE INDEX ix_claim_contradiction_set ON evidence_claim_proposals (tenant_id, property, subject, scope, proposal_id);
