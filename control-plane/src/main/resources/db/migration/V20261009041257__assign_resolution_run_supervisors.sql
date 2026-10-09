-- ============================================================================
-- Immutable initial supervisor assignment for one resolution attempt
-- ============================================================================
--
-- An authenticated machine command names the human supervisor for one run.
-- The application checks that the named actor has current tenant-wide RESOLVER
-- authority at assigned_at. The foreign keys retain the exact attestation and
-- tenant-safe run/actor links, but cannot prove time-dependent validity alone.
--
-- One run has one initial assignment. A later handover must append attributable
-- transitions instead of rewriting this record. No existing runs are assigned
-- by this additive migration, and neither run state nor execution authority
-- changes. The only table locks are those required to create a new table and
-- foreign keys; existing run rows are not scanned or backfilled.
-- ============================================================================


CREATE TABLE resolution_run_supervisor_assignments (
    tenant_id UUID NOT NULL,
    assignment_id UUID NOT NULL,
    run_id UUID NOT NULL,
    supervisor_actor_id UUID NOT NULL,
    authority_evidence_id UUID NOT NULL,
    authority TEXT GENERATED ALWAYS AS ('RESOLVER') STORED,
    assigning_machine_subject TEXT NOT NULL,
    command_id UUID NOT NULL,
    assigned_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT pk_resolution_run_supervisor_assignments
        PRIMARY KEY (tenant_id, assignment_id),

    CONSTRAINT uq_resolution_run_supervisor_assignments_run
        UNIQUE (tenant_id, run_id),

    CONSTRAINT uq_resolution_run_supervisor_assignments_command
        UNIQUE (tenant_id, command_id),

    CONSTRAINT fk_resolution_run_supervisor_assignments_run
        FOREIGN KEY (tenant_id, run_id)
        REFERENCES resolution_runs (tenant_id, run_id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_resolution_run_supervisor_assignments_actor
        FOREIGN KEY (tenant_id, supervisor_actor_id)
        REFERENCES human_actors (tenant_id, actor_id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_resolution_run_supervisor_assignments_authority
        FOREIGN KEY (
            tenant_id, authority_evidence_id, supervisor_actor_id, authority
        )
        REFERENCES approval_authority_evidence (
            tenant_id, evidence_id, actor_id, authority
        )
        ON DELETE RESTRICT,

    CONSTRAINT ck_resolution_run_supervisor_assignments_machine_subject
        CHECK (
            char_length(assigning_machine_subject) BETWEEN 1 AND 200
            AND btrim(assigning_machine_subject) <> ''
        )
);


-- Supports bounded keyset discovery for one supervisor. The run-state join is
-- by the resolution_run_states primary key; the authority EXISTS predicate is
-- evaluated at the caller's application-clock instant.
CREATE INDEX ix_resolution_run_supervisor_assignments_actor_assigned
    ON resolution_run_supervisor_assignments (
        tenant_id, supervisor_actor_id, assigned_at, assignment_id
    );


CREATE FUNCTION prevent_resolution_run_supervisor_assignment_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'resolution-run supervisor assignments are immutable; append a handover instead of using %',
        TG_OP
        USING ERRCODE = '55000';
END;
$$;


CREATE TRIGGER trg_resolution_run_supervisor_assignments_prevent_mutation
BEFORE UPDATE OR DELETE
ON resolution_run_supervisor_assignments
FOR EACH ROW
EXECUTE FUNCTION prevent_resolution_run_supervisor_assignment_mutation();


COMMENT ON TABLE resolution_run_supervisor_assignments IS
    'Immutable initial human supervisor assignment for one tenant-scoped run; not a lease or execution grant.';

COMMENT ON COLUMN resolution_run_supervisor_assignments.authority_evidence_id IS
    'Exact resolver attestation checked as current by the assignment application service.';

COMMENT ON COLUMN resolution_run_supervisor_assignments.assigning_machine_subject IS
    'Opaque authenticated machine principal that requested the assignment; never a token.';

COMMENT ON COLUMN resolution_run_supervisor_assignments.command_id IS
    'Tenant-scoped command identity for exact replay after an ambiguous response.';

COMMENT ON COLUMN resolution_run_supervisor_assignments.assigned_at IS
    'Application-clock instant when the assignment command accepted current resolver authority.';

COMMENT ON COLUMN resolution_run_supervisor_assignments.recorded_at IS
    'Database instant when the initial supervisor assignment became durable.';

COMMENT ON INDEX ix_resolution_run_supervisor_assignments_actor_assigned IS
    'Supports ordered assigned-run discovery scoped to one tenant and supervisor.';
