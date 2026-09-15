-- ============================================================================
-- Durable outcome-proof acceptance and verified case closure
-- ============================================================================
--
-- A proof acceptance is the second and terminal event of a successful run. It
-- freezes the exact post-action fact used by the decision, advances the run to
-- VERIFIED_RESOLVED, and permits one paired CaseVerifiedResolved event. The
-- application writes both streams and their projections in one transaction.
--
-- Receipt columns become nullable because they describe only capability-result
-- events. Event-shape constraints keep the two event variants unambiguous.
-- ============================================================================


ALTER TABLE case_events
    DROP CONSTRAINT ck_case_events_event_type;

ALTER TABLE case_events
    ADD CONSTRAINT ck_case_events_event_type
        CHECK (event_type IN (
            'CaseOpened',
            'ObservationRecorded',
            'AccountAccessStateBound',
            'ResolutionContractRevisionPinned',
            'CaseVerifiedResolved'
        ));


ALTER TABLE cases
    DROP CONSTRAINT ck_cases_status;

ALTER TABLE cases
    ADD CONSTRAINT ck_cases_status
        CHECK (status IN ('OPEN', 'VERIFIED_RESOLVED'));


ALTER TABLE resolution_run_states
    DROP CONSTRAINT ck_resolution_run_states_state,
    DROP CONSTRAINT ck_resolution_run_states_version,
    DROP CONSTRAINT ck_resolution_run_states_transition_shape;

ALTER TABLE resolution_run_states
    ADD CONSTRAINT ck_resolution_run_states_state
        CHECK (state IN (
            'WAITING_FOR_APPROVAL',
            'READY_FOR_AUTHORIZATION',
            'VERIFYING',
            'ACTION_FAILED',
            'VERIFIED_RESOLVED'
        )),
    ADD CONSTRAINT ck_resolution_run_states_version
        CHECK (version BETWEEN 0 AND 2),
    ADD CONSTRAINT ck_resolution_run_states_transition_shape
        CHECK (
            (version = 0 AND state IN ('WAITING_FOR_APPROVAL', 'READY_FOR_AUTHORIZATION'))
            OR (version = 1 AND state IN ('VERIFYING', 'ACTION_FAILED'))
            OR (version = 2 AND state = 'VERIFIED_RESOLVED')
        );


ALTER TABLE resolution_run_events
    DROP CONSTRAINT ck_resolution_run_events_first_sequence,
    DROP CONSTRAINT ck_resolution_run_events_from_state,
    DROP CONSTRAINT ck_resolution_run_events_capability_result,
    ALTER COLUMN authorization_consumption_id DROP NOT NULL,
    ALTER COLUMN receipt_outcome DROP NOT NULL;

ALTER TABLE resolution_run_events
    ADD CONSTRAINT uq_resolution_run_events_proof_identity
        UNIQUE (tenant_id, run_id, sequence, event_id),
    ADD CONSTRAINT ck_resolution_run_events_sequence
        CHECK (sequence BETWEEN 1 AND 2),
    ADD CONSTRAINT ck_resolution_run_events_state
        CHECK (
            from_state IN (
                'WAITING_FOR_APPROVAL', 'READY_FOR_AUTHORIZATION', 'VERIFYING'
            )
            AND to_state IN ('VERIFYING', 'ACTION_FAILED', 'VERIFIED_RESOLVED')
        ),
    ADD CONSTRAINT ck_resolution_run_events_shape
        CHECK (
            (
                sequence = 1
                AND event_type = 'CAPABILITY_SUCCEEDED'
                AND receipt_outcome = 'SUCCEEDED'
                AND authorization_consumption_id IS NOT NULL
                AND from_state IN ('WAITING_FOR_APPROVAL', 'READY_FOR_AUTHORIZATION')
                AND to_state = 'VERIFYING'
            )
            OR (
                sequence = 1
                AND event_type = 'CAPABILITY_FAILED'
                AND receipt_outcome = 'FAILED'
                AND authorization_consumption_id IS NOT NULL
                AND from_state IN ('WAITING_FOR_APPROVAL', 'READY_FOR_AUTHORIZATION')
                AND to_state = 'ACTION_FAILED'
            )
            OR (
                sequence = 2
                AND event_type = 'OUTCOME_PROOF_ACCEPTED'
                AND receipt_outcome IS NULL
                AND authorization_consumption_id IS NULL
                AND from_state = 'VERIFYING'
                AND to_state = 'VERIFIED_RESOLVED'
            )
        );


ALTER TABLE resolution_runs
    ADD CONSTRAINT uq_resolution_runs_proof_boundary
        UNIQUE (tenant_id, run_id, case_id, case_stream_version);

