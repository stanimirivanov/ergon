-- A publication identity lock serializes first admission/replay even for procedure-only bundles.
CREATE TABLE evidence_bundle_publication_keys (
    tenant_id UUID NOT NULL,
    bundle_id UUID NOT NULL,
    CONSTRAINT pk_bundle_publication_keys PRIMARY KEY (tenant_id, bundle_id)
);

-- Canonical UTF-8 JSON text retains the exact hashed representation; relational ledgers retain tenant-safe references.
CREATE TABLE evidence_bundles (
    tenant_id UUID NOT NULL,
    bundle_id UUID NOT NULL,
    publication_command TEXT NOT NULL,
    canonical_manifest TEXT NOT NULL,
    manifest_sha256 TEXT NOT NULL,
    assessed_at TIMESTAMPTZ NOT NULL,
    published_by UUID NOT NULL,
    authority_evidence_id UUID NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT pk_evidence_bundles PRIMARY KEY (tenant_id, bundle_id),
    CONSTRAINT fk_bundle_publication_key FOREIGN KEY (tenant_id, bundle_id)
        REFERENCES evidence_bundle_publication_keys (tenant_id, bundle_id) ON DELETE RESTRICT,
    CONSTRAINT fk_bundle_publisher FOREIGN KEY (tenant_id, published_by)
        REFERENCES human_actors (tenant_id, actor_id) ON DELETE RESTRICT,
    CONSTRAINT fk_bundle_authority FOREIGN KEY (tenant_id, authority_evidence_id)
        REFERENCES approval_authority_evidence (tenant_id, evidence_id) ON DELETE RESTRICT,
    CONSTRAINT ck_bundle_command CHECK (OCTET_LENGTH(publication_command) <= 32768
        AND JSONB_TYPEOF(publication_command::JSONB) = 'object'),
    CONSTRAINT ck_bundle_manifest CHECK (OCTET_LENGTH(canonical_manifest) <= 1048576
        AND JSONB_TYPEOF(canonical_manifest::JSONB) = 'object'),
    CONSTRAINT ck_bundle_digest CHECK (manifest_sha256 = ENCODE(SHA256(CONVERT_TO(canonical_manifest, 'UTF8')), 'hex'))
);

CREATE TABLE evidence_bundle_proposals (
    tenant_id UUID NOT NULL,
    bundle_id UUID NOT NULL,
    kind TEXT NOT NULL,
    proposal_id UUID NOT NULL,
    -- Exactly one typed proposal foreign key is populated; the other is inapplicable.
    claim_id UUID,
    procedure_id UUID,
    review_command_id UUID NOT NULL,
    CONSTRAINT pk_bundle_proposals PRIMARY KEY (tenant_id, bundle_id, kind, proposal_id),
    CONSTRAINT fk_bundle_proposal_parent FOREIGN KEY (tenant_id, bundle_id)
        REFERENCES evidence_bundles (tenant_id, bundle_id) ON DELETE RESTRICT,
    CONSTRAINT fk_bundle_claim FOREIGN KEY (tenant_id, claim_id)
        REFERENCES evidence_claim_proposals (tenant_id, proposal_id) ON DELETE RESTRICT,
    CONSTRAINT fk_bundle_procedure FOREIGN KEY (tenant_id, procedure_id)
        REFERENCES evidence_procedure_proposals (tenant_id, proposal_id) ON DELETE RESTRICT,
    CONSTRAINT fk_bundle_review FOREIGN KEY (tenant_id, review_command_id)
        REFERENCES evidence_proposal_reviews (tenant_id, command_id) ON DELETE RESTRICT,
    CONSTRAINT ck_bundle_target CHECK (
        (kind = 'CLAIM' AND claim_id IS NOT NULL AND claim_id = proposal_id AND procedure_id IS NULL)
        OR (kind = 'PROCEDURE' AND procedure_id IS NOT NULL AND procedure_id = proposal_id AND claim_id IS NULL))
);

CREATE TABLE evidence_bundle_sources (
    tenant_id UUID NOT NULL,
    bundle_id UUID NOT NULL,
    source_id UUID NOT NULL,
    digest TEXT NOT NULL,
    CONSTRAINT pk_bundle_sources PRIMARY KEY (tenant_id, bundle_id, source_id, digest),
    CONSTRAINT fk_bundle_source_parent FOREIGN KEY (tenant_id, bundle_id)
        REFERENCES evidence_bundles (tenant_id, bundle_id) ON DELETE RESTRICT,
    CONSTRAINT fk_bundle_source FOREIGN KEY (tenant_id, source_id, digest)
        REFERENCES knowledge_source_revisions (tenant_id, source_id, digest) ON DELETE RESTRICT
);

