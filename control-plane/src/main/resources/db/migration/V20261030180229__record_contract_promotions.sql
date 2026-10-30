-- Additive promotion authority, governing-suite history and promotion receipts.
-- No existing data rewrite/backfill. All history is immutable; current binding is its highest version.
-- Both writer paths serialize command then tenant/contract key, including absent first bindings.
CREATE TABLE contract_promotion_authorities (
    tenant_id UUID NOT NULL,
    evidence_id UUID NOT NULL,
    actor_id UUID NOT NULL,
    source_provider TEXT NOT NULL,
    source_reference TEXT NOT NULL,
    attested_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_contract_promotion_authorities PRIMARY KEY (tenant_id, evidence_id),
    CONSTRAINT uq_contract_promotion_authority_actor UNIQUE (tenant_id, evidence_id, actor_id),
    CONSTRAINT fk_contract_promotion_authority_actor FOREIGN KEY (tenant_id, actor_id)
        REFERENCES human_actors (tenant_id, actor_id) ON DELETE RESTRICT,
    CONSTRAINT ck_contract_promotion_authority_source CHECK (
        source_provider ~ '^[a-z0-9][a-z0-9._-]{0,99}$' AND length(source_reference) BETWEEN 1 AND 500
        AND source_reference = btrim(source_reference) AND source_reference !~ '[[:cntrl:]]'
    ),
    CONSTRAINT ck_contract_promotion_authority_validity CHECK (
        expires_at > attested_at AND expires_at <= attested_at + INTERVAL '24 hours'
    )
);
CREATE INDEX ix_contract_promotion_authority_current
    ON contract_promotion_authorities (tenant_id, actor_id, expires_at DESC);
CREATE FUNCTION stamp_contract_promotion_authority() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    NEW.attested_at := clock_timestamp();
    IF NEW.expires_at <= NEW.attested_at OR NEW.expires_at > NEW.attested_at + INTERVAL '24 hours' THEN
        RAISE EXCEPTION 'invalid promotion authority expiry' USING ERRCODE = 'P7503';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_contract_promotion_authority_stamp BEFORE INSERT ON contract_promotion_authorities
FOR EACH ROW EXECUTE FUNCTION stamp_contract_promotion_authority();
CREATE TRIGGER trg_contract_promotion_authority_immutable BEFORE UPDATE OR DELETE ON contract_promotion_authorities
FOR EACH ROW EXECUTE FUNCTION reject_simulation_fixture_mutation();

CREATE FUNCTION lock_contract_promotion(p_tenant UUID, p_key TEXT, p_command UUID, p_operation TEXT)
RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
    IF p_operation NOT IN ('binding', 'promotion') THEN RAISE EXCEPTION 'unknown promotion operation'; END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended('contract-' || p_operation || '-command:' || p_tenant || ':' || p_command, 0));
    PERFORM pg_advisory_xact_lock(hashtextextended('contract-promotion:' || p_tenant || ':' || p_key, 0));
END;
$$;
CREATE FUNCTION require_contract_promoter(p_tenant UUID, p_actor UUID, p_authority UUID)
RETURNS TIMESTAMPTZ LANGUAGE plpgsql AS $$
DECLARE admission_at TIMESTAMPTZ := clock_timestamp();
BEGIN
    IF NOT EXISTS (SELECT 1 FROM contract_promotion_authorities
        WHERE tenant_id = p_tenant AND actor_id = p_actor AND evidence_id = p_authority
          AND attested_at <= admission_at AND expires_at > admission_at) THEN
        RAISE EXCEPTION 'current contract promoter authority required' USING ERRCODE = 'P7501';
    END IF;
    RETURN admission_at;
END;
$$;

