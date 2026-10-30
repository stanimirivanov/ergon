-- New viewer authority and append-only revocations; no historical entitlement or report rewrite.
CREATE FUNCTION lock_simulation_authority(p_tenant UUID, p_actor UUID) RETURNS VOID LANGUAGE plpgsql AS $$
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended('simulation-authority:' || p_tenant || ':' || p_actor, 0));
END;
$$;
CREATE TABLE evaluation_viewer_authorities (
    tenant_id UUID NOT NULL,
    evidence_id UUID NOT NULL,
    actor_id UUID NOT NULL,
    source_provider TEXT NOT NULL,
    source_reference TEXT NOT NULL,
    attested_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    expires_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_evaluation_viewer_authority PRIMARY KEY (tenant_id, evidence_id),
    CONSTRAINT fk_evaluation_viewer_actor FOREIGN KEY (tenant_id, actor_id)
        REFERENCES human_actors (tenant_id, actor_id) ON DELETE RESTRICT,
    CONSTRAINT ck_evaluation_viewer_source CHECK (
        source_provider ~ '^[a-z0-9][a-z0-9._-]{0,99}$' AND length(source_reference) BETWEEN 1 AND 500
        AND source_reference = btrim(source_reference) AND source_reference !~ '[[:cntrl:]]'
    ),
    CONSTRAINT ck_evaluation_viewer_validity CHECK (
        expires_at > attested_at AND expires_at <= attested_at + INTERVAL '24 hours'
    )
);
CREATE INDEX ix_evaluation_viewer_current ON evaluation_viewer_authorities (tenant_id, actor_id, expires_at DESC);
CREATE TRIGGER trg_evaluation_viewer_stamp BEFORE INSERT ON evaluation_viewer_authorities
FOR EACH ROW EXECUTE FUNCTION stamp_contract_promotion_authority();
CREATE TRIGGER trg_evaluation_viewer_immutable BEFORE UPDATE OR DELETE ON evaluation_viewer_authorities
FOR EACH ROW EXECUTE FUNCTION reject_simulation_fixture_mutation();

CREATE TABLE evaluation_viewer_revocations (
    tenant_id UUID NOT NULL,
    evidence_id UUID NOT NULL,
    source_provider TEXT NOT NULL,
    source_reference TEXT NOT NULL,
    reason TEXT NOT NULL,
    revoked_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_evaluation_viewer_revocation PRIMARY KEY (tenant_id, evidence_id),
    CONSTRAINT fk_evaluation_viewer_revocation FOREIGN KEY (tenant_id, evidence_id)
        REFERENCES evaluation_viewer_authorities (tenant_id, evidence_id) ON DELETE RESTRICT,
    CONSTRAINT ck_evaluation_viewer_revocation_source CHECK (
        source_provider ~ '^[a-z0-9][a-z0-9._-]{0,99}$'
        AND length(source_reference) BETWEEN 1 AND 500 AND source_reference = btrim(source_reference)
        AND source_reference !~ '[[:cntrl:]]' AND length(reason) BETWEEN 1 AND 500
        AND reason = btrim(reason) AND reason !~ '[[:cntrl:]]'
    )
);
CREATE TABLE contract_promotion_revocations (
    tenant_id UUID NOT NULL,
    evidence_id UUID NOT NULL,
    source_provider TEXT NOT NULL,
    source_reference TEXT NOT NULL,
    reason TEXT NOT NULL,
    revoked_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_contract_promotion_revocation PRIMARY KEY (tenant_id, evidence_id),
    CONSTRAINT fk_contract_promotion_revocation FOREIGN KEY (tenant_id, evidence_id)
        REFERENCES contract_promotion_authorities (tenant_id, evidence_id) ON DELETE RESTRICT,
    CONSTRAINT ck_contract_promotion_revocation_source CHECK (
        source_provider ~ '^[a-z0-9][a-z0-9._-]{0,99}$'
        AND length(source_reference) BETWEEN 1 AND 500 AND source_reference = btrim(source_reference)
        AND source_reference !~ '[[:cntrl:]]' AND length(reason) BETWEEN 1 AND 500
        AND reason = btrim(reason) AND reason !~ '[[:cntrl:]]'
    )
);
CREATE FUNCTION admit_simulation_authority_revocation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE actor UUID;
BEGIN
    -- TG_ARGV is fixed by these migrations, never supplied by a request.
    EXECUTE format('SELECT actor_id FROM %I WHERE tenant_id = $1 AND evidence_id = $2', TG_ARGV[0])
        INTO actor USING NEW.tenant_id, NEW.evidence_id;
    IF actor IS NULL THEN RAISE EXCEPTION 'authority unavailable' USING ERRCODE = 'P7501'; END IF;
    PERFORM lock_simulation_authority(NEW.tenant_id, actor);
    NEW.revoked_at := clock_timestamp();
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_evaluation_viewer_revoke BEFORE INSERT ON evaluation_viewer_revocations
FOR EACH ROW EXECUTE FUNCTION admit_simulation_authority_revocation('evaluation_viewer_authorities');
CREATE TRIGGER trg_contract_promotion_revoke BEFORE INSERT ON contract_promotion_revocations
FOR EACH ROW EXECUTE FUNCTION admit_simulation_authority_revocation('contract_promotion_authorities');
CREATE TRIGGER trg_evaluation_viewer_revocation_immutable BEFORE UPDATE OR DELETE ON evaluation_viewer_revocations
FOR EACH ROW EXECUTE FUNCTION reject_simulation_fixture_mutation();
CREATE TRIGGER trg_contract_promotion_revocation_immutable BEFORE UPDATE OR DELETE ON contract_promotion_revocations
FOR EACH ROW EXECUTE FUNCTION reject_simulation_fixture_mutation();

CREATE OR REPLACE FUNCTION require_contract_promoter(p_tenant UUID, p_actor UUID, p_authority UUID)
RETURNS TIMESTAMPTZ LANGUAGE plpgsql AS $$
DECLARE admission_at TIMESTAMPTZ;
BEGIN
    -- Existing command -> contract locks precede this actor lock; revocation only takes the actor lock.
    PERFORM lock_simulation_authority(p_tenant, p_actor);
    admission_at := clock_timestamp();
    IF NOT EXISTS (SELECT 1 FROM contract_promotion_authorities a
        WHERE a.tenant_id = p_tenant AND a.actor_id = p_actor AND a.evidence_id = p_authority
          AND a.attested_at <= admission_at AND a.expires_at > admission_at
          AND NOT EXISTS (SELECT 1 FROM contract_promotion_revocations r
              WHERE r.tenant_id = a.tenant_id AND r.evidence_id = a.evidence_id)) THEN
        RAISE EXCEPTION 'current contract promoter authority required' USING ERRCODE = 'P7501';
    END IF;
    RETURN admission_at;
END;
$$;
-- Bounded newest-first keyset discovery; no report definitions are projected by the list query.
CREATE INDEX ix_simulation_report_discovery ON simulation_evaluation_reports (tenant_id, recorded_at DESC, evaluation_id DESC);
COMMENT ON TABLE evaluation_viewer_authorities IS 'Dedicated EVALUATION_VIEWER grants; no implicit role inheritance.';
COMMENT ON TABLE contract_promotion_revocations IS 'Revokes future authority use, never historical promotion or runtime qualification.';