CREATE TABLE evidence_bundle_adjudications (
    tenant_id UUID NOT NULL,
    bundle_id UUID NOT NULL,
    adjudication_id UUID NOT NULL,
    CONSTRAINT pk_bundle_adjudications PRIMARY KEY (tenant_id, bundle_id, adjudication_id),
    CONSTRAINT fk_bundle_adjudication_parent FOREIGN KEY (tenant_id, bundle_id)
        REFERENCES evidence_bundles (tenant_id, bundle_id) ON DELETE RESTRICT,
    CONSTRAINT fk_bundle_adjudication FOREIGN KEY (tenant_id, adjudication_id)
        REFERENCES evidence_contradiction_adjudications (tenant_id, command_id) ON DELETE RESTRICT
);

-- Called in one deterministic key order by admission; accepted membership advances on the same locked row.
CREATE FUNCTION qualify_bundle_claim_set(owner_tenant UUID, selection JSONB, manifest JSONB)
RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE
    current_revision BIGINT;
    candidates UUID[];
    reviews UUID[];
    expected_candidates UUID[];
    expected_reviews UUID[];
    selected_ids UUID[];
    codes TEXT[];
    adjudication evidence_contradiction_adjudications%ROWTYPE;
BEGIN
    SELECT revision INTO current_revision FROM evidence_claim_set_revisions
    WHERE tenant_id = owner_tenant AND property = selection->>'property'
        AND subject = selection->>'subject' AND scope = selection->>'scope' FOR UPDATE;
    SELECT ARRAY_AGG(p.proposal_id ORDER BY p.proposal_id), ARRAY_AGG(r.command_id ORDER BY p.proposal_id)
    INTO candidates, reviews
    FROM evidence_claim_proposals p
    JOIN evidence_proposal_reviews r ON r.tenant_id = p.tenant_id AND r.proposal_id = p.proposal_id
        AND r.proposal_kind = 'CLAIM' AND r.decision = 'ACCEPTED'
    WHERE p.tenant_id = owner_tenant AND p.property = selection->>'property'
        AND p.subject = selection->>'subject' AND p.scope = selection->>'scope';
    SELECT ARRAY_AGG(value::UUID ORDER BY ordinal) INTO expected_candidates
    FROM JSONB_ARRAY_ELEMENTS_TEXT(selection->'candidateIds') WITH ORDINALITY AS items(value, ordinal);
    SELECT ARRAY_AGG(value::UUID ORDER BY ordinal) INTO expected_reviews
    FROM JSONB_ARRAY_ELEMENTS_TEXT(selection->'reviewCommandIds') WITH ORDINALITY AS items(value, ordinal);
    IF current_revision IS NULL OR current_revision IS DISTINCT FROM (selection->>'revision')::BIGINT
        OR candidates IS DISTINCT FROM expected_candidates OR reviews IS DISTINCT FROM expected_reviews THEN
        RAISE EXCEPTION 'stale bundle claim set' USING ERRCODE = 'P7702';
    END IF;
    IF CARDINALITY(candidates) NOT BETWEEN 1 AND 20 OR EXISTS (
        SELECT 1 FROM evidence_claim_proposals WHERE tenant_id = owner_tenant AND proposal_id = ANY(candidates)
            AND (valid_from IS NULL OR valid_until IS NULL)
    ) THEN
        RAISE EXCEPTION 'unqualified bundle validity' USING ERRCODE = 'P7703';
    END IF;
    -- Complete alternatives must be present, not only the preferred subset.
    SELECT ARRAY_AGG((item->>'id')::UUID ORDER BY (item->>'id')::UUID),
           ARRAY_AGG((item->>'reviewCommandId')::UUID ORDER BY (item->>'id')::UUID)
    INTO expected_candidates, expected_reviews
    FROM JSONB_ARRAY_ELEMENTS(manifest->'proposals') AS entries(item)
    WHERE item->>'kind' = 'CLAIM' AND (item->>'id')::UUID = ANY(candidates);
    IF candidates IS DISTINCT FROM expected_candidates OR reviews IS DISTINCT FROM expected_reviews THEN
        RAISE EXCEPTION 'incomplete bundle alternatives' USING ERRCODE = 'P7702';
    END IF;
    SELECT ARRAY_AGG(code ORDER BY left_id, right_id) INTO codes FROM (
        SELECT l.proposal_id AS left_id, r.proposal_id AS right_id,
            CASE
                WHEN l.valid_from >= r.valid_until OR r.valid_from >= l.valid_until THEN 'DISJOINT_VALIDITY'
                WHEN l.boolean_value IS NOT DISTINCT FROM r.boolean_value
                    AND l.text_value IS NOT DISTINCT FROM r.text_value THEN 'COMPATIBLE'
                WHEN l.property = 'SUPPORT' THEN 'CONFLICT'
                ELSE 'UNDETERMINED'
            END AS code
        FROM evidence_claim_proposals l JOIN evidence_claim_proposals r
            ON r.tenant_id = l.tenant_id AND l.proposal_id < r.proposal_id
        WHERE l.tenant_id = owner_tenant AND l.proposal_id = ANY(candidates) AND r.proposal_id = ANY(candidates)
    ) pairs;
    codes := COALESCE(codes, ARRAY[]::TEXT[]);
    IF 'UNDETERMINED' = ANY(codes) OR TO_JSONB(codes) IS DISTINCT FROM selection->'findingCodes' THEN
        RAISE EXCEPTION 'undetermined or inconsistent bundle findings' USING ERRCODE = 'P7703';
    END IF;
    SELECT ARRAY_AGG((item->>'id')::UUID) INTO selected_ids
    FROM JSONB_ARRAY_ELEMENTS(manifest->'proposals') AS entries(item)
    WHERE item->>'kind' = 'CLAIM' AND (item->>'id')::UUID = ANY(candidates) AND (item->>'selected')::BOOLEAN;
    IF COALESCE(CARDINALITY(selected_ids), 0) = 0 THEN
        RAISE EXCEPTION 'unused bundle assessment' USING ERRCODE = 'P7703';
    END IF;
    IF 'CONFLICT' = ANY(codes) THEN
        SELECT * INTO adjudication FROM evidence_contradiction_adjudications
        WHERE tenant_id = owner_tenant AND command_id = (selection->>'adjudicationId')::UUID;
        IF NOT FOUND OR adjudication.property <> selection->>'property'
            OR adjudication.subject <> selection->>'subject' OR adjudication.scope <> selection->>'scope'
            OR adjudication.set_revision <> current_revision OR adjudication.candidate_ids <> candidates
            OR adjudication.review_command_ids <> reviews OR adjudication.finding_codes <> codes
            OR adjudication.adjudicated_at > (manifest->>'assessedAt')::TIMESTAMPTZ
            OR adjudication.choice <> 'PREFER_CLAIM' OR CARDINALITY(selected_ids) <> 1
            OR adjudication.selected_claim_id <> selected_ids[1] THEN
            RAISE EXCEPTION 'unresolved bundle conflict' USING ERRCODE = 'P7703';
        END IF;
    ELSIF selection->>'adjudicationId' IS NOT NULL THEN
        RAISE EXCEPTION 'unexpected bundle adjudication' USING ERRCODE = 'P7703';
    END IF;
