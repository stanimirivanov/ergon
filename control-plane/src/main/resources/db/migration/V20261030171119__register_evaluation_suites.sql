-- Additive immutable gate inputs. No active-policy selection or promotion is recorded here.
-- New tables only: no historical backfill, scans or rewrites. Insert document and fixture links atomically.
CREATE TABLE evaluation_gate_documents (
    tenant_id UUID NOT NULL,
    kind TEXT NOT NULL,
    document_key TEXT NOT NULL,
    revision TEXT NOT NULL,
    digest TEXT NOT NULL,
    definition TEXT NOT NULL,
    submitted_by UUID NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    -- POLICY has no parent/contract. SUITE must name one exact POLICY and published contract revision.
    policy_kind TEXT,
    policy_key TEXT,
    policy_revision TEXT,
    policy_digest TEXT,
    contract_key TEXT,
    contract_revision INTEGER,
    CONSTRAINT pk_evaluation_gate_documents PRIMARY KEY (tenant_id, kind, document_key, revision),
    CONSTRAINT uq_evaluation_gate_document_digest UNIQUE (tenant_id, kind, document_key, revision, digest),
    CONSTRAINT fk_evaluation_gate_actor FOREIGN KEY (tenant_id, submitted_by)
        REFERENCES human_actors (tenant_id, actor_id) ON DELETE RESTRICT,
    CONSTRAINT fk_evaluation_gate_policy FOREIGN KEY (tenant_id, policy_kind, policy_key, policy_revision, policy_digest)
        REFERENCES evaluation_gate_documents (tenant_id, kind, document_key, revision, digest) ON DELETE RESTRICT,
    CONSTRAINT fk_evaluation_gate_contract FOREIGN KEY (tenant_id, contract_key, contract_revision)
        REFERENCES resolution_contract_revisions (tenant_id, contract_key, revision) ON DELETE RESTRICT,
    CONSTRAINT ck_evaluation_gate_identity CHECK (
        kind IN ('POLICY', 'SUITE') AND document_key ~ '^[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}$'
        AND revision ~ '^[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}$'
    ),
    CONSTRAINT ck_evaluation_gate_digest CHECK (
        digest ~ '^[0-9a-f]{64}$' AND encode(sha256(convert_to(definition, 'UTF8')), 'hex') = digest
    ),
    CONSTRAINT ck_evaluation_gate_definition CHECK (
        octet_length(definition) BETWEEN 1 AND 131072
        AND jsonb_typeof(definition::JSONB) = 'object'
        AND (definition::JSONB ->> 'schemaVersion') IS NOT NULL
        AND (definition::JSONB ->> 'schemaVersion') = CASE kind
            WHEN 'POLICY' THEN 'ergon.simulation/gate-policy/v1' ELSE 'ergon.simulation/suite/v1' END
    ),
    CONSTRAINT ck_evaluation_gate_references CHECK (
        (kind = 'POLICY' AND policy_kind IS NULL AND policy_key IS NULL AND policy_revision IS NULL
            AND policy_digest IS NULL AND contract_key IS NULL AND contract_revision IS NULL)
        OR (kind = 'SUITE' AND policy_kind IS NOT NULL AND policy_kind = 'POLICY' AND policy_key IS NOT NULL
            AND policy_revision IS NOT NULL AND policy_digest IS NOT NULL
            AND contract_key IS NOT NULL AND contract_revision IS NOT NULL
            AND definition::JSONB ? 'policy' AND definition::JSONB ? 'contract'
            AND (definition::JSONB -> 'policy') = jsonb_build_object(
                'key', policy_key, 'revision', policy_revision, 'sha256', policy_digest)
            AND (definition::JSONB -> 'contract' ->> 'key') IS NOT NULL
            AND (definition::JSONB -> 'contract' ->> 'key') = contract_key
            AND (definition::JSONB -> 'contract' ->> 'revision') IS NOT NULL
            AND (definition::JSONB -> 'contract' ->> 'revision') = contract_revision::TEXT)
    )
);
CREATE INDEX ix_evaluation_gate_actor ON evaluation_gate_documents (tenant_id, submitted_by);
CREATE INDEX ix_evaluation_gate_policy
    ON evaluation_gate_documents (tenant_id, policy_kind, policy_key, policy_revision, policy_digest);
CREATE INDEX ix_evaluation_gate_contract ON evaluation_gate_documents (tenant_id, contract_key, contract_revision);
CREATE TRIGGER trg_evaluation_gate_immutable BEFORE UPDATE OR DELETE ON evaluation_gate_documents
FOR EACH ROW EXECUTE FUNCTION reject_simulation_fixture_mutation();

CREATE TABLE evaluation_suite_fixtures (
    tenant_id UUID NOT NULL,
    suite_kind TEXT NOT NULL,
    suite_key TEXT NOT NULL,
    suite_revision TEXT NOT NULL,
    fixture_key TEXT NOT NULL,
    fixture_revision TEXT NOT NULL,
    fixture_digest TEXT NOT NULL,
    CONSTRAINT pk_evaluation_suite_fixtures PRIMARY KEY (tenant_id, suite_key, suite_revision, fixture_key, fixture_revision),
    CONSTRAINT ck_evaluation_suite_kind CHECK (suite_kind = 'SUITE'),
    CONSTRAINT fk_evaluation_suite_document FOREIGN KEY (tenant_id, suite_kind, suite_key, suite_revision)
        REFERENCES evaluation_gate_documents (tenant_id, kind, document_key, revision) ON DELETE RESTRICT,
    CONSTRAINT fk_evaluation_suite_fixture FOREIGN KEY (tenant_id, fixture_key, fixture_revision, fixture_digest)
        REFERENCES simulation_fixture_revisions (tenant_id, fixture_key, revision, digest) ON DELETE RESTRICT
);
CREATE INDEX ix_evaluation_suite_fixture
    ON evaluation_suite_fixtures (tenant_id, fixture_key, fixture_revision, fixture_digest);
CREATE INDEX ix_evaluation_suite_document
    ON evaluation_suite_fixtures (tenant_id, suite_kind, suite_key, suite_revision);
CREATE TRIGGER trg_evaluation_suite_fixture_immutable BEFORE UPDATE OR DELETE ON evaluation_suite_fixtures
FOR EACH ROW EXECUTE FUNCTION reject_simulation_fixture_mutation();
COMMENT ON TABLE evaluation_gate_documents IS
    'Immutable reviewed suite/policy inputs; recording them grants no promotion or runtime authority.';
COMMENT ON TABLE evaluation_suite_fixtures IS
    'Exact tenant-owned fixture references derived from suite definitions in the same registration transaction.';
