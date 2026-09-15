-- ============================================================================
-- Explicit human follow-up for exhausted failed runs
-- ============================================================================
--
-- A resolver may terminate automated recovery only after the versioned retry
-- policy denies another attempt. ESCALATION_REQUESTED records that decision,
-- its actor and authority evidence, and the exact denial inputs. It changes the
-- run projection to ESCALATED but deliberately leaves the case open.
--
-- This migration takes table locks and validates existing rows while replacing
-- state/event shape constraints. Existing history is not rewritten.
-- ============================================================================


ALTER TABLE resolution_run_states
    DROP CONSTRAINT ck_resolution_run_states_state,
    DROP CONSTRAINT ck_resolution_run_states_transition_shape;

ALTER TABLE resolution_run_states
    ADD CONSTRAINT ck_resolution_run_states_state
        CHECK (state IN (
            'WAITING_FOR_APPROVAL', 'READY_FOR_AUTHORIZATION',
            'VERIFYING', 'ACTION_FAILED', 'VERIFIED_RESOLVED', 'SUPERSEDED',
            'ESCALATED'
        )),
    ADD CONSTRAINT ck_resolution_run_states_transition_shape
        CHECK (
            (version = 0 AND state IN ('WAITING_FOR_APPROVAL', 'READY_FOR_AUTHORIZATION'))
            OR (version = 1 AND state IN ('VERIFYING', 'ACTION_FAILED'))
            OR (version = 2 AND state IN ('VERIFIED_RESOLVED', 'SUPERSEDED', 'ESCALATED'))
        );


ALTER TABLE resolution_run_events
    DROP CONSTRAINT ck_resolution_run_events_shape,
    ADD COLUMN escalation_actor_id UUID,
    ADD COLUMN escalation_authority_evidence_id UUID,
    ADD COLUMN escalation_authority TEXT
        GENERATED ALWAYS AS (
            CASE WHEN event_type = 'ESCALATION_REQUESTED' THEN 'RESOLVER'::TEXT END
        ) STORED,
    ADD COLUMN escalation_reason TEXT,
    ADD COLUMN escalation_policy_revision TEXT,
    ADD COLUMN escalation_source_attempt_number INTEGER,
    ADD COLUMN escalation_maximum_attempts INTEGER,
    ADD COLUMN escalation_source_sequence BIGINT
        GENERATED ALWAYS AS (
            CASE WHEN event_type = 'ESCALATION_REQUESTED' THEN 1::BIGINT END
        ) STORED;

ALTER TABLE resolution_run_events
    ADD CONSTRAINT fk_resolution_run_events_escalation_authority
        FOREIGN KEY (
            tenant_id, escalation_authority_evidence_id,
            escalation_actor_id, escalation_authority
        )
        REFERENCES approval_authority_evidence (
            tenant_id, evidence_id, actor_id, authority
        )
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_resolution_run_events_escalation_attempt
        FOREIGN KEY (tenant_id, run_id, escalation_source_attempt_number)
        REFERENCES resolution_runs (tenant_id, run_id, attempt_number)
        ON DELETE RESTRICT,
    ADD CONSTRAINT fk_resolution_run_events_escalation_failure
        FOREIGN KEY (tenant_id, run_id, escalation_source_sequence, from_state)
        REFERENCES resolution_run_events (tenant_id, run_id, sequence, to_state)
        ON DELETE RESTRICT,
    ADD CONSTRAINT ck_resolution_run_events_escalation
        CHECK (
            (
                event_type = 'ESCALATION_REQUESTED'
                AND escalation_actor_id IS NOT NULL
                AND escalation_authority_evidence_id IS NOT NULL
                AND escalation_reason IS NOT NULL
                AND escalation_policy_revision IS NOT NULL
                AND escalation_source_attempt_number IS NOT NULL
                AND escalation_maximum_attempts IS NOT NULL
                AND escalation_reason = 'RETRY_ATTEMPT_LIMIT_REACHED'
                AND char_length(escalation_policy_revision) BETWEEN 1 AND 200
                AND escalation_policy_revision ~ '^[a-z][a-z0-9.-]*(/[a-z0-9][a-z0-9.-]*)+$'
                AND escalation_source_attempt_number > 0
                AND escalation_maximum_attempts > 0
                AND escalation_source_attempt_number >= escalation_maximum_attempts
            )
            OR (
                event_type <> 'ESCALATION_REQUESTED'
                AND escalation_actor_id IS NULL
                AND escalation_authority_evidence_id IS NULL
                AND escalation_reason IS NULL
                AND escalation_policy_revision IS NULL
                AND escalation_source_attempt_number IS NULL
                AND escalation_maximum_attempts IS NULL
            )
        ),
    ADD CONSTRAINT ck_resolution_run_events_shape
        CHECK (
            (
                sequence = 1
                AND event_type = 'CAPABILITY_SUCCEEDED'
                AND receipt_outcome = 'SUCCEEDED'
                AND authorization_consumption_id IS NOT NULL
                AND replacement_run_id IS NULL
                AND from_state IN ('WAITING_FOR_APPROVAL', 'READY_FOR_AUTHORIZATION')
                AND to_state = 'VERIFYING'
            )
            OR (
                sequence = 1
                AND event_type = 'CAPABILITY_FAILED'
                AND receipt_outcome = 'FAILED'
                AND authorization_consumption_id IS NOT NULL
                AND replacement_run_id IS NULL
                AND from_state IN ('WAITING_FOR_APPROVAL', 'READY_FOR_AUTHORIZATION')
                AND to_state = 'ACTION_FAILED'
            )
            OR (
                sequence = 2
                AND event_type = 'OUTCOME_PROOF_ACCEPTED'
                AND receipt_outcome IS NULL
                AND authorization_consumption_id IS NULL
                AND replacement_run_id IS NULL
                AND from_state = 'VERIFYING'
                AND to_state = 'VERIFIED_RESOLVED'
            )
            OR (
                sequence = 2
                AND event_type = 'RETRY_STARTED'
                AND receipt_outcome IS NULL
                AND authorization_consumption_id IS NULL
                AND replacement_run_id IS NOT NULL
                AND from_state = 'ACTION_FAILED'
                AND to_state = 'SUPERSEDED'
            )
            OR (
                sequence = 2
                AND event_type = 'ESCALATION_REQUESTED'
                AND receipt_outcome IS NULL
                AND authorization_consumption_id IS NULL
                AND replacement_run_id IS NULL
                AND from_state = 'ACTION_FAILED'
                AND to_state = 'ESCALATED'
            )
        );


COMMENT ON COLUMN resolution_run_events.escalation_actor_id IS
    'Resolver who requested human follow-up; present only for ESCALATION_REQUESTED.';

COMMENT ON COLUMN resolution_run_events.escalation_authority_evidence_id IS
    'Current tenant-wide resolver attestation used for ESCALATION_REQUESTED.';

COMMENT ON COLUMN resolution_run_events.escalation_reason IS
    'Stable reason automated recovery ended; present only for ESCALATION_REQUESTED.';

COMMENT ON COLUMN resolution_run_events.escalation_policy_revision IS
    'Exact retry policy revision whose denial permitted escalation.';

COMMENT ON COLUMN resolution_run_events.escalation_source_attempt_number IS
    'Failed attempt denied another retry and handed to human follow-up.';

COMMENT ON COLUMN resolution_run_events.escalation_maximum_attempts IS
    'Total attempt ceiling evaluated by the recorded retry policy.';
