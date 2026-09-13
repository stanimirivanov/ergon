-- ============================================================================
-- Human identities and attributable approval-authority evidence
-- ============================================================================
--
-- human_actors binds a tenant-scoped Ergon identity to one opaque external
-- identity-provider subject. approval_authority_evidence records immutable,
-- time-bounded attestations about those actors. REQUESTER evidence is scoped
-- to one case; RESOLVER evidence is tenant-wide.
--
-- These tables provide evidence only. They do not authenticate an HTTP caller,
-- record an approval decision, or authorize resolution-run execution.
-- ============================================================================


CREATE TABLE human_actors (
    tenant_id UUID NOT NULL,
    actor_id UUID NOT NULL,
    identity_provider TEXT NOT NULL,
    identity_subject TEXT NOT NULL,
    registered_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT pk_human_actors
        PRIMARY KEY (tenant_id, actor_id),

    CONSTRAINT uq_human_actors_external_identity
        UNIQUE (tenant_id, identity_provider, identity_subject),

    CONSTRAINT ck_human_actors_identity_provider
        CHECK (
            char_length(identity_provider) BETWEEN 1 AND 100
            AND identity_provider ~ '^[a-z0-9][a-z0-9._-]*$'
        ),

    CONSTRAINT ck_human_actors_identity_subject
        CHECK (char_length(identity_subject) BETWEEN 1 AND 255)
);


CREATE TABLE approval_authority_evidence (
    tenant_id UUID NOT NULL,
    evidence_id UUID NOT NULL,
    actor_id UUID NOT NULL,
    authority TEXT NOT NULL,
    case_id UUID,
    source_provider TEXT NOT NULL,
    source_reference TEXT NOT NULL,
    attested_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT pk_approval_authority_evidence
        PRIMARY KEY (tenant_id, evidence_id),

    CONSTRAINT fk_approval_authority_evidence_actor
        FOREIGN KEY (tenant_id, actor_id)
        REFERENCES human_actors (tenant_id, actor_id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_approval_authority_evidence_case
        FOREIGN KEY (tenant_id, case_id)
        REFERENCES cases (tenant_id, case_id)
        ON DELETE RESTRICT,

    CONSTRAINT ck_approval_authority_evidence_scope
        CHECK (
            (authority = 'REQUESTER' AND case_id IS NOT NULL)
            OR (authority = 'RESOLVER' AND case_id IS NULL)
        ),

    CONSTRAINT ck_approval_authority_evidence_source_provider
        CHECK (
            char_length(source_provider) BETWEEN 1 AND 100
            AND source_provider ~ '^[a-z0-9][a-z0-9._-]*$'
        ),

    CONSTRAINT ck_approval_authority_evidence_source_reference
        CHECK (char_length(source_reference) BETWEEN 1 AND 500),

    CONSTRAINT ck_approval_authority_evidence_validity
        CHECK (
            expires_at > attested_at
            AND expires_at <= attested_at + INTERVAL '24 hours'
        )
);


CREATE INDEX ix_approval_authority_evidence_actor_expiry
    ON approval_authority_evidence (tenant_id, actor_id, expires_at DESC);


CREATE INDEX ix_approval_authority_evidence_case_authority_expiry
    ON approval_authority_evidence (tenant_id, case_id, authority, expires_at DESC)
    WHERE case_id IS NOT NULL;


CREATE FUNCTION prevent_human_actor_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'human actors are immutable; register a new identity instead of using %',
        TG_OP
        USING ERRCODE = '55000';
END;
$$;


CREATE TRIGGER trg_human_actors_prevent_mutation
BEFORE UPDATE OR DELETE
ON human_actors
FOR EACH ROW
EXECUTE FUNCTION prevent_human_actor_mutation();


CREATE FUNCTION prevent_approval_authority_evidence_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'approval authority evidence is immutable; append a new attestation instead of using %',
        TG_OP
        USING ERRCODE = '55000';
END;
$$;


CREATE TRIGGER trg_approval_authority_evidence_prevent_mutation
BEFORE UPDATE OR DELETE
ON approval_authority_evidence
FOR EACH ROW
EXECUTE FUNCTION prevent_approval_authority_evidence_mutation();


COMMENT ON TABLE human_actors IS
    'Immutable tenant-scoped bindings to opaque external human identity subjects.';

COMMENT ON COLUMN human_actors.identity_subject IS
    'Opaque provider-local subject identifier; never a credential or bearer token.';

COMMENT ON COLUMN human_actors.registered_at IS
    'Application instant at which Ergon registered the external identity binding.';

COMMENT ON COLUMN human_actors.recorded_at IS
    'Database instant at which the immutable actor identity became durable.';

COMMENT ON TABLE approval_authority_evidence IS
    'Immutable, expiring external attestations of requester or resolver authority.';

COMMENT ON COLUMN approval_authority_evidence.case_id IS
    'Requester-authority case scope; NULL only for tenant-wide resolver authority.';

COMMENT ON COLUMN approval_authority_evidence.source_reference IS
    'Opaque pointer to the attesting provider record; not authentication material.';

COMMENT ON COLUMN approval_authority_evidence.attested_at IS
    'Application instant at which Ergon accepted the external attestation.';

COMMENT ON COLUMN approval_authority_evidence.expires_at IS
    'Exclusive validity end; the evidence is expired at and after this instant.';

COMMENT ON COLUMN approval_authority_evidence.recorded_at IS
    'Database instant at which the immutable attestation became durable.';
