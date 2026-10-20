-- Per-gateway-call audit, distinct from browser admission and the single winning
-- terminal receipt. Preparation commits before I/O but does not prove dispatch.
-- A separate immutable completion records this caller's return/exception and
-- measured elapsed time. Missing completion is not a live/in-flight assertion.
-- No backfill: historical/older-writer calls remain outside this audit.
-- Deploy schema before new writers/readers. New tables start empty; the
-- consumption lookup index scans existing rows and blocks writes while building.
-- Budget a maintenance window, index/WAL storage and retained audit growth.
-- Older explicit-column writers remain compatible. Retain audit on rollback;
-- never delete history or treat an incomplete preparation as permission to retry.

CREATE TABLE capability_invocation_preparations (
    tenant_id UUID NOT NULL,
    invocation_attempt_id UUID NOT NULL,
    authorization_consumption_id UUID NOT NULL,
    prepared_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_capability_invocation_preparations PRIMARY KEY (tenant_id, invocation_attempt_id),
    CONSTRAINT fk_capability_invocation_preparation_consumption
        FOREIGN KEY (tenant_id, authorization_consumption_id)
        REFERENCES capability_authorization_consumptions (tenant_id, authorization_consumption_id) ON DELETE RESTRICT
);

CREATE TABLE capability_invocation_completions (
    tenant_id UUID NOT NULL,
    invocation_attempt_id UUID NOT NULL,
    outcome TEXT NOT NULL,
    invocation_started_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ NOT NULL,
    elapsed_nanoseconds BIGINT NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_capability_invocation_completions PRIMARY KEY (tenant_id, invocation_attempt_id),
    CONSTRAINT fk_capability_invocation_completion_preparation
        FOREIGN KEY (tenant_id, invocation_attempt_id)
        REFERENCES capability_invocation_preparations (tenant_id, invocation_attempt_id) ON DELETE RESTRICT,
    CONSTRAINT ck_capability_invocation_completion_outcome CHECK (outcome IN ('SUCCEEDED', 'FAILED', 'UNKNOWN')),
    CONSTRAINT ck_capability_invocation_completion_elapsed CHECK (elapsed_nanoseconds >= 0)
    -- No wall-clock ordering constraint: UTC corrections cannot invalidate a
    -- separately measured monotonic duration or establish causal ordering.
);

-- Exact tenant/run lookup; the attempt index supports the bounded reservation
-- join and deterministic display order, not a causal or stable historical prefix.
CREATE INDEX ix_capability_consumptions_run_history
    ON capability_authorization_consumptions (tenant_id, run_id, authorization_consumption_id);
CREATE INDEX ix_capability_invocation_preparations_history
    ON capability_invocation_preparations (tenant_id, authorization_consumption_id, recorded_at, invocation_attempt_id);

CREATE FUNCTION prevent_capability_invocation_audit_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'capability invocation audit is immutable; cannot %', TG_OP USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_capability_invocation_preparations_immutable
    BEFORE UPDATE OR DELETE ON capability_invocation_preparations
    FOR EACH ROW EXECUTE FUNCTION prevent_capability_invocation_audit_mutation();
CREATE TRIGGER trg_capability_invocation_completions_immutable
    BEFORE UPDATE OR DELETE ON capability_invocation_completions
    FOR EACH ROW EXECUTE FUNCTION prevent_capability_invocation_audit_mutation();

COMMENT ON TABLE capability_invocation_preparations IS
    'Append-only reservation-attributed gateway preparation, committed before possible I/O; not proof of provider dispatch.';
COMMENT ON TABLE capability_invocation_completions IS
    'One caller gateway return/exception and monotonic duration, not the winning receipt or independent outcome proof.';
COMMENT ON COLUMN capability_invocation_completions.outcome IS
    'SUCCEEDED/FAILED are gateway-reported results; UNKNOWN is a thrown call with unresolved provider effects, never a terminal receipt.';
COMMENT ON COLUMN capability_invocation_completions.elapsed_nanoseconds IS
    'Non-negative monotonic gateway duration excluding database work; never inferred from UTC differences or used as cost.';