END;
$$;

CREATE FUNCTION admit_evidence_bundle() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    manifest JSONB := NEW.canonical_manifest::JSONB;
    command JSONB := NEW.publication_command::JSONB;
    item JSONB;
    item_source UUID;
    item_digest TEXT;
    item_extraction UUID;
    item_compiler TEXT;
    selected_claims JSONB;
    selected_procedures JSONB;
    inconsistent BOOLEAN;
BEGIN
    INSERT INTO evidence_bundle_publication_keys (tenant_id, bundle_id) VALUES (NEW.tenant_id, NEW.bundle_id)
    ON CONFLICT DO NOTHING;
    PERFORM 1 FROM evidence_bundle_publication_keys
    WHERE tenant_id = NEW.tenant_id AND bundle_id = NEW.bundle_id FOR UPDATE;
    IF manifest->>'schema' IS DISTINCT FROM 'ergon.dev/evidence-bundle/v1'
        OR (manifest->>'tenantId')::UUID IS DISTINCT FROM NEW.tenant_id
        OR (manifest->>'assessedAt')::TIMESTAMPTZ IS DISTINCT FROM NEW.assessed_at
        OR NEW.assessed_at > clock_timestamp()
        OR JSONB_TYPEOF(manifest->'proposals') IS DISTINCT FROM 'array'
        OR JSONB_TYPEOF(manifest->'sources') IS DISTINCT FROM 'array'
        OR JSONB_TYPEOF(manifest->'claimSets') IS DISTINCT FROM 'array'
        OR JSONB_TYPEOF(command->'claims') IS DISTINCT FROM 'array'
        OR JSONB_TYPEOF(command->'procedures') IS DISTINCT FROM 'array'
        OR JSONB_TYPEOF(command->'claimSets') IS DISTINCT FROM 'array'
        OR JSONB_ARRAY_LENGTH(manifest->'proposals') NOT BETWEEN 1 AND 420
        OR JSONB_ARRAY_LENGTH(manifest->'sources') NOT BETWEEN 1 AND 420
        OR JSONB_ARRAY_LENGTH(manifest->'claimSets') NOT BETWEEN 0 AND 20 THEN
        RAISE EXCEPTION 'invalid bundle manifest' USING ERRCODE = 'P7703';
    END IF;
    IF (SELECT COUNT(DISTINCT (p->>'kind', p->>'id')) FROM JSONB_ARRAY_ELEMENTS(manifest->'proposals') p)
            <> JSONB_ARRAY_LENGTH(manifest->'proposals')
        OR (SELECT COUNT(DISTINCT (s->>'sourceId', s->>'digest')) FROM JSONB_ARRAY_ELEMENTS(manifest->'sources') s)
            <> JSONB_ARRAY_LENGTH(manifest->'sources')
        OR (SELECT COUNT(DISTINCT (g->>'property', g->>'subject', g->>'scope'))
            FROM JSONB_ARRAY_ELEMENTS(manifest->'claimSets') g) <> JSONB_ARRAY_LENGTH(manifest->'claimSets')
        OR (SELECT COUNT(DISTINCT g->>'anchorId') FROM JSONB_ARRAY_ELEMENTS(command->'claimSets') g)
            <> JSONB_ARRAY_LENGTH(command->'claimSets') THEN
        RAISE EXCEPTION 'duplicate bundle references' USING ERRCODE = 'P7703';
    END IF;
    -- Acquire the same total order as repository admission, independent of client UUID ordering or database locale.
    FOR item IN SELECT value FROM JSONB_ARRAY_ELEMENTS(manifest->'claimSets')
        ORDER BY (value->>'property') COLLATE "C", (value->>'subject') COLLATE "C", (value->>'scope') COLLATE "C"
    LOOP
        PERFORM qualify_bundle_claim_set(NEW.tenant_id, item, manifest);
    END LOOP;
    FOR item IN SELECT value FROM JSONB_ARRAY_ELEMENTS(manifest->'proposals') LOOP
        IF JSONB_TYPEOF(item->'selected') IS DISTINCT FROM 'boolean' THEN
            RAISE EXCEPTION 'missing bundle selection flag' USING ERRCODE = 'P7703';
        END IF;
        IF item->>'kind' = 'CLAIM' THEN
            SELECT source_id, digest, extraction_id, compiler_revision
            INTO item_source, item_digest, item_extraction, item_compiler
            FROM evidence_claim_proposals WHERE tenant_id = NEW.tenant_id AND proposal_id = (item->>'id')::UUID;
            IF NOT FOUND OR NOT EXISTS (
                SELECT 1 FROM evidence_claim_proposals p, JSONB_ARRAY_ELEMENTS(manifest->'claimSets') g
                WHERE p.tenant_id = NEW.tenant_id AND p.proposal_id = (item->>'id')::UUID
                    AND p.property = g->>'property' AND p.subject = g->>'subject' AND p.scope = g->>'scope'
            ) THEN
                RAISE EXCEPTION 'missing bundle claim assessment' USING ERRCODE = 'P7702';
            END IF;
        ELSIF item->>'kind' = 'PROCEDURE' AND (item->>'selected')::BOOLEAN THEN
            SELECT source_id, digest, extraction_id, compiler_revision
            INTO item_source, item_digest, item_extraction, item_compiler
            FROM evidence_procedure_proposals WHERE tenant_id = NEW.tenant_id AND proposal_id = (item->>'id')::UUID;
        ELSE
            RAISE EXCEPTION 'invalid bundle proposal kind' USING ERRCODE = 'P7703';
        END IF;
        IF item_source IS DISTINCT FROM (item->>'sourceId')::UUID OR item_digest IS DISTINCT FROM item->>'digest'
            OR item_extraction IS DISTINCT FROM (item->>'extractionId')::UUID
            OR item_compiler IS DISTINCT FROM item->>'compilerRevision' OR NOT EXISTS (
                SELECT 1 FROM evidence_proposal_reviews r
                WHERE r.tenant_id = NEW.tenant_id AND r.command_id = (item->>'reviewCommandId')::UUID
                    AND r.proposal_kind = item->>'kind' AND r.proposal_id = (item->>'id')::UUID
                    AND r.extraction_id = item_extraction AND r.decision = 'ACCEPTED' AND r.reviewed_at <= NEW.assessed_at
            ) THEN
            RAISE EXCEPTION 'unaccepted or mismatched bundle proposal' USING ERRCODE = 'P7702';
        END IF;
    END LOOP;
    SELECT COALESCE(JSONB_AGG(proposal->'id' ORDER BY (proposal->>'id')::UUID), '[]'::JSONB) INTO selected_claims
    FROM JSONB_ARRAY_ELEMENTS(manifest->'proposals') AS entries(proposal)
    WHERE proposal->>'kind' = 'CLAIM' AND (proposal->>'selected')::BOOLEAN;
    SELECT COALESCE(JSONB_AGG(proposal->'id' ORDER BY (proposal->>'id')::UUID), '[]'::JSONB) INTO selected_procedures
    FROM JSONB_ARRAY_ELEMENTS(manifest->'proposals') AS entries(proposal)
    WHERE proposal->>'kind' = 'PROCEDURE' AND (proposal->>'selected')::BOOLEAN;
    IF selected_claims IS DISTINCT FROM command->'claims' OR selected_procedures IS DISTINCT FROM command->'procedures'
        OR JSONB_ARRAY_LENGTH(selected_claims) + JSONB_ARRAY_LENGTH(selected_procedures) NOT BETWEEN 1 AND 20
        OR JSONB_ARRAY_LENGTH(command->'claimSets') <> JSONB_ARRAY_LENGTH(manifest->'claimSets') THEN
        RAISE EXCEPTION 'bundle command selection mismatch' USING ERRCODE = 'P7703';
    END IF;
    FOR item IN SELECT value FROM JSONB_ARRAY_ELEMENTS(command->'claimSets') LOOP
        IF (SELECT COUNT(*) FROM JSONB_ARRAY_ELEMENTS(manifest->'claimSets') g
            WHERE g->'candidateIds' @> JSONB_BUILD_ARRAY(item->>'anchorId')
                AND g->'candidateIds' = item->'candidateIds' AND g->'revision' = item->'revision'
                AND g->'adjudicationId' IS NOT DISTINCT FROM item->'adjudicationId') <> 1 THEN
            RAISE EXCEPTION 'bundle assessment intent mismatch' USING ERRCODE = 'P7702';
        END IF;
    END LOOP;
    WITH required AS (
        SELECT (p->>'sourceId')::UUID AS source_id, p->>'digest' AS digest
        FROM JSONB_ARRAY_ELEMENTS(manifest->'proposals') p
        UNION
        SELECT o.source_id, o.digest FROM evidence_procedure_operation_sources o
        JOIN JSONB_ARRAY_ELEMENTS(manifest->'proposals') p
            ON p->>'kind' = 'PROCEDURE' AND (p->>'id')::UUID = o.proposal_id
        WHERE o.tenant_id = NEW.tenant_id
    ), declared AS (
        SELECT (s->>'sourceId')::UUID AS source_id, s->>'digest' AS digest FROM JSONB_ARRAY_ELEMENTS(manifest->'sources') s
    ), differences AS (
        (SELECT * FROM required EXCEPT SELECT * FROM declared)
        UNION ALL (SELECT * FROM declared EXCEPT SELECT * FROM required)
    ) SELECT EXISTS (SELECT 1 FROM differences) INTO inconsistent;
    IF inconsistent THEN RAISE EXCEPTION 'bundle source set mismatch' USING ERRCODE = 'P7703'; END IF;
    NEW.recorded_at := clock_timestamp();
    IF NOT EXISTS (
        SELECT 1 FROM approval_authority_evidence a WHERE a.tenant_id = NEW.tenant_id
            AND a.evidence_id = NEW.authority_evidence_id AND a.actor_id = NEW.published_by
            AND a.authority = 'RESOLVER' AND a.case_id IS NULL
            AND a.attested_at <= NEW.recorded_at AND a.expires_at > NEW.recorded_at
    ) THEN
        RAISE EXCEPTION 'current resolver authority required' USING ERRCODE = 'P7701';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_bundle_admission BEFORE INSERT ON evidence_bundles
