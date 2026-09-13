-- ============================================================================
-- Immutable, expiring requests for human approval
-- ============================================================================
--
-- Each row asks for the human authority pinned by one resolution-run start.
-- Requests are append-only audit history: expiry is derived from timestamps,
-- and an expired row remains intact when a replacement is requested.
--
-- Creation locks the referenced run and checks the latest expiry before insert.
-- That ordering serializes concurrent requests without pretending a time-based
-- partial unique index could determine whether a request is active.
-- ============================================================================


-- This redundant identity lets the foreign key below prove that a request
-- copies the step and authority from the same tenant-scoped run.
ALTER TABLE resolution_runs
    ADD CONSTRAINT uq_resolution_runs_approval_subject
        UNIQUE (tenant_id, run_id, step_id, required_approval);


CREATE TABLE resolution_approval_requests (
    tenant_id UUID NOT NULL,
    approval_request_id UUID NOT NULL,
    run_id UUID NOT NULL,
    step_id TEXT NOT NULL,
    required_authority TEXT NOT NULL,
    requested_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT pk_resolution_approval_requests
        PRIMARY KEY (tenant_id, approval_request_id),

    CONSTRAINT fk_resolution_approval_requests_run
        FOREIGN KEY (tenant_id, run_id, step_id, required_authority)
        REFERENCES resolution_runs (tenant_id, run_id, step_id, required_approval)
        ON DELETE RESTRICT,

    CONSTRAINT ck_resolution_approval_requests_authority
        CHECK (required_authority IN ('REQUESTER', 'RESOLVER')),

    CONSTRAINT ck_resolution_approval_requests_validity
        CHECK (
            expires_at > requested_at
            AND expires_at <= requested_at + INTERVAL '24 hours'
        )
);


CREATE INDEX ix_resolution_approval_requests_run_recorded_at
    ON resolution_approval_requests (tenant_id, run_id, recorded_at DESC);


CREATE FUNCTION prevent_resolution_approval_request_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'resolution approval requests are immutable; append a replacement after expiry instead of using %',
        TG_OP
        USING ERRCODE = '55000';
END;
$$;


CREATE TRIGGER trg_resolution_approval_requests_prevent_mutation
BEFORE UPDATE OR DELETE
ON resolution_approval_requests
FOR EACH ROW
EXECUTE FUNCTION prevent_resolution_approval_request_mutation();


COMMENT ON TABLE resolution_approval_requests IS
    'Immutable tenant-scoped requests for human authority required by a resolution-run step.';

COMMENT ON COLUMN resolution_approval_requests.requested_at IS
    'Application-clock start of the half-open approval-request validity interval.';

COMMENT ON COLUMN resolution_approval_requests.expires_at IS
    'Exclusive end of request validity; status is EXPIRED at and after this instant.';

COMMENT ON COLUMN resolution_approval_requests.recorded_at IS
    'Database instant at which the immutable approval request became durable.';
