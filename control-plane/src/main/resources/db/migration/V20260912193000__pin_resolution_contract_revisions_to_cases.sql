-- ============================================================================
-- Immutable resolution contract pin for each case
-- ============================================================================
--
-- A pin is a planning decision in the authoritative case event stream, not a
-- source observation. case_resolution_contract_pins is its synchronous,
-- one-row read projection. Composite foreign keys prove that the event, case,
-- and published contract revision all belong to the same tenant.
--
-- A case may be pinned once. Future replanning must append a distinct event and
-- use a separately designed projection rather than rewriting this decision.
-- ============================================================================


ALTER TABLE case_events
    DROP CONSTRAINT ck_case_events_event_type;

ALTER TABLE case_events
    ADD CONSTRAINT ck_case_events_event_type
        CHECK (event_type IN (
            'CaseOpened',
            'ObservationRecorded',
            'AccountAccessStateBound',
            'ResolutionContractRevisionPinned'
        ));


CREATE TABLE case_resolution_contract_pins (
    tenant_id UUID NOT NULL,
    case_id UUID NOT NULL,
    contract_key TEXT NOT NULL,
    contract_revision INTEGER NOT NULL,
    stream_version BIGINT NOT NULL,
    event_id UUID NOT NULL,
    pinned_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_case_resolution_contract_pins
        PRIMARY KEY (tenant_id, case_id),

    CONSTRAINT uq_case_resolution_contract_pins_event
        UNIQUE (event_id),

    CONSTRAINT fk_case_resolution_contract_pins_case
        FOREIGN KEY (tenant_id, case_id)
        REFERENCES cases (tenant_id, case_id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_case_resolution_contract_pins_event
        FOREIGN KEY (tenant_id, case_id, stream_version, event_id)
        REFERENCES case_events (tenant_id, case_id, stream_version, event_id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_case_resolution_contract_pins_revision
        FOREIGN KEY (tenant_id, contract_key, contract_revision)
        REFERENCES resolution_contract_revisions (tenant_id, contract_key, revision)
        ON DELETE RESTRICT,

    CONSTRAINT ck_case_resolution_contract_pins_revision_positive
        CHECK (contract_revision > 0),

    CONSTRAINT ck_case_resolution_contract_pins_stream_version_positive
        CHECK (stream_version > 0)
);


CREATE FUNCTION prevent_case_resolution_contract_pin_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'case resolution contract pins are immutable; append a replanning event instead of using %',
        TG_OP
        USING ERRCODE = '55000';
END;
$$;


CREATE TRIGGER trg_case_resolution_contract_pins_prevent_mutation
BEFORE UPDATE OR DELETE
ON case_resolution_contract_pins
FOR EACH ROW
EXECUTE FUNCTION prevent_case_resolution_contract_pin_mutation();


COMMENT ON TABLE case_resolution_contract_pins IS
    'Immutable synchronous projection of the exact contract revision pinned to each case.';

COMMENT ON COLUMN case_resolution_contract_pins.pinned_at IS
    'Application instant at which the contract revision was selected for the case.';

COMMENT ON COLUMN case_resolution_contract_pins.recorded_at IS
    'Database instant at which the pin event was durably recorded.';