FOR EACH ROW EXECUTE FUNCTION admit_evidence_bundle();

-- The immutable manifest and all relational links commit together; later additions cannot change its exact set.
CREATE FUNCTION validate_evidence_bundle_links() RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    manifest JSONB;
    inconsistent BOOLEAN;
BEGIN
    SELECT canonical_manifest::JSONB INTO STRICT manifest FROM evidence_bundles
    WHERE tenant_id = NEW.tenant_id AND bundle_id = NEW.bundle_id;
    WITH expected AS (
        SELECT p->>'kind' AS kind, (p->>'id')::UUID AS id, (p->>'reviewCommandId')::UUID AS review
        FROM JSONB_ARRAY_ELEMENTS(manifest->'proposals') p
    ), actual AS (
        SELECT kind, proposal_id AS id, review_command_id AS review FROM evidence_bundle_proposals
        WHERE tenant_id = NEW.tenant_id AND bundle_id = NEW.bundle_id
    ), differences AS (
        (SELECT * FROM expected EXCEPT SELECT * FROM actual) UNION ALL (SELECT * FROM actual EXCEPT SELECT * FROM expected)
    ) SELECT EXISTS (SELECT 1 FROM differences) INTO inconsistent;
    IF inconsistent THEN RAISE EXCEPTION 'incomplete bundle proposal links'; END IF;
    WITH expected AS (
        SELECT (s->>'sourceId')::UUID AS source_id, s->>'digest' AS digest FROM JSONB_ARRAY_ELEMENTS(manifest->'sources') s
    ), actual AS (
        SELECT source_id, digest FROM evidence_bundle_sources WHERE tenant_id = NEW.tenant_id AND bundle_id = NEW.bundle_id
    ), differences AS (
        (SELECT * FROM expected EXCEPT SELECT * FROM actual) UNION ALL (SELECT * FROM actual EXCEPT SELECT * FROM expected)
    ) SELECT EXISTS (SELECT 1 FROM differences) INTO inconsistent;
    IF inconsistent THEN RAISE EXCEPTION 'incomplete bundle source links'; END IF;
    WITH expected AS (
        SELECT (g->>'adjudicationId')::UUID AS id FROM JSONB_ARRAY_ELEMENTS(manifest->'claimSets') g
        WHERE g->>'adjudicationId' IS NOT NULL
    ), actual AS (
        SELECT adjudication_id AS id FROM evidence_bundle_adjudications WHERE tenant_id = NEW.tenant_id AND bundle_id = NEW.bundle_id
    ), differences AS (
        (SELECT * FROM expected EXCEPT SELECT * FROM actual) UNION ALL (SELECT * FROM actual EXCEPT SELECT * FROM expected)
    ) SELECT EXISTS (SELECT 1 FROM differences) INTO inconsistent;
    IF inconsistent THEN RAISE EXCEPTION 'incomplete bundle adjudication links'; END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_bundle_links_complete AFTER INSERT ON evidence_bundles
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_evidence_bundle_links();
CREATE CONSTRAINT TRIGGER trg_bundle_proposal_complete AFTER INSERT ON evidence_bundle_proposals
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_evidence_bundle_links();
CREATE CONSTRAINT TRIGGER trg_bundle_source_complete AFTER INSERT ON evidence_bundle_sources
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_evidence_bundle_links();
CREATE CONSTRAINT TRIGGER trg_bundle_adjudication_complete AFTER INSERT ON evidence_bundle_adjudications
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION validate_evidence_bundle_links();
CREATE TRIGGER trg_bundle_immutable BEFORE UPDATE OR DELETE ON evidence_bundles
FOR EACH ROW EXECUTE FUNCTION reject_evidence_review_mutation();
CREATE TRIGGER trg_bundle_proposal_immutable BEFORE UPDATE OR DELETE ON evidence_bundle_proposals
FOR EACH ROW EXECUTE FUNCTION reject_evidence_review_mutation();
CREATE TRIGGER trg_bundle_source_immutable BEFORE UPDATE OR DELETE ON evidence_bundle_sources
FOR EACH ROW EXECUTE FUNCTION reject_evidence_review_mutation();
CREATE TRIGGER trg_bundle_adjudication_immutable BEFORE UPDATE OR DELETE ON evidence_bundle_adjudications
FOR EACH ROW EXECUTE FUNCTION reject_evidence_review_mutation();
CREATE TRIGGER trg_bundle_key_immutable BEFORE UPDATE OR DELETE ON evidence_bundle_publication_keys
FOR EACH ROW EXECUTE FUNCTION reject_evidence_review_mutation();
