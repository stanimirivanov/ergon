-- Account-access facts are typed semantic bindings derived from connector
-- observations. case_events remains authoritative; this table is a synchronous
-- read projection written in the event append transaction. A fact inherits its
-- account reference from its source observation and is immutable by convention.

ALTER TABLE case_events
    DROP CONSTRAINT ck_case_events_event_type;

ALTER TABLE case_events
    ADD CONSTRAINT ck_case_events_event_type
        CHECK (event_type IN (
            'CaseOpened',
            'ObservationRecorded',
            'AccountAccessStateBound'
        ));

-- The additional uniqueness lets a tenant-safe foreign key prove that a fact
-- references an observation projected for the same case and inherits that
-- observation's account reference.
ALTER TABLE case_timeline_entries
    ADD CONSTRAINT uq_case_timeline_entries_observation_reference
        UNIQUE (tenant_id, case_id, observation_id, observation_reference);

CREATE TABLE case_account_access_facts (
    tenant_id UUID NOT NULL,
    case_id UUID NOT NULL,
    fact_id UUID NOT NULL,
    stream_version BIGINT NOT NULL,
    event_id UUID NOT NULL,
    observation_id UUID NOT NULL,
    account_reference TEXT NOT NULL,
    state TEXT NOT NULL,
    bound_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_case_account_access_facts
        PRIMARY KEY (tenant_id, case_id, fact_id),

    CONSTRAINT uq_case_account_access_facts_event
        UNIQUE (event_id),

    -- One observation may establish this property once. A state change must be
    -- represented by a later observation and fact rather than rewriting history.
    CONSTRAINT uq_case_account_access_facts_observation
        UNIQUE (tenant_id, case_id, observation_id),

    CONSTRAINT fk_case_account_access_facts_case
        FOREIGN KEY (tenant_id, case_id)
        REFERENCES cases (tenant_id, case_id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_case_account_access_facts_event
        FOREIGN KEY (tenant_id, case_id, stream_version, event_id)
        REFERENCES case_events (tenant_id, case_id, stream_version, event_id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_case_account_access_facts_observation
        FOREIGN KEY (tenant_id, case_id, observation_id, account_reference)
        REFERENCES case_timeline_entries (
            tenant_id,
            case_id,
            observation_id,
            observation_reference
        )
        ON DELETE RESTRICT,

    CONSTRAINT ck_case_account_access_facts_stream_version_positive
        CHECK (stream_version > 0),

    CONSTRAINT ck_case_account_access_facts_reference_length
        CHECK (char_length(account_reference) BETWEEN 1 AND 500),

    -- Mirrors AccountAccessState; extend both in the same change.
    CONSTRAINT ck_case_account_access_facts_state
        CHECK (state IN ('ACTIVE', 'LOCKED'))
);

COMMENT ON TABLE case_account_access_facts IS
    'Synchronous projection of typed account-access facts bound to connector observations.';

COMMENT ON COLUMN case_account_access_facts.observation_id IS
    'Source observation from which the semantic account state was derived.';

COMMENT ON COLUMN case_account_access_facts.bound_at IS
    'Application instant at which the semantic binding was accepted.';

COMMENT ON COLUMN case_account_access_facts.recorded_at IS
    'Database instant at which the binding event was durably recorded.';
