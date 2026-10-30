-- Additive immutable curated simulation packages; no production case/contract FK is intended.
-- Synthetic identities must remain separate from live runtime entities. No existing tables are scanned.
-- Canonical bytes bind the manifest digest; the application verifies all included artifact bytes.
CREATE TABLE simulation_fixture_revisions (
    tenant_id UUID NOT NULL,
    fixture_key TEXT NOT NULL,
    revision TEXT NOT NULL,
    digest TEXT NOT NULL,
    canonical BYTEA NOT NULL,
    definition TEXT NOT NULL,
    submitted_by UUID NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_simulation_fixture_revisions PRIMARY KEY (tenant_id, fixture_key, revision),
    CONSTRAINT fk_simulation_fixture_submitter FOREIGN KEY (tenant_id, submitted_by)
        REFERENCES human_actors (tenant_id, actor_id),
    CONSTRAINT ck_simulation_fixture_key CHECK (fixture_key ~ '^[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}$'),
    CONSTRAINT ck_simulation_fixture_revision CHECK (revision ~ '^[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}$'),
    CONSTRAINT ck_simulation_fixture_digest CHECK (digest ~ '^[0-9a-f]{64}$' AND encode(sha256(canonical), 'hex') = digest),
    CONSTRAINT ck_simulation_fixture_canonical CHECK (octet_length(canonical) BETWEEN 1 AND 65536),
    CONSTRAINT ck_simulation_fixture_definition CHECK (
        jsonb_typeof(definition::JSONB) = 'object'
        AND (definition::JSONB #>> '{manifest,schemaVersion}') IS NOT NULL
        AND (definition::JSONB #>> '{manifest,schemaVersion}') = 'ergon.simulation/v1'
        AND (definition::JSONB #>> '{manifest,tenantId}') IS NOT NULL
        AND (definition::JSONB #>> '{manifest,tenantId}') = tenant_id::TEXT
        AND (definition::JSONB ->> 'digest') IS NOT NULL
        AND (definition::JSONB ->> 'digest') = digest
        AND octet_length(definition) <= 1048576
    )
);

CREATE FUNCTION reject_simulation_fixture_mutation() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'simulation fixture revisions are immutable';
END;
$$;

CREATE TRIGGER trg_simulation_fixture_immutable
BEFORE UPDATE OR DELETE ON simulation_fixture_revisions
FOR EACH ROW EXECUTE FUNCTION reject_simulation_fixture_mutation();

COMMENT ON TABLE simulation_fixture_revisions IS
    'Curated replay packages, not production state or promotion evidence. Original attribution survives retry.';
COMMENT ON COLUMN simulation_fixture_revisions.definition IS
    'Normalized versioned JSON text preserves exact bounded UTF-8 representation; contains inert materials and provenance.';
