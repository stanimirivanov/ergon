-- A single terminal review of an exact immutable proposal. Nullable target columns select exactly one typed FK.
CREATE TABLE evidence_proposal_reviews (
    tenant_id UUID NOT NULL,
    proposal_kind TEXT NOT NULL,
    proposal_id UUID NOT NULL,
    claim_id UUID,
    procedure_id UUID,
    command_id UUID NOT NULL,
    extraction_id UUID NOT NULL,
    review_version BIGINT NOT NULL DEFAULT 1,
    decision TEXT NOT NULL,
    rationale TEXT NOT NULL,
    reviewer_id UUID NOT NULL,
    authority_evidence_id UUID NOT NULL,
    reviewed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_evidence_proposal_reviews PRIMARY KEY (tenant_id, proposal_kind, proposal_id),
    CONSTRAINT uq_evidence_review_command UNIQUE (tenant_id, command_id),
    CONSTRAINT fk_review_claim FOREIGN KEY (tenant_id, claim_id)
        REFERENCES evidence_claim_proposals (tenant_id, proposal_id) ON DELETE RESTRICT,
    CONSTRAINT fk_review_procedure FOREIGN KEY (tenant_id, procedure_id)
        REFERENCES evidence_procedure_proposals (tenant_id, proposal_id) ON DELETE RESTRICT,
    CONSTRAINT fk_review_reviewer FOREIGN KEY (tenant_id, reviewer_id)
        REFERENCES human_actors (tenant_id, actor_id) ON DELETE RESTRICT,
    CONSTRAINT fk_review_authority FOREIGN KEY (tenant_id, authority_evidence_id)
        REFERENCES approval_authority_evidence (tenant_id, evidence_id) ON DELETE RESTRICT,
    CONSTRAINT ck_review_target CHECK (
        (proposal_kind = 'CLAIM' AND claim_id IS NOT NULL AND claim_id = proposal_id AND procedure_id IS NULL)
        OR (proposal_kind = 'PROCEDURE' AND procedure_id IS NOT NULL AND procedure_id = proposal_id AND claim_id IS NULL)),
    CONSTRAINT ck_review_decision CHECK (review_version = 1 AND decision IN ('ACCEPTED', 'REJECTED')),
    CONSTRAINT ck_review_rationale CHECK (char_length(rationale) BETWEEN 1 AND 2000 AND btrim(rationale) <> '')
);

CREATE FUNCTION admit_evidence_proposal_review() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    selected_extraction UUID;
BEGIN
    -- Serialize even direct SQL producers with the proposal's other review commands.
    IF NEW.proposal_kind = 'CLAIM' THEN
        SELECT extraction_id INTO selected_extraction FROM evidence_claim_proposals
        WHERE tenant_id = NEW.tenant_id AND proposal_id = NEW.proposal_id FOR UPDATE;
    ELSE
        SELECT extraction_id INTO selected_extraction FROM evidence_procedure_proposals
        WHERE tenant_id = NEW.tenant_id AND proposal_id = NEW.proposal_id FOR UPDATE;
    END IF;
    IF selected_extraction IS NULL OR selected_extraction <> NEW.extraction_id THEN
        RAISE EXCEPTION 'review target extraction mismatch' USING ERRCODE = 'P7502';
    END IF;
    NEW.reviewed_at := clock_timestamp();
    IF NOT EXISTS (
        SELECT 1 FROM approval_authority_evidence authority
        WHERE authority.tenant_id = NEW.tenant_id AND authority.evidence_id = NEW.authority_evidence_id
            AND authority.actor_id = NEW.reviewer_id AND authority.authority = 'RESOLVER' AND authority.case_id IS NULL
            AND authority.attested_at <= NEW.reviewed_at AND authority.expires_at > NEW.reviewed_at
    ) THEN
        RAISE EXCEPTION 'current resolver authority required for review' USING ERRCODE = 'P7501';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_evidence_review_admission BEFORE INSERT ON evidence_proposal_reviews
FOR EACH ROW EXECUTE FUNCTION admit_evidence_proposal_review();

CREATE FUNCTION reject_evidence_review_mutation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'evidence review decisions are immutable' USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_evidence_review_immutable BEFORE UPDATE OR DELETE ON evidence_proposal_reviews
FOR EACH ROW EXECUTE FUNCTION reject_evidence_review_mutation();

-- Shared ordering dimensions support bounded keyset scans in each pending-proposal branch.
CREATE INDEX ix_claim_review_queue ON evidence_claim_proposals (tenant_id, recorded_at DESC, proposal_id DESC);
CREATE INDEX ix_procedure_review_queue ON evidence_procedure_proposals (tenant_id, recorded_at DESC, proposal_id DESC);
