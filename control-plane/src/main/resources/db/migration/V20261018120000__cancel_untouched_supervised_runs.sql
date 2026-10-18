-- Untouched-run termination, not provider cancellation.
-- Starts/approval history remain immutable. Write order is shared run fence,
-- command key, state lock, event, terminal projection, attributed command audit.
-- Replacing CHECK constraints scans state/events under write-conflicting locks;
-- supporting UNIQUE builds scan events and consume disk/WAL. No data backfill.
-- Deploy every reader/writer fence and compatible UI before enabling commands.
-- Before installing these triggers, upgrade ALL authorization writers with
-- Flyway held at V20261017120000, or quiesce writes for the whole deployment.
-- Old child-first writers can invert the new run fence even with commands off.
-- See ADR 0056; writer downgrade is unsafe from this migration onward.
-- Disable commands for rollback, retain history, and recover forward.

ALTER TABLE resolution_run_states
    DROP CONSTRAINT ck_resolution_run_states_state,
    DROP CONSTRAINT ck_resolution_run_states_transition_shape,
    ADD CONSTRAINT ck_resolution_run_states_state CHECK (state IN (
        'WAITING_FOR_APPROVAL', 'READY_FOR_AUTHORIZATION', 'VERIFYING', 'ACTION_FAILED',
        'VERIFIED_RESOLVED', 'SUPERSEDED', 'ESCALATED', 'CANCELLED'
    )),
    ADD CONSTRAINT ck_resolution_run_states_transition_shape CHECK (
        (version = 0 AND state IN ('WAITING_FOR_APPROVAL', 'READY_FOR_AUTHORIZATION'))
        OR (version = 1 AND state IN ('VERIFYING', 'ACTION_FAILED', 'CANCELLED'))
        OR (version = 2 AND state IN ('VERIFIED_RESOLVED', 'SUPERSEDED', 'ESCALATED'))
    );

ALTER TABLE resolution_run_events
    DROP CONSTRAINT ck_resolution_run_events_shape,
    ADD CONSTRAINT ck_resolution_run_events_shape CHECK (
        (sequence = 1 AND from_state IN ('WAITING_FOR_APPROVAL', 'READY_FOR_AUTHORIZATION')
            AND replacement_run_id IS NULL AND (
                (event_type = 'CAPABILITY_SUCCEEDED' AND receipt_outcome = 'SUCCEEDED'
                    AND authorization_consumption_id IS NOT NULL AND to_state = 'VERIFYING')
                OR (event_type = 'CAPABILITY_FAILED' AND receipt_outcome = 'FAILED'
                    AND authorization_consumption_id IS NOT NULL AND to_state = 'ACTION_FAILED')
                OR (event_type = 'RUN_CANCELLED' AND receipt_outcome IS NULL
                    AND authorization_consumption_id IS NULL AND to_state = 'CANCELLED')
            ))
        OR (sequence = 2 AND receipt_outcome IS NULL AND authorization_consumption_id IS NULL AND (
            (event_type = 'OUTCOME_PROOF_ACCEPTED' AND from_state = 'VERIFYING'
                AND to_state = 'VERIFIED_RESOLVED' AND replacement_run_id IS NULL)
            OR (event_type = 'RETRY_STARTED' AND from_state = 'ACTION_FAILED'
                AND to_state = 'SUPERSEDED' AND replacement_run_id IS NOT NULL)
            OR (event_type = 'ESCALATION_REQUESTED' AND from_state = 'ACTION_FAILED'
                AND to_state = 'ESCALATED' AND replacement_run_id IS NULL)
        ))
    ),
    ADD CONSTRAINT uq_run_cancellation_event UNIQUE (tenant_id, run_id, event_id, event_type, occurred_at);

