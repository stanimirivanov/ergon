-- Add immutable case attachments. recorded_at is copied from the durable case
-- event and is the authoritative validity instant; pinned_at is audit business
-- time only. No old events, contracts, bundles or runs are rewritten.
ALTER TABLE case_events DROP CONSTRAINT ck_case_events_event_type;
ALTER TABLE case_events ADD CONSTRAINT ck_case_events_event_type CHECK (event_type IN (
    'CaseOpened', 'ObservationRecorded', 'AccountAccessStateBound',
    'ResolutionContractRevisionPinned', 'CaseVerifiedResolved', 'DocumentaryBundlePinned'
));

CREATE TABLE case_documentary_bundle_pins (
    tenant_id UUID NOT NULL,
    case_id UUID NOT NULL,
    bundle_id UUID NOT NULL,
    manifest_sha256 TEXT NOT NULL,
    stream_version BIGINT NOT NULL,
    event_id UUID NOT NULL,
    pinned_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_case_documentary_bundle_pins PRIMARY KEY (tenant_id, case_id, bundle_id),
    CONSTRAINT uq_case_documentary_pin_event UNIQUE (event_id),
    CONSTRAINT fk_case_documentary_pin_case FOREIGN KEY (tenant_id, case_id)
        REFERENCES cases (tenant_id, case_id) ON DELETE RESTRICT,
    CONSTRAINT fk_case_documentary_pin_event FOREIGN KEY (tenant_id, case_id, stream_version, event_id)
        REFERENCES case_events (tenant_id, case_id, stream_version, event_id) ON DELETE RESTRICT,
    CONSTRAINT fk_case_documentary_pin_bundle FOREIGN KEY (tenant_id, bundle_id, manifest_sha256)
        REFERENCES evidence_bundles (tenant_id, bundle_id, manifest_sha256) ON DELETE RESTRICT,
    CONSTRAINT ck_case_documentary_pin_version CHECK (stream_version > 0)
);

CREATE FUNCTION admit_case_documentary_pin() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    case_status TEXT;
    case_version BIGINT;
BEGIN
    SELECT status, stream_version INTO case_status, case_version FROM cases
    WHERE tenant_id = NEW.tenant_id AND case_id = NEW.case_id FOR UPDATE;
    IF case_status IS DISTINCT FROM 'OPEN' OR NEW.stream_version > case_version THEN
        RAISE EXCEPTION 'documentary attachment requires an open current case' USING ERRCODE = '23514';
    END IF;
    IF (SELECT COUNT(*) FROM case_documentary_bundle_pins
        WHERE tenant_id = NEW.tenant_id AND case_id = NEW.case_id) >= 20 THEN
        RAISE EXCEPTION 'case supports at most 20 documentary attachments' USING ERRCODE = '23514';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM case_events e
        WHERE e.tenant_id = NEW.tenant_id AND e.case_id = NEW.case_id AND e.stream_version = NEW.stream_version
            AND e.event_id = NEW.event_id AND e.event_type = 'DocumentaryBundlePinned' AND e.schema_version = 1
            AND e.payload->>'bundleId' = NEW.bundle_id::TEXT AND e.payload->>'sha256' = NEW.manifest_sha256
            AND e.occurred_at = NEW.pinned_at AND e.recorded_at = NEW.recorded_at
    ) THEN
        RAISE EXCEPTION 'attachment must retain its exact event identity and recording instant' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_admit_case_documentary_pin BEFORE INSERT ON case_documentary_bundle_pins
FOR EACH ROW EXECUTE FUNCTION admit_case_documentary_pin();

CREATE FUNCTION reject_case_documentary_pin_mutation() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'documentary case attachments are immutable' USING ERRCODE = '55000';
END;
$$;
CREATE TRIGGER trg_case_documentary_pins_immutable BEFORE UPDATE OR DELETE ON case_documentary_bundle_pins
FOR EACH ROW EXECUTE FUNCTION reject_case_documentary_pin_mutation();

CREATE FUNCTION require_case_documentary_projection() RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM case_documentary_bundle_pins
        WHERE tenant_id = NEW.tenant_id AND case_id = NEW.case_id AND stream_version = NEW.stream_version
            AND event_id = NEW.event_id) THEN
        RAISE EXCEPTION 'documentary case event requires its complete projection' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE CONSTRAINT TRIGGER trg_require_case_documentary_projection AFTER INSERT ON case_events
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW WHEN (NEW.event_type = 'DocumentaryBundlePinned')
EXECUTE FUNCTION require_case_documentary_projection();

