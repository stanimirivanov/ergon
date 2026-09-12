-- ============================================================================
-- Immutable, tenant-scoped resolution contract revisions
-- ============================================================================
--
-- Each row stores the normalized meaning produced by one supported contract
-- document schema. The relational identity and schema identifier select the
-- decoder; the JSONB definition keeps schema-versioned fields together without
-- turning author-supplied YAML syntax into runtime state.
--
-- Revisions are configuration history, not an event stream. They are inserted
-- once and cannot be updated or deleted through ordinary database writes.
-- ============================================================================


CREATE TABLE resolution_contract_revisions (
    tenant_id UUID NOT NULL,
    contract_key TEXT NOT NULL,
    revision INTEGER NOT NULL,
    schema_id TEXT NOT NULL,
    definition JSONB NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT pk_resolution_contract_revisions
        PRIMARY KEY (tenant_id, contract_key, revision),

    CONSTRAINT ck_resolution_contract_revisions_key
        CHECK (
            char_length(contract_key) BETWEEN 1 AND 100
            AND contract_key ~ '^[a-z0-9][a-z0-9-]{0,99}$'
        ),

    CONSTRAINT ck_resolution_contract_revisions_revision_positive
        CHECK (revision > 0),

    -- This closed set selects the durable JSON decoder. Adding a schema requires
    -- a paired codec change and migration.
    CONSTRAINT ck_resolution_contract_revisions_schema
        CHECK (schema_id IN ('ergon.dev/resolution-contract/v1alpha1')),

    CONSTRAINT ck_resolution_contract_revisions_definition_object
        CHECK (jsonb_typeof(definition) = 'object')
);


CREATE FUNCTION prevent_resolution_contract_revision_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'resolution contract revisions are immutable; publish a new revision instead of using %',
        TG_OP
        USING ERRCODE = '55000';
END;
$$;


CREATE TRIGGER trg_resolution_contract_revisions_prevent_mutation
BEFORE UPDATE OR DELETE
ON resolution_contract_revisions
FOR EACH ROW
EXECUTE FUNCTION prevent_resolution_contract_revision_mutation();


COMMENT ON TABLE resolution_contract_revisions IS
    'Immutable tenant-scoped history of validated resolution contract revisions.';

COMMENT ON COLUMN resolution_contract_revisions.schema_id IS
    'Compatibility identifier selecting the decoder for the normalized definition.';

COMMENT ON COLUMN resolution_contract_revisions.definition IS
    'Normalized schema-versioned contract meaning; authoring syntax is deliberately not retained.';

COMMENT ON COLUMN resolution_contract_revisions.recorded_at IS
    'Database instant at which this immutable revision was first published.';
