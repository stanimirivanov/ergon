-- Explicit initial requester ownership, independent of approval authority.
-- Authoritative and immutable: legacy cases remain unowned, never backfilled
-- from observations, subjects or approval evidence. Trusted operators bootstrap
-- reviewed bindings until authenticated intake records them atomically (M05-PR23).
-- Additive empty table: no old-row rewrite or index scan. FK creation takes brief
-- referenced-table DDL locks; preserve bindings on application rollback.

CREATE TABLE case_requesters (
    tenant_id UUID NOT NULL,
    case_id UUID NOT NULL,
    requester_actor_id UUID NOT NULL,
    source_reference TEXT NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT pk_case_requesters PRIMARY KEY (tenant_id, case_id),
    CONSTRAINT fk_case_requesters_case FOREIGN KEY (tenant_id, case_id)
        REFERENCES cases (tenant_id, case_id) ON DELETE RESTRICT,
    CONSTRAINT fk_case_requesters_actor FOREIGN KEY (tenant_id, requester_actor_id)
        REFERENCES human_actors (tenant_id, actor_id) ON DELETE RESTRICT,
    CONSTRAINT ck_case_requesters_source_reference
        CHECK (char_length(source_reference) BETWEEN 1 AND 500 AND source_reference ~ '[^[:space:]]')
);

CREATE FUNCTION prevent_case_requester_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'case requester bindings are immutable; % is not supported', TG_OP
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_case_requesters_prevent_mutation
BEFORE UPDATE OR DELETE ON case_requesters
FOR EACH ROW EXECUTE FUNCTION prevent_case_requester_mutation();

CREATE TRIGGER trg_case_requesters_prevent_truncate
BEFORE TRUNCATE ON case_requesters
FOR EACH STATEMENT EXECUTE FUNCTION prevent_case_requester_mutation();

COMMENT ON TABLE case_requesters IS
    'Authoritative initial requester of one tenant case; not approval evidence, delegation or resolver ownership.';
COMMENT ON COLUMN case_requesters.source_reference IS
    'Trusted provisioning audit reference, never browser-supplied ownership evidence or public progress content.';
COMMENT ON COLUMN case_requesters.recorded_at IS
    'Database binding recording time, not the case opening or an approval decision time.';
