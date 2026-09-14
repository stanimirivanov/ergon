-- ============================================================================
-- Narrow capability authorization derived from approved human decisions
-- ============================================================================
--
-- Each immutable row authorizes one future attempt of the exact capability and
-- run step approved while the originating request remained current. The grant
-- inherits that request's expiry; no caller can extend the approval window.
-- Composite foreign keys prove that decision, request, run, case, policy, step,
-- capability, outcome, and expiry all belong to the same immutable history.
--
-- This table records authorization only. Grant consumption, connector
-- credentials, invocation, idempotency, and receipts remain separate records.
-- ============================================================================


ALTER TABLE resolution_runs
    ADD CONSTRAINT uq_resolution_runs_authorization_scope
        UNIQUE (tenant_id, run_id, case_id, policy_revision, step_id, capability);


ALTER TABLE resolution_approval_requests
    ADD CONSTRAINT uq_resolution_approval_requests_authorization_scope
        UNIQUE (tenant_id, approval_request_id, run_id, step_id, expires_at);


ALTER TABLE resolution_approval_decisions
    ADD CONSTRAINT uq_resolution_approval_decisions_authorization_source
        UNIQUE (
            tenant_id, approval_decision_id, approval_request_id,
            run_id, case_id, outcome
        );


CREATE TABLE capability_authorization_grants (
    tenant_id UUID NOT NULL,
    authorization_grant_id UUID NOT NULL,
    approval_decision_id UUID NOT NULL,
    approval_request_id UUID NOT NULL,
    run_id UUID NOT NULL,
    case_id UUID NOT NULL,
    policy_revision TEXT NOT NULL,
    step_id TEXT NOT NULL,
    capability TEXT NOT NULL,
    decision_outcome TEXT NOT NULL,
    authorized_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT pk_capability_authorization_grants
        PRIMARY KEY (tenant_id, authorization_grant_id),

    CONSTRAINT uq_capability_authorization_grants_decision
        UNIQUE (tenant_id, approval_decision_id),

    CONSTRAINT fk_capability_authorization_grants_decision
        FOREIGN KEY (
            tenant_id, approval_decision_id, approval_request_id,
            run_id, case_id, decision_outcome
        )
        REFERENCES resolution_approval_decisions (
            tenant_id, approval_decision_id, approval_request_id,
            run_id, case_id, outcome
        )
        ON DELETE RESTRICT,

    CONSTRAINT fk_capability_authorization_grants_request
        FOREIGN KEY (tenant_id, approval_request_id, run_id, step_id, expires_at)
        REFERENCES resolution_approval_requests (
            tenant_id, approval_request_id, run_id, step_id, expires_at
        )
        ON DELETE RESTRICT,

    CONSTRAINT fk_capability_authorization_grants_run
        FOREIGN KEY (
            tenant_id, run_id, case_id, policy_revision, step_id, capability
        )
        REFERENCES resolution_runs (
            tenant_id, run_id, case_id, policy_revision, step_id, capability
        )
        ON DELETE RESTRICT,

    CONSTRAINT ck_capability_authorization_grants_approved
        CHECK (decision_outcome = 'APPROVED'),

    CONSTRAINT ck_capability_authorization_grants_validity
        CHECK (expires_at > authorized_at)
);


CREATE INDEX ix_capability_authorization_grants_expiry
    ON capability_authorization_grants (tenant_id, expires_at);


CREATE FUNCTION prevent_capability_authorization_grant_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'capability authorization grants are immutable; create a new approved request instead of using %',
        TG_OP
        USING ERRCODE = '55000';
END;
$$;


CREATE TRIGGER trg_capability_authorization_grants_prevent_mutation
BEFORE UPDATE OR DELETE
ON capability_authorization_grants
FOR EACH ROW
EXECUTE FUNCTION prevent_capability_authorization_grant_mutation();


COMMENT ON TABLE capability_authorization_grants IS
    'Immutable, bounded authorizations for one future consumption of an exact run capability.';

COMMENT ON COLUMN capability_authorization_grants.approval_decision_id IS
    'Approved human decision that may produce exactly one authorization grant.';

COMMENT ON COLUMN capability_authorization_grants.policy_revision IS
    'Policy snapshot identity copied from the immutable resolution run.';

COMMENT ON COLUMN capability_authorization_grants.capability IS
    'Exact qualified capability authorized for the pinned run step.';

COMMENT ON COLUMN capability_authorization_grants.decision_outcome IS
    'Copied APPROVED outcome enforced by a decision foreign key and check constraint.';

COMMENT ON COLUMN capability_authorization_grants.expires_at IS
    'Exclusive validity boundary inherited from the originating approval request.';

COMMENT ON COLUMN capability_authorization_grants.recorded_at IS
    'Database instant at which the immutable authorization became durable.';