CREATE TABLE resolution_run_cancellation_commands (
    tenant_id UUID NOT NULL,
    command_id UUID NOT NULL,
    run_id UUID NOT NULL,
    actor_id UUID NOT NULL,
    supervision_revision BIGINT NOT NULL,
    supervised_at TIMESTAMPTZ NOT NULL,
    expected_run_version BIGINT NOT NULL,
    authority_evidence_id UUID NOT NULL,
    authority TEXT NOT NULL DEFAULT 'RESOLVER',
    event_id UUID NOT NULL,
    event_type TEXT NOT NULL DEFAULT 'RUN_CANCELLED',
    reason TEXT NOT NULL DEFAULT 'OPERATOR_REQUESTED',
    cancelled_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_resolution_run_cancellation_commands PRIMARY KEY (tenant_id, command_id),
    CONSTRAINT uq_run_cancellation_command_run UNIQUE (tenant_id, run_id),
    CONSTRAINT ck_run_cancellation_version CHECK (expected_run_version = 0),
    CONSTRAINT ck_run_cancellation_revision CHECK (supervision_revision BETWEEN 0 AND 9007199254740990),
    CONSTRAINT ck_run_cancellation_kind CHECK (authority = 'RESOLVER' AND event_type = 'RUN_CANCELLED' AND reason = 'OPERATOR_REQUESTED'),
    CONSTRAINT fk_run_cancellation_supervision FOREIGN KEY (tenant_id, run_id, supervision_revision, actor_id, supervised_at)
        REFERENCES resolution_run_supervision_events (tenant_id, run_id, supervision_revision, supervisor_actor_id, occurred_at)
        ON DELETE RESTRICT,
    CONSTRAINT fk_run_cancellation_authority FOREIGN KEY (tenant_id, authority_evidence_id, actor_id, authority)
        REFERENCES approval_authority_evidence (tenant_id, evidence_id, actor_id, authority) ON DELETE RESTRICT,
    CONSTRAINT fk_run_cancellation_event FOREIGN KEY (tenant_id, run_id, event_id, event_type, cancelled_at)
        REFERENCES resolution_run_events (tenant_id, run_id, event_id, event_type, occurred_at) ON DELETE RESTRICT
);

CREATE FUNCTION prevent_run_cancellation_command_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'run cancellation commands are immutable; cannot %', TG_OP USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_run_cancellation_commands_prevent_mutation
BEFORE UPDATE OR DELETE ON resolution_run_cancellation_commands
FOR EACH ROW EXECUTE FUNCTION prevent_run_cancellation_command_mutation();

-- Defense in depth for insert adapters/older writers. Upgraded application
-- lookup locks take this run fence BEFORE child locks, avoiding lock inversion.
-- Historical reads and exact acknowledgements do not INSERT and remain intact.
CREATE FUNCTION reject_cancelled_run_authorization()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    PERFORM run_id FROM resolution_runs
    WHERE tenant_id = NEW.tenant_id AND run_id = NEW.run_id FOR NO KEY UPDATE;
    IF EXISTS (SELECT 1 FROM resolution_run_states
        WHERE tenant_id = NEW.tenant_id AND run_id = NEW.run_id AND state = 'CANCELLED') THEN
        RAISE EXCEPTION 'cancelled run cannot acquire new authorization' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_approval_requests_cancelled_run BEFORE INSERT ON resolution_approval_requests
FOR EACH ROW EXECUTE FUNCTION reject_cancelled_run_authorization();
CREATE TRIGGER trg_approval_decisions_cancelled_run BEFORE INSERT ON resolution_approval_decisions
FOR EACH ROW EXECUTE FUNCTION reject_cancelled_run_authorization();
CREATE TRIGGER trg_authorization_grants_cancelled_run BEFORE INSERT ON capability_authorization_grants
FOR EACH ROW EXECUTE FUNCTION reject_cancelled_run_authorization();
CREATE TRIGGER trg_authorization_consumptions_cancelled_run BEFORE INSERT ON capability_authorization_consumptions
FOR EACH ROW EXECUTE FUNCTION reject_cancelled_run_authorization();
CREATE TRIGGER trg_execution_commands_cancelled_run BEFORE INSERT ON resolution_run_execution_commands
FOR EACH ROW EXECUTE FUNCTION reject_cancelled_run_authorization();
CREATE TRIGGER trg_execution_attempts_cancelled_run BEFORE INSERT ON resolution_run_execution_attempts
FOR EACH ROW EXECUTE FUNCTION reject_cancelled_run_authorization();

COMMENT ON TABLE resolution_run_cancellation_commands IS
    'Immutable supervisor/resolver cancellation of an untouched run; never revocation or provider cancellation.';
COMMENT ON COLUMN resolution_run_cancellation_commands.supervised_at IS
    'Exact historical supervisor epoch, not command time or proof of current access.';
COMMENT ON COLUMN resolution_run_cancellation_commands.reason IS
    'Server-owned OPERATOR_REQUESTED reason, with no free-text or private evidence payload.';
COMMENT ON COLUMN resolution_run_cancellation_commands.cancelled_at IS
    'Server occurrence instant linked to the sole RUN_CANCELLED event; distinct from database recording time.';
