-- ============================================================================
-- Revisioned run supervision, preserving immutable initial assignments
-- ============================================================================
-- Events are authoritative history; current supervision is a derived access
-- projection. Initial assignments remain unchanged, including machine replay.
-- A first-assignment trigger supports old writers while all readers are upgraded.
-- CREATE TRIGGER locks that table against writes before the one-time backfill;
-- assess assignment count, scan/lock time, WAL and disk headroom before rollout.
-- Handover must stay disabled until no old reader serves supervision traffic.
-- After any handover, old binaries are unsafe: disable commands and recover
-- forward. This transactional migration neither changes run state nor grants
-- capability authority. No production/demo identities are inserted.

-- This redundant unique tuple permits an exact tenant/run/initial-assignment FK.
ALTER TABLE resolution_run_supervisor_assignments
    ADD CONSTRAINT uq_run_supervisor_initial_run_identity
    UNIQUE (tenant_id, run_id, assignment_id);

CREATE TABLE resolution_run_supervision_events (
    tenant_id UUID NOT NULL,
    run_id UUID NOT NULL,
    supervision_revision BIGINT NOT NULL,
    assignment_id UUID NOT NULL,
    event_type TEXT NOT NULL,
    supervisor_actor_id UUID NOT NULL,
    authority_evidence_id UUID NOT NULL,
    authority TEXT GENERATED ALWAYS AS ('RESOLVER') STORED,
    previous_actor_id UUID,
    previous_authority_evidence_id UUID,
    command_id UUID,
    occurred_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT pk_resolution_run_supervision_events
        PRIMARY KEY (tenant_id, run_id, supervision_revision),
    CONSTRAINT uq_run_supervision_event_projection
        UNIQUE (tenant_id, run_id, supervision_revision, supervisor_actor_id, occurred_at),
    CONSTRAINT uq_run_supervision_event_command
        UNIQUE (tenant_id, command_id),
    CONSTRAINT fk_run_supervision_event_initial
        FOREIGN KEY (tenant_id, run_id, assignment_id)
        REFERENCES resolution_run_supervisor_assignments (tenant_id, run_id, assignment_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_run_supervision_event_incoming_authority
        FOREIGN KEY (tenant_id, authority_evidence_id, supervisor_actor_id, authority)
        REFERENCES approval_authority_evidence (tenant_id, evidence_id, actor_id, authority)
        ON DELETE RESTRICT,
    CONSTRAINT fk_run_supervision_event_outgoing_authority
        FOREIGN KEY (tenant_id, previous_authority_evidence_id, previous_actor_id, authority)
        REFERENCES approval_authority_evidence (tenant_id, evidence_id, actor_id, authority)
        ON DELETE RESTRICT,
    CONSTRAINT ck_run_supervision_event_revision
        CHECK (supervision_revision BETWEEN 0 AND 9007199254740990),
    CONSTRAINT ck_run_supervision_event_shape CHECK (
        (event_type = 'ASSIGNED' AND supervision_revision = 0
            AND previous_actor_id IS NULL AND previous_authority_evidence_id IS NULL
            AND command_id IS NULL)
        OR
        (event_type = 'HANDED_OVER' AND supervision_revision > 0
            AND previous_actor_id IS NOT NULL AND previous_authority_evidence_id IS NOT NULL
            AND previous_actor_id <> supervisor_actor_id AND command_id IS NOT NULL)
    )
);

CREATE TABLE resolution_run_current_supervision (
    tenant_id UUID NOT NULL,
    run_id UUID NOT NULL,
    supervision_revision BIGINT NOT NULL,
    supervisor_actor_id UUID NOT NULL,
    supervised_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT pk_resolution_run_current_supervision PRIMARY KEY (tenant_id, run_id),
    -- The projection cannot attribute an epoch to an actor/instant different
    -- from its immutable event. Both first assignment and handover use this FK.
    CONSTRAINT fk_run_current_supervision_event
        FOREIGN KEY (tenant_id, run_id, supervision_revision, supervisor_actor_id, supervised_at)
        REFERENCES resolution_run_supervision_events (
            tenant_id, run_id, supervision_revision, supervisor_actor_id, occurred_at
        ) ON DELETE RESTRICT
);

-- Current-supervisor filtering joins the initial assignment for stable keyset
-- order. The old actor index cannot locate a recipient after a transfer.
CREATE INDEX ix_run_current_supervision_actor
    ON resolution_run_current_supervision (tenant_id, supervisor_actor_id, run_id);

CREATE FUNCTION initialize_run_current_supervision()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO resolution_run_supervision_events (
        tenant_id, run_id, supervision_revision, assignment_id, event_type,
        supervisor_actor_id, authority_evidence_id, occurred_at, recorded_at
    ) VALUES (
        NEW.tenant_id, NEW.run_id, 0, NEW.assignment_id, 'ASSIGNED',
        NEW.supervisor_actor_id, NEW.authority_evidence_id, NEW.assigned_at, NEW.recorded_at
    );
    INSERT INTO resolution_run_current_supervision (
        tenant_id, run_id, supervision_revision, supervisor_actor_id, supervised_at
    ) VALUES (NEW.tenant_id, NEW.run_id, 0, NEW.supervisor_actor_id, NEW.assigned_at);
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_run_supervisor_assignment_initialize_current
AFTER INSERT ON resolution_run_supervisor_assignments
FOR EACH ROW EXECUTE FUNCTION initialize_run_current_supervision();

INSERT INTO resolution_run_supervision_events (
    tenant_id, run_id, supervision_revision, assignment_id, event_type,
    supervisor_actor_id, authority_evidence_id, occurred_at, recorded_at
)
SELECT tenant_id, run_id, 0, assignment_id, 'ASSIGNED',
    supervisor_actor_id, authority_evidence_id, assigned_at, recorded_at
FROM resolution_run_supervisor_assignments;

INSERT INTO resolution_run_current_supervision (
    tenant_id, run_id, supervision_revision, supervisor_actor_id, supervised_at
)
SELECT tenant_id, run_id, supervision_revision, supervisor_actor_id, occurred_at
FROM resolution_run_supervision_events;

CREATE FUNCTION prevent_run_supervision_event_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'run supervision events are immutable; append a handover instead of using %', TG_OP
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_run_supervision_events_prevent_mutation
BEFORE UPDATE OR DELETE ON resolution_run_supervision_events
FOR EACH ROW EXECUTE FUNCTION prevent_run_supervision_event_mutation();

COMMENT ON TABLE resolution_run_supervision_events IS
    'Immutable supervision epochs: initial machine assignment and attributable browser handovers; not run-state events or execution grants.';
COMMENT ON TABLE resolution_run_current_supervision IS
    'Derived current supervisor used by all upgraded browser access gates; never infer current access from the initial assignment.';
COMMENT ON COLUMN resolution_run_supervision_events.command_id IS
    'NULL only for the initial assignment mirror; tenant-scoped exact browser command identity for a handover.';
COMMENT ON COLUMN resolution_run_supervision_events.previous_actor_id IS
    'NULL only for initial assignment; outgoing authenticated supervisor for a handover.';
COMMENT ON COLUMN resolution_run_supervision_events.previous_authority_evidence_id IS
    'NULL only for initial assignment; outgoing resolver evidence checked at the transfer instant.';
COMMENT ON COLUMN resolution_run_supervision_events.occurred_at IS
    'Application-clock authority check instant; timestamps do not order supervision epochs.';
COMMENT ON COLUMN resolution_run_supervision_events.recorded_at IS
    'Database recording instant; initial mirrors preserve their existing assignment recording time.';
