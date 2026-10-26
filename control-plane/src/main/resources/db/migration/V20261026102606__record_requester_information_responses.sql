-- One immutable answer per requester question, not a fact, approval or proof.
-- Case and requester attribution derive from immutable questions/case_requesters.
-- Additive empty table: no backfill, old-row scan or rewrite. FK creation briefly
-- locks the question table. Retain audit/schema on rollback; no HTTP producer yet.
-- Future commands own current access, OPEN-case locking and exact replay checks;
-- these historical constraints are not authorization or RLS.

CREATE TABLE requester_information_responses (
    tenant_id UUID NOT NULL,
    response_id UUID NOT NULL,
    request_id UUID NOT NULL,
    answer TEXT NOT NULL,
    responded_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_requester_information_responses PRIMARY KEY (tenant_id, response_id),
    CONSTRAINT uq_requester_information_responses_question UNIQUE (tenant_id, request_id),
    CONSTRAINT fk_requester_information_responses_question
        FOREIGN KEY (tenant_id, request_id)
        REFERENCES requester_information_requests (tenant_id, request_id) ON DELETE RESTRICT,
    CONSTRAINT ck_requester_information_responses_answer
        CHECK (
            CHAR_LENGTH(answer) BETWEEN 1 AND 4000
            -- Match Kotlin whitespace independently of database locale, including NBSP.
            AND BTRIM(answer,
                U&'\0009\000A\000B\000C\000D\001C\001D\001E\001F\0020\00A0\1680'
                || U&'\2000\2001\2002\2003\2004\2005\2006\2007\2008\2009\200A\2028\2029\202F\205F\3000'
            ) <> ''
        )
);

CREATE FUNCTION prevent_requester_information_response_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'information responses are immutable; % is not supported', TG_OP USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_requester_information_responses_prevent_mutation
BEFORE UPDATE OR DELETE ON requester_information_responses
FOR EACH ROW EXECUTE FUNCTION prevent_requester_information_response_mutation();
CREATE TRIGGER trg_requester_information_responses_prevent_truncate
BEFORE TRUNCATE ON requester_information_responses
FOR EACH STATEMENT EXECUTE FUNCTION prevent_requester_information_response_mutation();

COMMENT ON TABLE requester_information_responses IS
    'Immutable untrusted requester answers; no semantic fact, consent, approval, delivery or proof meaning.';
COMMENT ON COLUMN requester_information_responses.response_id IS
    'Tenant-wide immutable intent identity; acknowledgement requires exact authorized comparison.';
COMMENT ON COLUMN requester_information_responses.request_id IS
    'One final answer per question. Immutable parents supply case and bound requester attribution.';
COMMENT ON COLUMN requester_information_responses.responded_at IS
    'Server application acceptance instant, not a caller timestamp or independent verification; recorded_at is database time.';
