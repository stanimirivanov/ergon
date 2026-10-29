-- The nested definition is independently versioned; operation-source links retain relational tenant-safe FKs.
CREATE TABLE evidence_procedure_proposals (
    tenant_id UUID NOT NULL,
    proposal_id UUID NOT NULL,
    source_id UUID NOT NULL,
    digest TEXT NOT NULL,
    extraction_id UUID NOT NULL,
    compiler_revision TEXT NOT NULL,
    observation_index INTEGER NOT NULL,
    title TEXT NOT NULL,
    step_count INTEGER NOT NULL,
    definition_schema TEXT NOT NULL DEFAULT 'ergon.dev/procedure-definition/v1',
    definition JSONB NOT NULL,
    binding_rule TEXT NOT NULL DEFAULT 'ordered-list-procedure-v1',
    proposed_by UUID NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_procedure_proposals PRIMARY KEY (tenant_id, proposal_id),
    CONSTRAINT fk_procedure_source FOREIGN KEY (tenant_id, source_id, digest)
        REFERENCES knowledge_source_revisions (tenant_id, source_id, digest),
    CONSTRAINT fk_procedure_proposer FOREIGN KEY (tenant_id, proposed_by) REFERENCES human_actors (tenant_id, actor_id),
    CONSTRAINT ck_procedure_metadata CHECK (observation_index >= 0 AND char_length(title) BETWEEN 1 AND 256
        AND step_count BETWEEN 1 AND 20 AND compiler_revision ~ '^[a-z0-9][a-z0-9._-]{0,99}$'),
    CONSTRAINT ck_procedure_definition CHECK (definition_schema = 'ergon.dev/procedure-definition/v1'
        AND binding_rule = 'ordered-list-procedure-v1' AND jsonb_typeof(definition) = 'object'
        AND definition ? 'citation' AND jsonb_typeof(definition->'citation') = 'object' AND definition ? 'steps'
        AND jsonb_typeof(definition->'steps') = 'array' AND jsonb_array_length(definition->'steps') = step_count
        AND octet_length(definition::TEXT) <= 2097152)
);

CREATE TABLE evidence_procedure_operation_sources (
    tenant_id UUID NOT NULL,
    proposal_id UUID NOT NULL,
    step_position INTEGER NOT NULL,
    source_id UUID NOT NULL,
    digest TEXT NOT NULL,
    CONSTRAINT pk_procedure_operation_sources PRIMARY KEY (tenant_id, proposal_id, step_position),
    CONSTRAINT fk_procedure_operation_parent FOREIGN KEY (tenant_id, proposal_id)
        REFERENCES evidence_procedure_proposals (tenant_id, proposal_id),
    CONSTRAINT fk_procedure_operation_source FOREIGN KEY (tenant_id, source_id, digest)
        REFERENCES knowledge_source_revisions (tenant_id, source_id, digest),
    CONSTRAINT ck_procedure_operation_position CHECK (step_position BETWEEN 1 AND 20)
);

CREATE FUNCTION reject_procedure_proposal_mutation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'procedure proposals and operation links are immutable';
END;
$$;

CREATE TRIGGER trg_procedure_immutable BEFORE UPDATE OR DELETE ON evidence_procedure_proposals
FOR EACH ROW EXECUTE FUNCTION reject_procedure_proposal_mutation();
CREATE TRIGGER trg_procedure_operation_immutable BEFORE UPDATE OR DELETE ON evidence_procedure_operation_sources
FOR EACH ROW EXECUTE FUNCTION reject_procedure_proposal_mutation();

-- Deferred checks let parent and links be inserted atomically, but forbid missing or later-added links.
CREATE FUNCTION validate_procedure_operation_sources() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    payload JSONB;
    inconsistent BOOLEAN;
BEGIN
    SELECT definition INTO STRICT payload FROM evidence_procedure_proposals
    WHERE tenant_id = NEW.tenant_id AND proposal_id = NEW.proposal_id;
    WITH expected AS (
        SELECT position::INTEGER AS step_position, (item->'operation'->>'sourceId')::UUID AS source_id,
            item->'operation'->>'digest' AS digest
        FROM jsonb_array_elements(payload->'steps') WITH ORDINALITY AS step(item, position)
        WHERE item->'operation' IS NOT NULL AND item->'operation' <> 'null'::JSONB
    ), actual AS (
        SELECT step_position, source_id, digest FROM evidence_procedure_operation_sources
        WHERE tenant_id = NEW.tenant_id AND proposal_id = NEW.proposal_id
    ), differences AS (
        (SELECT * FROM expected EXCEPT SELECT * FROM actual)
        UNION ALL
        (SELECT * FROM actual EXCEPT SELECT * FROM expected)
    )
    SELECT EXISTS (SELECT 1 FROM differences) INTO inconsistent;
    IF inconsistent THEN
        RAISE EXCEPTION 'procedure operation links must match the immutable definition';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_procedure_links_complete AFTER INSERT ON evidence_procedure_proposals
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_procedure_operation_sources();
CREATE CONSTRAINT TRIGGER trg_procedure_link_consistent AFTER INSERT ON evidence_procedure_operation_sources
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_procedure_operation_sources();