CREATE TABLE contract_required_suite_bindings (
    tenant_id UUID NOT NULL,
    contract_key TEXT NOT NULL,
    version BIGINT NOT NULL,
    command_id UUID NOT NULL,
    suite_kind TEXT NOT NULL DEFAULT 'SUITE',
    suite_key TEXT NOT NULL,
    suite_revision TEXT NOT NULL,
    suite_digest TEXT NOT NULL,
    actor_id UUID NOT NULL,
    authority_id UUID NOT NULL,
    definition TEXT NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_contract_required_suite_bindings PRIMARY KEY (tenant_id, contract_key, version),
    CONSTRAINT uq_contract_required_suite_command UNIQUE (tenant_id, command_id),
    CONSTRAINT uq_contract_required_suite_reference UNIQUE (tenant_id, contract_key, version, suite_key, suite_revision, suite_digest),
    CONSTRAINT ck_contract_required_suite_version CHECK (version > 0 AND suite_kind = 'SUITE'),
    CONSTRAINT ck_contract_required_suite_definition CHECK (
        octet_length(definition) BETWEEN 1 AND 131072 AND jsonb_typeof(definition::JSONB) = 'object'
    ),
    CONSTRAINT fk_contract_required_suite FOREIGN KEY (tenant_id, suite_kind, suite_key, suite_revision, suite_digest)
        REFERENCES evaluation_gate_documents (tenant_id, kind, document_key, revision, digest) ON DELETE RESTRICT,
    CONSTRAINT fk_contract_required_suite_authority FOREIGN KEY (tenant_id, authority_id, actor_id)
        REFERENCES contract_promotion_authorities (tenant_id, evidence_id, actor_id) ON DELETE RESTRICT
);
CREATE INDEX ix_contract_required_suite ON contract_required_suite_bindings (tenant_id, suite_kind, suite_key, suite_revision, suite_digest);
CREATE INDEX ix_contract_required_suite_authority ON contract_required_suite_bindings (tenant_id, authority_id, actor_id);
CREATE FUNCTION admit_contract_required_suite() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE current_version BIGINT;
BEGIN
    PERFORM lock_contract_promotion(NEW.tenant_id, NEW.contract_key, NEW.command_id, 'binding');
    NEW.recorded_at := require_contract_promoter(NEW.tenant_id, NEW.actor_id, NEW.authority_id);
    SELECT COALESCE(MAX(version), 0) INTO current_version FROM contract_required_suite_bindings
        WHERE tenant_id = NEW.tenant_id AND contract_key = NEW.contract_key;
    IF NEW.version <> current_version + 1 THEN
        RAISE EXCEPTION 'stale required suite version' USING ERRCODE = 'P7502';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM evaluation_gate_documents WHERE tenant_id = NEW.tenant_id AND kind = 'SUITE'
        AND document_key = NEW.suite_key AND revision = NEW.suite_revision AND digest = NEW.suite_digest
        AND contract_key = NEW.contract_key) THEN
        RAISE EXCEPTION 'required suite must bind this published contract key' USING ERRCODE = 'P7502';
    END IF;
    IF (NEW.definition::JSONB ->> 'schemaVersion') IS DISTINCT FROM 'ergon.contract-promotion/required-suite/v1'
        OR (NEW.definition::JSONB ->> 'expectedVersion')::BIGINT IS DISTINCT FROM NEW.version - 1
        OR (NEW.definition::JSONB -> 'suite') IS DISTINCT FROM jsonb_build_object(
            'key', NEW.suite_key, 'revision', NEW.suite_revision, 'sha256', NEW.suite_digest) THEN
        RAISE EXCEPTION 'binding intent does not match its references' USING ERRCODE = 'P7502';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_contract_required_suite_admit BEFORE INSERT ON contract_required_suite_bindings
FOR EACH ROW EXECUTE FUNCTION admit_contract_required_suite();
CREATE TRIGGER trg_contract_required_suite_immutable BEFORE UPDATE OR DELETE ON contract_required_suite_bindings
FOR EACH ROW EXECUTE FUNCTION reject_simulation_fixture_mutation();

CREATE TABLE contract_promotions (
    tenant_id UUID NOT NULL,
    command_id UUID NOT NULL,
    contract_key TEXT NOT NULL,
    contract_revision INTEGER NOT NULL,
    binding_version BIGINT NOT NULL,
    suite_key TEXT NOT NULL,
    suite_revision TEXT NOT NULL,
    suite_digest TEXT NOT NULL,
    actor_id UUID NOT NULL,
    authority_id UUID NOT NULL,
    definition TEXT NOT NULL,
    promoted_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_contract_promotions PRIMARY KEY (tenant_id, command_id),
    CONSTRAINT fk_contract_promotion_contract FOREIGN KEY (tenant_id, contract_key, contract_revision)
        REFERENCES resolution_contract_revisions (tenant_id, contract_key, revision) ON DELETE RESTRICT,
    CONSTRAINT fk_contract_promotion_binding FOREIGN KEY (tenant_id, contract_key, binding_version, suite_key, suite_revision, suite_digest)
        REFERENCES contract_required_suite_bindings (tenant_id, contract_key, version, suite_key, suite_revision, suite_digest) ON DELETE RESTRICT,
    CONSTRAINT fk_contract_promotion_authority FOREIGN KEY (tenant_id, authority_id, actor_id)
        REFERENCES contract_promotion_authorities (tenant_id, evidence_id, actor_id) ON DELETE RESTRICT,
    CONSTRAINT ck_contract_promotion_definition CHECK (
        octet_length(definition) BETWEEN 1 AND 131072 AND jsonb_typeof(definition::JSONB) = 'object'
    )
);
CREATE INDEX ix_contract_promotion_contract ON contract_promotions (tenant_id, contract_key, contract_revision);
CREATE INDEX ix_contract_promotion_binding ON contract_promotions (tenant_id, contract_key, binding_version, suite_key, suite_revision, suite_digest);
CREATE INDEX ix_contract_promotion_authority ON contract_promotions (tenant_id, authority_id, actor_id);
-- Repeat stable qualification against immutable stored results, without executing fixture content in SQL.
CREATE FUNCTION admit_contract_promotion() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    current_version BIGINT;
    suite JSONB;
    policy JSONB;
    intent JSONB := NEW.definition::JSONB;
    member JSONB;
    selected JSONB;
    report simulation_evaluation_reports%ROWTYPE;
