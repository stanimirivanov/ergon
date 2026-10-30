-- Additive completed evaluation receipts. Execution occurs before the short insert transaction.
-- Report semantic text is canonical UTF-8 JSON; recording ID/actor/time are outside its digest.
-- Existing fixture rows are indexed but not rewritten; reports and their source packages are immutable.
ALTER TABLE simulation_fixture_revisions
    ADD CONSTRAINT uq_simulation_fixture_digest UNIQUE (tenant_id, fixture_key, revision, digest);

CREATE TABLE simulation_evaluation_reports (
    tenant_id UUID NOT NULL,
    evaluation_id UUID NOT NULL,
    fixture_key TEXT NOT NULL,
    fixture_revision TEXT NOT NULL,
    fixture_digest TEXT NOT NULL,
    report_digest TEXT NOT NULL,
    definition TEXT NOT NULL,
    passed BOOLEAN NOT NULL,
    submitted_by UUID NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_simulation_evaluation_reports PRIMARY KEY (tenant_id, evaluation_id),
    CONSTRAINT fk_simulation_evaluation_fixture FOREIGN KEY (tenant_id, fixture_key, fixture_revision, fixture_digest)
        REFERENCES simulation_fixture_revisions (tenant_id, fixture_key, revision, digest) ON DELETE RESTRICT,
    CONSTRAINT fk_simulation_evaluation_actor FOREIGN KEY (tenant_id, submitted_by)
        REFERENCES human_actors (tenant_id, actor_id) ON DELETE RESTRICT,
    CONSTRAINT ck_simulation_evaluation_digest CHECK (
        report_digest ~ '^[0-9a-f]{64}$'
        AND encode(sha256(convert_to(definition, 'UTF8')), 'hex') = report_digest
    ),
    CONSTRAINT ck_simulation_evaluation_definition CHECK (
        octet_length(definition) BETWEEN 1 AND 1048576
        AND jsonb_typeof(definition::JSONB) = 'object'
        AND (definition::JSONB ->> 'schemaVersion') IS NOT NULL
        AND (definition::JSONB ->> 'schemaVersion') = 'ergon.simulation/report/v1'
        AND (definition::JSONB ->> 'fixtureDigest') IS NOT NULL
        AND (definition::JSONB ->> 'fixtureDigest') = fixture_digest
        AND jsonb_typeof(definition::JSONB -> 'passed') = 'boolean'
        AND (definition::JSONB ->> 'passed') IS NOT NULL
        AND (definition::JSONB ->> 'passed')::BOOLEAN = passed
    )
);

CREATE INDEX ix_simulation_evaluation_fixture
    ON simulation_evaluation_reports (tenant_id, fixture_key, fixture_revision, fixture_digest);
CREATE INDEX ix_simulation_evaluation_actor
    ON simulation_evaluation_reports (tenant_id, submitted_by);
CREATE TRIGGER trg_simulation_evaluation_immutable
BEFORE UPDATE OR DELETE ON simulation_evaluation_reports
FOR EACH ROW EXECUTE FUNCTION reject_simulation_fixture_mutation();

COMMENT ON TABLE simulation_evaluation_reports IS
    'Immutable completed offline reports, including failed checks; not promotion authority. No partial execution rows.';
COMMENT ON COLUMN simulation_evaluation_reports.definition IS
    'Canonical versioned report text. Command identity, submitted actor and recording time are excluded from semantic meaning.';
