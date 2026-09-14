-- ============================================================================
-- Idempotent capability invocation receipts
-- ============================================================================
--
-- Each row preserves the terminal result reported by the connector for one
-- authorization consumption. The consumption identity is also the provider
-- idempotency key, allowing a process to retry an uncertain call without
-- creating a different external operation.
--
-- A receipt is evidence of the connector response, not independent outcome
-- proof and not current run state. It may be recorded after grant expiry because
-- the preceding consumption reserved the authorization while it was current.
-- ============================================================================


ALTER TABLE capability_authorization_consumptions
    ADD CONSTRAINT uq_capability_authorization_consumptions_invocation_scope
        UNIQUE (
            tenant_id, authorization_consumption_id, authorization_grant_id,
            run_id, case_id, policy_revision, step_id, capability, connector,
            consumed_at
        );


CREATE TABLE capability_invocation_receipts (
    tenant_id UUID NOT NULL,
    authorization_consumption_id UUID NOT NULL,
    authorization_grant_id UUID NOT NULL,
    run_id UUID NOT NULL,
    case_id UUID NOT NULL,
    policy_revision TEXT NOT NULL,
    step_id TEXT NOT NULL,
    capability TEXT NOT NULL,
    connector TEXT NOT NULL,
    idempotency_key UUID NOT NULL,
    outcome TEXT NOT NULL,
    provider_operation_reference TEXT NOT NULL,
    consumption_consumed_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT pk_capability_invocation_receipts
        PRIMARY KEY (tenant_id, authorization_consumption_id),

    CONSTRAINT uq_capability_invocation_receipts_provider_operation
        UNIQUE (tenant_id, connector, provider_operation_reference),

    CONSTRAINT fk_capability_invocation_receipts_consumption
        FOREIGN KEY (
            tenant_id, authorization_consumption_id, authorization_grant_id,
            run_id, case_id, policy_revision, step_id, capability, connector,
            consumption_consumed_at
        )
        REFERENCES capability_authorization_consumptions (
            tenant_id, authorization_consumption_id, authorization_grant_id,
            run_id, case_id, policy_revision, step_id, capability, connector,
            consumed_at
        )
        ON DELETE RESTRICT,

    CONSTRAINT ck_capability_invocation_receipts_idempotency_key
        CHECK (idempotency_key = authorization_consumption_id),

    CONSTRAINT ck_capability_invocation_receipts_outcome
        CHECK (outcome IN ('SUCCEEDED', 'FAILED')),

    CONSTRAINT ck_capability_invocation_receipts_provider_reference
        CHECK (
            char_length(provider_operation_reference) BETWEEN 1 AND 500
            AND provider_operation_reference = btrim(provider_operation_reference)
        ),

    CONSTRAINT ck_capability_invocation_receipts_completion
        CHECK (completed_at >= consumption_consumed_at)
);


CREATE FUNCTION prevent_capability_invocation_receipt_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'capability invocation receipts are immutable; retain the original connector result instead of using %',
        TG_OP
        USING ERRCODE = '55000';
END;
$$;


CREATE TRIGGER trg_capability_invocation_receipts_prevent_mutation
BEFORE UPDATE OR DELETE
ON capability_invocation_receipts
FOR EACH ROW
EXECUTE FUNCTION prevent_capability_invocation_receipt_mutation();


COMMENT ON TABLE capability_invocation_receipts IS
    'Immutable terminal connector results for idempotently invoked authorization consumptions.';

COMMENT ON COLUMN capability_invocation_receipts.idempotency_key IS
    'Stable provider key equal to the authorization consumption identity and reused by retries.';

COMMENT ON COLUMN capability_invocation_receipts.outcome IS
    'Terminal result reported by the connector; not independent outcome verification.';

COMMENT ON COLUMN capability_invocation_receipts.provider_operation_reference IS
    'Opaque connector-owned reference to the idempotent provider operation.';

COMMENT ON COLUMN capability_invocation_receipts.completed_at IS
    'Application-clock instant at which the connector returned its terminal result.';

COMMENT ON COLUMN capability_invocation_receipts.recorded_at IS
    'Database instant at which the immutable connector receipt became durable.';