BEGIN
    PERFORM lock_contract_promotion(NEW.tenant_id, NEW.contract_key, NEW.command_id, 'promotion');
    NEW.promoted_at := require_contract_promoter(NEW.tenant_id, NEW.actor_id, NEW.authority_id);
    SELECT MAX(version) INTO current_version FROM contract_required_suite_bindings
        WHERE tenant_id = NEW.tenant_id AND contract_key = NEW.contract_key;
    IF current_version IS NULL OR current_version <> NEW.binding_version THEN
        RAISE EXCEPTION 'stale promotion gate' USING ERRCODE = 'P7502';
    END IF;
    SELECT definition::JSONB INTO suite FROM evaluation_gate_documents
        WHERE tenant_id = NEW.tenant_id AND kind = 'SUITE' AND document_key = NEW.suite_key
          AND revision = NEW.suite_revision AND digest = NEW.suite_digest;
    SELECT definition::JSONB INTO policy FROM evaluation_gate_documents
        WHERE tenant_id = NEW.tenant_id AND kind = 'POLICY' AND document_key = suite -> 'policy' ->> 'key'
          AND revision = suite -> 'policy' ->> 'revision' AND digest = suite -> 'policy' ->> 'sha256';
    IF suite IS NULL OR policy IS NULL
        OR (intent ->> 'schemaVersion') IS DISTINCT FROM 'ergon.contract-promotion/command/v1'
        OR (intent ->> 'expectedVersion')::BIGINT IS DISTINCT FROM NEW.binding_version
        OR (intent -> 'suite') IS DISTINCT FROM jsonb_build_object(
            'key', NEW.suite_key, 'revision', NEW.suite_revision, 'sha256', NEW.suite_digest)
        OR (intent -> 'contract') IS DISTINCT FROM (suite -> 'contract')
        OR (intent -> 'policy') IS DISTINCT FROM (suite -> 'policy')
        OR (intent -> 'contract' ->> 'key') IS DISTINCT FROM NEW.contract_key
        OR (intent -> 'contract' ->> 'revision') IS DISTINCT FROM NEW.contract_revision::TEXT
        OR jsonb_typeof(intent -> 'reports') IS DISTINCT FROM 'array'
        OR jsonb_array_length(intent -> 'reports') IS DISTINCT FROM jsonb_array_length(suite -> 'scenarios') THEN
        RAISE EXCEPTION 'promotion intent does not match the complete required suite' USING ERRCODE = 'P7502';
    END IF;
    FOR member IN SELECT value FROM jsonb_array_elements(suite -> 'scenarios') LOOP
        SELECT value INTO selected FROM jsonb_array_elements(intent -> 'reports')
            WHERE value -> 'fixture' = member -> 'fixture';
        SELECT * INTO report FROM simulation_evaluation_reports
            WHERE tenant_id = NEW.tenant_id AND evaluation_id = (selected ->> 'reportId')::UUID
              AND report_digest = selected ->> 'reportDigest' AND passed
              AND fixture_key = member -> 'fixture' ->> 'key'
              AND fixture_revision = member -> 'fixture' ->> 'revision'
              AND fixture_digest = member -> 'fixture' ->> 'sha256';
        IF NOT FOUND THEN
            RAISE EXCEPTION 'required passing report is unavailable' USING ERRCODE = 'P7502';
        END IF;
        IF NOT (policy -> 'evaluators' @> jsonb_build_array(report.definition::JSONB -> 'evaluator'))
            OR EXISTS (SELECT 1 FROM jsonb_array_elements_text(member -> 'requiredAssertions') AS required_check(id)
                WHERE NOT EXISTS (SELECT 1 FROM jsonb_array_elements(report.definition::JSONB -> 'checks') check_result
                    WHERE check_result ->> 'id' = required_check.id AND check_result ->> 'status' = 'PASS'))
            OR EXISTS (SELECT 1 FROM jsonb_array_elements(report.definition::JSONB -> 'checks') check_result
                WHERE (check_result ->> 'required')::BOOLEAN AND check_result ->> 'status' IS DISTINCT FROM 'PASS') THEN
            RAISE EXCEPTION 'required report assertions or evaluator do not qualify' USING ERRCODE = 'P7502';
        END IF;
        IF (policy ->> 'requireCostBudget')::BOOLEAN AND (
            (report.definition::JSONB #>> '{budgets,cost,limit}') IS NULL
            OR (report.definition::JSONB #>> '{budgets,cost,status}') IS DISTINCT FROM 'PASS') THEN
            RAISE EXCEPTION 'required cost budget does not qualify' USING ERRCODE = 'P7502';
        END IF;
        IF (policy ->> 'requireLatencyBudget')::BOOLEAN AND (
            (report.definition::JSONB #>> '{budgets,simulatedLatency,limit}') IS NULL
            OR (report.definition::JSONB #>> '{budgets,simulatedLatency,status}') IS DISTINCT FROM 'PASS') THEN
            RAISE EXCEPTION 'required latency budget does not qualify' USING ERRCODE = 'P7502';
        END IF;
    END LOOP;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_contract_promotion_admit BEFORE INSERT ON contract_promotions
FOR EACH ROW EXECUTE FUNCTION admit_contract_promotion();
CREATE TRIGGER trg_contract_promotion_immutable BEFORE UPDATE OR DELETE ON contract_promotions
FOR EACH ROW EXECUTE FUNCTION reject_simulation_fixture_mutation();

CREATE TABLE contract_promotion_reports (
    tenant_id UUID NOT NULL,
    promotion_id UUID NOT NULL,
    evaluation_id UUID NOT NULL,
    CONSTRAINT pk_contract_promotion_reports PRIMARY KEY (tenant_id, promotion_id, evaluation_id),
    CONSTRAINT fk_contract_promotion_report_parent FOREIGN KEY (tenant_id, promotion_id)
        REFERENCES contract_promotions (tenant_id, command_id) ON DELETE RESTRICT,
    CONSTRAINT fk_contract_promotion_report FOREIGN KEY (tenant_id, evaluation_id)
        REFERENCES simulation_evaluation_reports (tenant_id, evaluation_id) ON DELETE RESTRICT
);
CREATE INDEX ix_contract_promotion_report ON contract_promotion_reports (tenant_id, evaluation_id);
CREATE TRIGGER trg_contract_promotion_report_immutable BEFORE UPDATE OR DELETE ON contract_promotion_reports
FOR EACH ROW EXECUTE FUNCTION reject_simulation_fixture_mutation();
COMMENT ON TABLE contract_promotion_authorities IS 'Dedicated tenant-wide CONTRACT_PROMOTER attestations; no resolver inheritance. Expiry is half-open.';
COMMENT ON TABLE contract_required_suite_bindings IS 'Confirmed immutable governing-suite history; highest version governs new promotion commands.';
COMMENT ON TABLE contract_promotions IS 'Explicit historical promotion receipts. Run admission is a separate gate; old receipts survive governing-suite changes.';
COMMENT ON TABLE contract_promotion_reports IS 'Tenant-safe immutable evidence links written atomically with the promotion receipt.';

CREATE FUNCTION check_contract_promotion_report_links() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE expected JSONB;
BEGIN
    SELECT definition::JSONB -> 'reports' INTO expected FROM contract_promotions
        WHERE tenant_id = NEW.tenant_id AND command_id = NEW.command_id;
    IF (SELECT COUNT(*) FROM contract_promotion_reports
        WHERE tenant_id = NEW.tenant_id AND promotion_id = NEW.command_id) <> jsonb_array_length(expected)
        OR EXISTS (SELECT 1 FROM jsonb_array_elements(expected) selected
            WHERE NOT EXISTS (SELECT 1 FROM contract_promotion_reports
                WHERE tenant_id = NEW.tenant_id AND promotion_id = NEW.command_id
                  AND evaluation_id = (selected ->> 'reportId')::UUID)) THEN
        RAISE EXCEPTION 'incomplete promotion evidence links' USING ERRCODE = 'P7502';
    END IF;
    RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER trg_contract_promotion_report_links AFTER INSERT ON contract_promotions
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION check_contract_promotion_report_links();

CREATE FUNCTION admit_contract_promotion_report_link() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM contract_promotions p,
        LATERAL jsonb_array_elements(p.definition::JSONB -> 'reports') AS selected(value)
        WHERE p.tenant_id = NEW.tenant_id AND p.command_id = NEW.promotion_id
          AND (selected.value ->> 'reportId')::UUID = NEW.evaluation_id) THEN
        RAISE EXCEPTION 'report is not declared in immutable promotion intent' USING ERRCODE = 'P7502';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_contract_promotion_report_link_admit BEFORE INSERT ON contract_promotion_reports
FOR EACH ROW EXECUTE FUNCTION admit_contract_promotion_report_link();
