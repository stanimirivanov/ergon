-- ============================================================================
-- Case event store and synchronous timeline projection
-- ============================================================================
--
-- case_events is the authoritative, append-only history. cases and
-- case_timeline_entries are derived read models updated in the same transaction
-- as each event append. A command must:
--
--   1. lock the tenant/case stream;
--   2. verify its expected stream version;
--   3. append immutable events;
--   4. advance both projections;
--   5. commit the event and projections atomically.
--
-- occurred_at is source time; recorded_at is the database persistence time.
-- The database rejects UPDATE and DELETE against case_events so corrections
-- must be represented by later events.
-- ============================================================================


-- Immutable event history for the Case aggregate.
CREATE TABLE case_events (
    event_id UUID NOT NULL,
    tenant_id UUID NOT NULL,
    case_id UUID NOT NULL,
    stream_version BIGINT NOT NULL,
    event_type TEXT NOT NULL,
    schema_version INTEGER NOT NULL,
    payload JSONB NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT pk_case_events
        PRIMARY KEY (event_id),

    CONSTRAINT uq_case_events_stream_version
        UNIQUE (tenant_id, case_id, stream_version),

    -- The logically redundant event_id is required so the timeline's composite
    -- foreign key proves that an entry references the same tenant and stream.
    CONSTRAINT uq_case_events_stream_identity
        UNIQUE (tenant_id, case_id, stream_version, event_id),

    CONSTRAINT ck_case_events_stream_version_positive
        CHECK (stream_version > 0),

    CONSTRAINT ck_case_events_schema_version_positive
        CHECK (schema_version > 0),

    -- This closed set mirrors CaseEvent and requires a paired migration when a
    -- new persisted event type is introduced.
    CONSTRAINT ck_case_events_event_type
        CHECK (event_type IN ('CaseOpened', 'ObservationRecorded')),

    CONSTRAINT ck_case_events_payload_object
        CHECK (jsonb_typeof(payload) = 'object')
);


-- Mutable current-state projection. Event replay remains the source of truth.
CREATE TABLE cases (
    tenant_id UUID NOT NULL,
    case_id UUID NOT NULL,
    goal TEXT NOT NULL,
    status TEXT NOT NULL,
    stream_version BIGINT NOT NULL,
    opened_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_cases
        PRIMARY KEY (tenant_id, case_id),

    CONSTRAINT ck_cases_goal_length
        CHECK (char_length(goal) BETWEEN 1 AND 500),

    -- This closed set mirrors CaseStatus and evolves with a paired migration.
    CONSTRAINT ck_cases_status
        CHECK (status IN ('OPEN')),

    CONSTRAINT ck_cases_stream_version_positive
        CHECK (stream_version > 0)
);


-- Ordered read projection of attributable observations for one case.
CREATE TABLE case_timeline_entries (
    tenant_id UUID NOT NULL,
    case_id UUID NOT NULL,
    stream_version BIGINT NOT NULL,
    event_id UUID NOT NULL,
    entry_type TEXT NOT NULL,
    summary TEXT NOT NULL,
    observation_id UUID NOT NULL,
    observation_origin_type TEXT NOT NULL,
    observation_provider TEXT NOT NULL,
    observation_reference TEXT,
    observation_content TEXT NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_case_timeline_entries
        PRIMARY KEY (tenant_id, case_id, stream_version),

    CONSTRAINT uq_case_timeline_entries_event
        UNIQUE (event_id),

    CONSTRAINT fk_case_timeline_entries_case
        FOREIGN KEY (tenant_id, case_id)
        REFERENCES cases (tenant_id, case_id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_case_timeline_entries_event
        FOREIGN KEY (tenant_id, case_id, stream_version, event_id)
        REFERENCES case_events (tenant_id, case_id, stream_version, event_id)
        ON DELETE RESTRICT,

    CONSTRAINT ck_case_timeline_entries_stream_version_positive
        CHECK (stream_version > 0),

    CONSTRAINT ck_case_timeline_entries_type
        CHECK (entry_type IN ('CASE_OPENED', 'OBSERVATION_RECORDED')),

    CONSTRAINT ck_case_timeline_entries_summary_length
        CHECK (char_length(summary) BETWEEN 1 AND 200),

    CONSTRAINT ck_case_timeline_entries_origin_type
        CHECK (observation_origin_type IN ('REQUESTER', 'CONNECTOR')),

    CONSTRAINT ck_case_timeline_entries_provider
        CHECK (
            char_length(observation_provider) BETWEEN 1 AND 100
            AND observation_provider ~ '^[a-z0-9][a-z0-9._-]*$'
        ),

    -- Requester observations originate at the API and have no external source
    -- reference. Connector observations must remain traceable to one.
    CONSTRAINT ck_case_timeline_entries_origin_reference
        CHECK (
            (
                observation_origin_type = 'REQUESTER'
                AND observation_provider = 'api'
                AND observation_reference IS NULL
            )
            OR (
                observation_origin_type = 'CONNECTOR'
                AND observation_reference IS NOT NULL
                AND char_length(observation_reference) BETWEEN 1 AND 500
            )
        ),

    CONSTRAINT ck_case_timeline_entries_content_length
        CHECK (char_length(observation_content) BETWEEN 1 AND 8000)
);


-- Enforce the append-only promise independently of application code.
CREATE FUNCTION prevent_case_event_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'case_events is immutable; append a new event instead of using %',
        TG_OP
        USING ERRCODE = '55000';
END;
$$;


CREATE TRIGGER trg_case_events_prevent_mutation
BEFORE UPDATE OR DELETE
ON case_events
FOR EACH ROW
EXECUTE FUNCTION prevent_case_event_mutation();


COMMENT ON TABLE case_events IS
    'Authoritative append-only history for Case aggregates, partitioned logically by tenant and case.';

COMMENT ON TABLE cases IS
    'Mutable current-state projection of each case; reconstructable from case_events.';

COMMENT ON TABLE case_timeline_entries IS
    'Mutable synchronous read projection of a case source-observation timeline.';

COMMENT ON COLUMN case_events.payload IS
    'Versioned serialized domain event; event_type and schema_version select its decoder.';

COMMENT ON COLUMN case_events.occurred_at IS
    'Instant at which the event occurred at its source.';

COMMENT ON COLUMN case_events.recorded_at IS
    'Database instant at which the event was durably recorded.';

COMMENT ON COLUMN case_timeline_entries.observation_reference IS
    'External source reference for connector observations; NULL only for requester API observations.';