ALTER TABLE case_timeline_entries
    ADD CONSTRAINT uq_case_timeline_entries_proof_observation
        UNIQUE (tenant_id, case_id, observation_id, stream_version);

ALTER TABLE case_account_access_facts
    ADD CONSTRAINT uq_case_account_access_facts_proof_source
        UNIQUE (
            tenant_id, case_id, fact_id, observation_id, stream_version, state
        );


CREATE TABLE resolution_outcome_proof_acceptances (
    tenant_id UUID NOT NULL,
    run_id UUID NOT NULL,
    run_event_sequence BIGINT NOT NULL,
    run_event_id UUID NOT NULL,
    case_id UUID NOT NULL,
    run_case_stream_version BIGINT NOT NULL,
    case_stream_version BIGINT NOT NULL,
    fact_type TEXT NOT NULL,
    expected_value TEXT NOT NULL,
    actual_value TEXT NOT NULL,
    fact_id UUID NOT NULL,
    observation_id UUID NOT NULL,
    observation_stream_version BIGINT NOT NULL,
    fact_stream_version BIGINT NOT NULL,
    accepted_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT pk_resolution_outcome_proof_acceptances
        PRIMARY KEY (tenant_id, run_id),

    CONSTRAINT uq_resolution_outcome_proof_acceptances_event
        UNIQUE (tenant_id, run_event_id),

    CONSTRAINT fk_resolution_outcome_proof_acceptances_event
        FOREIGN KEY (tenant_id, run_id, run_event_sequence, run_event_id)
        REFERENCES resolution_run_events (tenant_id, run_id, sequence, event_id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_resolution_outcome_proof_acceptances_run
        FOREIGN KEY (tenant_id, run_id, case_id, run_case_stream_version)
        REFERENCES resolution_runs (
            tenant_id, run_id, case_id, case_stream_version
        )
        ON DELETE RESTRICT,

    CONSTRAINT fk_resolution_outcome_proof_acceptances_case_version
        FOREIGN KEY (tenant_id, case_id, case_stream_version)
        REFERENCES case_events (tenant_id, case_id, stream_version)
        ON DELETE RESTRICT,

    CONSTRAINT fk_resolution_outcome_proof_acceptances_observation
        FOREIGN KEY (
            tenant_id, case_id, observation_id, observation_stream_version
        )
        REFERENCES case_timeline_entries (
            tenant_id, case_id, observation_id, stream_version
        )
        ON DELETE RESTRICT,

    CONSTRAINT fk_resolution_outcome_proof_acceptances_fact
        FOREIGN KEY (
            tenant_id, case_id, fact_id, observation_id,
            fact_stream_version, actual_value
        )
        REFERENCES case_account_access_facts (
            tenant_id, case_id, fact_id, observation_id, stream_version, state
        )
        ON DELETE RESTRICT,

    CONSTRAINT ck_resolution_outcome_proof_acceptances_event_sequence
        CHECK (run_event_sequence = 2),

    CONSTRAINT ck_resolution_outcome_proof_acceptances_stream_versions
        CHECK (
            run_case_stream_version > 0
            AND observation_stream_version > run_case_stream_version
            AND fact_stream_version > observation_stream_version
            AND case_stream_version >= fact_stream_version
        ),

    CONSTRAINT ck_resolution_outcome_proof_acceptances_fact
        CHECK (
            fact_type = 'account.access.state'
            AND expected_value = actual_value
        )
);


CREATE FUNCTION prevent_resolution_outcome_proof_acceptance_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'resolution outcome proof acceptances are immutable; attempted %',
        TG_OP
        USING ERRCODE = '55000';
END;
$$;


CREATE TRIGGER trg_resolution_outcome_proof_acceptances_prevent_mutation
BEFORE UPDATE OR DELETE
ON resolution_outcome_proof_acceptances
FOR EACH ROW
EXECUTE FUNCTION prevent_resolution_outcome_proof_acceptance_mutation();


COMMENT ON TABLE resolution_outcome_proof_acceptances IS
    'Immutable evidence projection for accepted outcome-proof run events.';

COMMENT ON COLUMN resolution_outcome_proof_acceptances.case_stream_version IS
    'Final case event included when proof was accepted; closure is the following case event.';

COMMENT ON COLUMN resolution_outcome_proof_acceptances.accepted_at IS
    'Application instant shared by the accepted-proof run event and verified case closure.';

COMMENT ON COLUMN resolution_run_events.authorization_consumption_id IS
    'Terminal connector receipt for capability-result events; NULL for later event types.';

COMMENT ON COLUMN resolution_run_events.receipt_outcome IS
    'Terminal connector outcome for capability-result events; NULL for later event types.';

COMMENT ON COLUMN resolution_run_events.occurred_at IS
    'Business instant at which this run transition occurred.';
