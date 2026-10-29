-- Documentary proposals, not operational evidence or authority. Unknown validity has both bounds NULL.
CREATE TABLE evidence_claim_proposals (
    tenant_id UUID NOT NULL,
    proposal_id UUID NOT NULL,
    source_id UUID NOT NULL,
    digest TEXT NOT NULL,
    extraction_id UUID NOT NULL,
    compiler_revision TEXT NOT NULL,
    observation_index INTEGER NOT NULL,
    span_start INTEGER NOT NULL,
    span_end INTEGER NOT NULL,
    quote BYTEA NOT NULL,
    property TEXT NOT NULL,
    boolean_value BOOLEAN,
    text_value TEXT,
    subject TEXT NOT NULL,
    scope TEXT NOT NULL,
    valid_from TIMESTAMPTZ,
    valid_until TIMESTAMPTZ,
    binding_rule TEXT NOT NULL DEFAULT 'manual-claim-v1',
    proposed_by UUID NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_evidence_claim_proposals PRIMARY KEY (tenant_id, proposal_id),
    CONSTRAINT fk_claim_source FOREIGN KEY (tenant_id, source_id, digest)
        REFERENCES knowledge_source_revisions (tenant_id, source_id, digest),
    CONSTRAINT fk_claim_proposer FOREIGN KEY (tenant_id, proposed_by) REFERENCES human_actors (tenant_id, actor_id),
    CONSTRAINT ck_claim_type CHECK (
        (property = 'SUPPORT' AND boolean_value IS NOT NULL AND text_value IS NULL)
        OR (property = 'REQUIREMENT' AND boolean_value IS NULL AND text_value IS NOT NULL
            AND char_length(text_value) BETWEEN 1 AND 1000)),
    CONSTRAINT ck_claim_scope CHECK (char_length(subject) BETWEEN 1 AND 256 AND char_length(scope) BETWEEN 1 AND 256),
    CONSTRAINT ck_claim_validity CHECK ((valid_from IS NULL AND valid_until IS NULL)
        OR (valid_from IS NOT NULL AND valid_until IS NOT NULL AND valid_from < valid_until
            AND valid_from >= TIMESTAMPTZ '0001-01-01 00:00:00+00'
            AND valid_until <= TIMESTAMPTZ '9999-12-31 23:59:59.999999+00')),
    CONSTRAINT ck_claim_citation CHECK (observation_index >= 0 AND span_start >= 0 AND span_end > span_start
        AND octet_length(quote) BETWEEN 1 AND 65536),
    CONSTRAINT ck_claim_compiler CHECK (compiler_revision ~ '^[a-z0-9][a-z0-9._-]{0,99}$'),
    CONSTRAINT ck_claim_binding CHECK (binding_rule = 'manual-claim-v1')
);

CREATE FUNCTION reject_claim_proposal_mutation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'documentary claim proposals are immutable';
END;
$$;

CREATE TRIGGER trg_claim_proposal_immutable BEFORE UPDATE OR DELETE ON evidence_claim_proposals
FOR EACH ROW EXECUTE FUNCTION reject_claim_proposal_mutation();
