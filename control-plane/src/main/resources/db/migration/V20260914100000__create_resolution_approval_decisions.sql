-- ============================================================================
-- Authenticated human decisions on approval requests
-- ============================================================================
--
-- Each row records one authenticated actor's immutable APPROVED or REJECTED
-- response to one approval request. The application accepts the response only
-- while both the request and its attributed authority evidence are current.
-- Dynamic time validity remains an application-clock decision; foreign keys
-- preserve the durable request, run, case, actor, evidence, and authority links.
--
-- A decision satisfies or rejects a human prerequisite. It is not a capability
-- grant, authorization record, run transition, or execution receipt.
-- ============================================================================


-- These redundant identities let decision foreign keys prove that copied audit
-- attributes originate from the same immutable rows rather than unrelated IDs.
ALTER TABLE resolution_runs
    ADD CONSTRAINT uq_resolution_runs_decision_subject
        UNIQUE (tenant_id, run_id, case_id);


ALTER TABLE resolution_approval_requests
    ADD CONSTRAINT uq_resolution_approval_requests_decision_subject
        UNIQUE (tenant_id, approval_request_id, run_id, required_authority);


ALTER TABLE approval_authority_evidence
    ADD CONSTRAINT uq_approval_authority_evidence_decision_subject
        UNIQUE (tenant_id, evidence_id, actor_id, authority);


CREATE TABLE resolution_approval_decisions (
    tenant_id UUID NOT NULL,
    approval_decision_id UUID NOT NULL,
    approval_request_id UUID NOT NULL,
    run_id UUID NOT NULL,
    case_id UUID NOT NULL,
    actor_id UUID NOT NULL,
    evidence_id UUID NOT NULL,
    authority TEXT NOT NULL,
    outcome TEXT NOT NULL,
    decided_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT pk_resolution_approval_decisions
        PRIMARY KEY (tenant_id, approval_decision_id),

    CONSTRAINT uq_resolution_approval_decisions_request
        UNIQUE (tenant_id, approval_request_id),

    CONSTRAINT fk_resolution_approval_decisions_request
        FOREIGN KEY (tenant_id, approval_request_id, run_id, authority)
        REFERENCES resolution_approval_requests (
            tenant_id, approval_request_id, run_id, required_authority
        )
        ON DELETE RESTRICT,

    CONSTRAINT fk_resolution_approval_decisions_run
        FOREIGN KEY (tenant_id, run_id, case_id)
        REFERENCES resolution_runs (tenant_id, run_id, case_id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_resolution_approval_decisions_evidence
        FOREIGN KEY (tenant_id, evidence_id, actor_id, authority)
        REFERENCES approval_authority_evidence (tenant_id, evidence_id, actor_id, authority)
        ON DELETE RESTRICT,

    CONSTRAINT ck_resolution_approval_decisions_authority
        CHECK (authority IN ('REQUESTER', 'RESOLVER')),

    CONSTRAINT ck_resolution_approval_decisions_outcome
        CHECK (outcome IN ('APPROVED', 'REJECTED'))
);


CREATE INDEX ix_resolution_approval_decisions_actor_recorded_at
    ON resolution_approval_decisions (tenant_id, actor_id, recorded_at DESC);


CREATE FUNCTION prevent_resolution_approval_decision_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'resolution approval decisions are immutable; a request can be answered only once instead of using %',
        TG_OP
        USING ERRCODE = '55000';
END;
$$;


CREATE TRIGGER trg_resolution_approval_decisions_prevent_mutation
BEFORE UPDATE OR DELETE
ON resolution_approval_decisions
FOR EACH ROW
EXECUTE FUNCTION prevent_resolution_approval_decision_mutation();


COMMENT ON TABLE resolution_approval_decisions IS
    'Immutable authenticated human responses to current resolution approval requests.';

COMMENT ON COLUMN resolution_approval_decisions.approval_request_id IS
    'Request answered exactly once by this decision.';

COMMENT ON COLUMN resolution_approval_decisions.evidence_id IS
    'Authority attestation evaluated as current when the application accepted the decision.';

COMMENT ON COLUMN resolution_approval_decisions.outcome IS
    'Authenticated human response: APPROVED or REJECTED; not an execution authorization.';

COMMENT ON COLUMN resolution_approval_decisions.decided_at IS
    'Application-clock instant used to evaluate request and authority-evidence validity.';

COMMENT ON COLUMN resolution_approval_decisions.recorded_at IS
    'Database instant at which the immutable approval decision became durable.';
