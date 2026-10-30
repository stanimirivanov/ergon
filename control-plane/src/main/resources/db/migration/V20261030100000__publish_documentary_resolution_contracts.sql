-- Expand immutable contract storage with exact historical documentary bundle links.
-- The versioned definition remains authoritative; an AFTER INSERT trigger writes
-- its complete relational projection in the same statement. No backfill or rewrite
-- of v1alpha1 definitions. The bundle unique constraint scans existing identities;
-- schedule this short DDL/validation lock before enabling v1alpha2 publication.
-- PR13 must replace the explicit documentary run-start gate when consumption exists.

ALTER TABLE resolution_contract_revisions
    DROP CONSTRAINT ck_resolution_contract_revisions_schema;
ALTER TABLE resolution_contract_revisions
    ADD CONSTRAINT ck_resolution_contract_revisions_schema
    CHECK (schema_id IN ('ergon.dev/resolution-contract/v1alpha1', 'ergon.dev/resolution-contract/v1alpha2'));

ALTER TABLE evidence_bundles
    ADD CONSTRAINT uq_evidence_bundles_exact_manifest UNIQUE (tenant_id, bundle_id, manifest_sha256);

CREATE TABLE resolution_contract_documentary_evidence (
    tenant_id UUID NOT NULL,
    contract_key TEXT NOT NULL,
    contract_revision INTEGER NOT NULL,
    bundle_id UUID NOT NULL,
    manifest_sha256 TEXT NOT NULL,
    CONSTRAINT pk_contract_documentary_evidence PRIMARY KEY (tenant_id, contract_key, contract_revision, bundle_id),
    CONSTRAINT fk_contract_documentary_parent FOREIGN KEY (tenant_id, contract_key, contract_revision)
        REFERENCES resolution_contract_revisions (tenant_id, contract_key, revision) ON DELETE RESTRICT,
    CONSTRAINT fk_contract_documentary_bundle FOREIGN KEY (tenant_id, bundle_id, manifest_sha256)
        REFERENCES evidence_bundles (tenant_id, bundle_id, manifest_sha256) ON DELETE RESTRICT
);

CREATE FUNCTION validate_contract_documentary_definition() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    item JSONB;
    entries JSONB;
BEGIN
    IF NEW.schema_id = 'ergon.dev/resolution-contract/v1alpha1' THEN
        IF NEW.definition ? 'documentaryEvidence' THEN
            RAISE EXCEPTION 'v1alpha1 cannot declare documentary evidence' USING ERRCODE = '23514';
        END IF;
        RETURN NEW;
    END IF;
    entries := NEW.definition->'documentaryEvidence';
    IF entries IS NULL OR JSONB_TYPEOF(entries) <> 'array' THEN
        RAISE EXCEPTION 'v1alpha2 requires documentary evidence' USING ERRCODE = '23514';
    END IF;
    IF JSONB_ARRAY_LENGTH(entries) NOT BETWEEN 1 AND 20 THEN
        RAISE EXCEPTION 'documentary evidence must contain 1-20 references' USING ERRCODE = '23514';
    END IF;
    FOR item IN SELECT value FROM JSONB_ARRAY_ELEMENTS(entries) LOOP
        IF JSONB_TYPEOF(item) <> 'object' THEN
            RAISE EXCEPTION 'documentary reference must be an object' USING ERRCODE = '23514';
        END IF;
        IF NOT (item ?& ARRAY['bundleId', 'sha256']) OR item - ARRAY['bundleId', 'sha256'] <> '{}'::JSONB
            OR JSONB_TYPEOF(item->'bundleId') <> 'string' OR JSONB_TYPEOF(item->'sha256') <> 'string'
            OR (item->>'bundleId') !~ '^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$'
            OR (item->>'sha256') !~ '^[a-f0-9]{64}$' THEN
            RAISE EXCEPTION 'invalid exact documentary reference' USING ERRCODE = '23514';
        END IF;
    END LOOP;
    IF (SELECT COUNT(DISTINCT value->>'bundleId') FROM JSONB_ARRAY_ELEMENTS(entries)) <> JSONB_ARRAY_LENGTH(entries) THEN
        RAISE EXCEPTION 'documentary bundle identities must be unique' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_validate_contract_documentary_definition BEFORE INSERT ON resolution_contract_revisions
FOR EACH ROW EXECUTE FUNCTION validate_contract_documentary_definition();

CREATE FUNCTION project_contract_documentary_evidence() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.schema_id = 'ergon.dev/resolution-contract/v1alpha2' THEN
        INSERT INTO resolution_contract_documentary_evidence (
            tenant_id, contract_key, contract_revision, bundle_id, manifest_sha256
        )
        SELECT NEW.tenant_id, NEW.contract_key, NEW.revision, (value->>'bundleId')::UUID, value->>'sha256'
        FROM JSONB_ARRAY_ELEMENTS(NEW.definition->'documentaryEvidence');
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_project_contract_documentary_evidence AFTER INSERT ON resolution_contract_revisions
FOR EACH ROW EXECUTE FUNCTION project_contract_documentary_evidence();

-- Automatic projection guarantees completeness. This guard and the primary key
-- also forbid later additions/replacements through direct child INSERTs.
CREATE FUNCTION validate_contract_documentary_link() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM resolution_contract_revisions c
        CROSS JOIN LATERAL JSONB_ARRAY_ELEMENTS(c.definition->'documentaryEvidence') AS e(item)
        WHERE c.tenant_id = NEW.tenant_id AND c.contract_key = NEW.contract_key AND c.revision = NEW.contract_revision
            AND c.schema_id = 'ergon.dev/resolution-contract/v1alpha2'
            AND e.item->>'bundleId' = NEW.bundle_id::TEXT AND e.item->>'sha256' = NEW.manifest_sha256
    ) THEN
        RAISE EXCEPTION 'documentary link must match its immutable definition' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_validate_contract_documentary_link BEFORE INSERT ON resolution_contract_documentary_evidence
FOR EACH ROW EXECUTE FUNCTION validate_contract_documentary_link();
CREATE TRIGGER trg_contract_documentary_evidence_immutable BEFORE UPDATE OR DELETE ON resolution_contract_documentary_evidence
FOR EACH ROW EXECUTE FUNCTION prevent_resolution_contract_revision_mutation();

CREATE FUNCTION reject_unsupported_documentary_run() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM resolution_contract_revisions
        WHERE tenant_id = NEW.tenant_id AND contract_key = NEW.contract_key AND revision = NEW.contract_revision
            AND schema_id = 'ergon.dev/resolution-contract/v1alpha2'
    ) THEN
        RAISE EXCEPTION 'documentary evidence consumption is not supported' USING ERRCODE = '0A000';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_reject_unsupported_documentary_run BEFORE INSERT ON resolution_runs
FOR EACH ROW EXECUTE FUNCTION reject_unsupported_documentary_run();

COMMENT ON TABLE resolution_contract_documentary_evidence IS
    'Exact immutable bundle requirements, projected atomically from v1alpha2 contract definitions; not case evidence or execution authority.';
COMMENT ON COLUMN resolution_contract_documentary_evidence.manifest_sha256 IS
    'SHA-256 of the exact canonical historical manifest, enforced with the tenant and bundle identity by a composite foreign key.';