-- Reads only frozen selected manifest references and immutable claim bounds.
-- Context alternatives, later acceptance/adjudication and wall time do not
-- participate. A procedure-only bundle has no selected claim interval to test.
CREATE FUNCTION documentary_bundle_suitable_at(
    tenant UUID, bundle UUID, digest TEXT, attached_at TIMESTAMPTZ
) RETURNS BOOLEAN LANGUAGE SQL STABLE AS $$
    SELECT EXISTS (
        SELECT 1 FROM evidence_bundles b
        WHERE b.tenant_id = tenant AND b.bundle_id = bundle AND b.manifest_sha256 = digest
            AND b.assessed_at <= attached_at
            AND b.canonical_manifest::JSONB->>'schema' = 'ergon.dev/evidence-bundle/v1'
            AND NOT EXISTS (
                SELECT 1 FROM JSONB_ARRAY_ELEMENTS(b.canonical_manifest::JSONB->'proposals') AS p(item)
                LEFT JOIN evidence_claim_proposals c
                    ON c.tenant_id = b.tenant_id AND c.proposal_id = (p.item->>'id')::UUID
                WHERE p.item->'selected' = 'true'::JSONB AND p.item->>'kind' = 'CLAIM'
                    AND (c.proposal_id IS NULL OR c.valid_from IS NULL OR c.valid_until IS NULL
                        OR attached_at < c.valid_from OR attached_at >= c.valid_until)
            )
    );
$$;

-- Replace PR12's unconditional v1alpha2 refusal. Lock the case just as the
-- repository does, verify the exact contract/prefix and repeat documentary
-- admission independently of application planning. Operational policy,
-- authority, connector and outcome-proof gates remain separate and unchanged.
DROP TRIGGER trg_reject_unsupported_documentary_run ON resolution_runs;
DROP FUNCTION reject_unsupported_documentary_run();
CREATE FUNCTION admit_documentary_run() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    schema TEXT;
    current_version BIGINT;
BEGIN
    SELECT schema_id INTO schema FROM resolution_contract_revisions
    WHERE tenant_id = NEW.tenant_id AND contract_key = NEW.contract_key AND revision = NEW.contract_revision;
    IF schema IS DISTINCT FROM 'ergon.dev/resolution-contract/v1alpha2' THEN RETURN NEW; END IF;
    SELECT stream_version INTO current_version FROM cases
    WHERE tenant_id = NEW.tenant_id AND case_id = NEW.case_id FOR UPDATE;
    IF current_version IS NULL OR current_version IS DISTINCT FROM NEW.case_stream_version OR NOT EXISTS (
        SELECT 1 FROM case_resolution_contract_pins
        WHERE tenant_id = NEW.tenant_id AND case_id = NEW.case_id AND contract_key = NEW.contract_key
            AND contract_revision = NEW.contract_revision AND stream_version <= NEW.case_stream_version
    ) THEN
        RAISE EXCEPTION 'documentary run requires its exact current case and contract prefix' USING ERRCODE = 'P7801';
    END IF;
    IF EXISTS (
        SELECT 1 FROM resolution_contract_documentary_evidence r
        LEFT JOIN case_documentary_bundle_pins p ON p.tenant_id = r.tenant_id AND p.case_id = NEW.case_id
            AND p.bundle_id = r.bundle_id AND p.manifest_sha256 = r.manifest_sha256
            AND p.stream_version <= NEW.case_stream_version
        WHERE r.tenant_id = NEW.tenant_id AND r.contract_key = NEW.contract_key AND r.contract_revision = NEW.contract_revision
            AND p.bundle_id IS NULL
    ) THEN
        RAISE EXCEPTION 'documentary attachments are missing at this prefix' USING ERRCODE = 'P7801';
    END IF;
    IF EXISTS (
        SELECT 1 FROM resolution_contract_documentary_evidence r
        JOIN case_documentary_bundle_pins p ON p.tenant_id = r.tenant_id AND p.case_id = NEW.case_id
            AND p.bundle_id = r.bundle_id AND p.manifest_sha256 = r.manifest_sha256
        WHERE r.tenant_id = NEW.tenant_id AND r.contract_key = NEW.contract_key AND r.contract_revision = NEW.contract_revision
            AND NOT documentary_bundle_suitable_at(r.tenant_id, r.bundle_id, r.manifest_sha256, p.recorded_at)
    ) THEN
        RAISE EXCEPTION 'documentary attachments are unsuitable' USING ERRCODE = 'P7802';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_admit_documentary_run BEFORE INSERT ON resolution_runs
FOR EACH ROW EXECUTE FUNCTION admit_documentary_run();

COMMENT ON TABLE case_documentary_bundle_pins IS
    'Immutable exact case attachments. Prefix membership and database attachment time qualify documentary requirements without supplying operational facts or authority.';
COMMENT ON COLUMN case_documentary_bundle_pins.recorded_at IS
    'Exact case event database recording instant used for historical claim validity; never a client-selected cutoff.';
