-- Immutable questions for the explicitly bound requester, not evidence or consent.
-- Authorship is derived from the exact CLAIMED event; recipient from case_requesters.
-- Additive empty table: no backfill, old-row scan or rewrite. FK DDL briefly locks
-- referenced tables. Keep schema/audit on rollback. This foundation exposes no route.
-- Current ownership/authority and OPEN case checks belong to the later command,
-- not these historical constraints or RLS. No response/delivery/runtime state is added.

CREATE TABLE requester_information_requests (
    tenant_id UUID NOT NULL,
    request_id UUID NOT NULL,
    work_item_id UUID NOT NULL,
    case_id UUID NOT NULL,
    ownership_revision BIGINT NOT NULL,
    ownership_event_type TEXT GENERATED ALWAYS AS ('CLAIMED') STORED,
    question TEXT NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_requester_information_requests PRIMARY KEY (tenant_id, request_id),
    CONSTRAINT fk_requester_information_requests_owner
        FOREIGN KEY (tenant_id, work_item_id, ownership_revision, ownership_event_type)
        REFERENCES human_follow_up_ownership_events (tenant_id, work_item_id, ownership_revision, event_type)
        ON DELETE RESTRICT,
    CONSTRAINT fk_requester_information_requests_recipient
        FOREIGN KEY (tenant_id, case_id) REFERENCES case_requesters (tenant_id, case_id) ON DELETE RESTRICT,
    CONSTRAINT ck_requester_information_requests_question
        CHECK (
            CHAR_LENGTH(question) BETWEEN 1 AND 4000
            -- Match Kotlin Char.isWhitespace independently of the database locale,
            -- including nonbreaking spaces that POSIX [:space:] can leave behind.
            AND BTRIM(question,
                U&'\0009\000A\000B\000C\000D\001C\001D\001E\001F\0020\00A0\1680'
                || U&'\2000\2001\2002\2003\2004\2005\2006\2007\2008\2009\200A\2028\2029\202F\205F\3000'
            ) <> ''
        )
);

-- Parent creation coordinates are immutable. Validate their relationship without
-- redundant run/recipient copies or new unique indexes over existing history.
CREATE FUNCTION check_requester_information_request_case()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM human_follow_up_work_items w
        JOIN resolution_runs r ON r.tenant_id = w.tenant_id AND r.run_id = w.run_id
        WHERE w.tenant_id = NEW.tenant_id AND w.work_item_id = NEW.work_item_id AND r.case_id = NEW.case_id
    ) THEN
        RAISE EXCEPTION 'information request case must match its work source' USING ERRCODE = '23503';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_requester_information_requests_case
BEFORE INSERT ON requester_information_requests
FOR EACH ROW EXECUTE FUNCTION check_requester_information_request_case();

CREATE FUNCTION prevent_requester_information_request_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'information requests are immutable; % is not supported', TG_OP USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_requester_information_requests_prevent_mutation
BEFORE UPDATE OR DELETE ON requester_information_requests
FOR EACH ROW EXECUTE FUNCTION prevent_requester_information_request_mutation();
CREATE TRIGGER trg_requester_information_requests_prevent_truncate
BEFORE TRUNCATE ON requester_information_requests
FOR EACH STATEMENT EXECUTE FUNCTION prevent_requester_information_request_mutation();

COMMENT ON TABLE requester_information_requests IS
    'Immutable requester-facing questions; no response, delivery, approval, evidence or proof meaning.';
COMMENT ON COLUMN requester_information_requests.request_id IS
    'Tenant-wide client intent identity. Reuse with changed work, claim or text conflicts.';
COMMENT ON COLUMN requester_information_requests.ownership_revision IS
    'Exact CLAIMED event supplies historical author/claim, not continuing ownership authority.';
COMMENT ON COLUMN requester_information_requests.case_id IS
    'Exact source case and immutable requester binding; no caller-selected recipient or copied identity.';
COMMENT ON COLUMN requester_information_requests.requested_at IS
    'Application authoring instant; recorded_at is the independent database recording instant.';
