-- ============================================================================
-- Durable human follow-up work for explicit run escalation
-- ============================================================================
--
-- Each ESCALATION_REQUESTED event owns exactly one immutable OPEN work item.
-- The item makes terminal automated failure actionable while retaining the
-- exact case/run/event source. Assignment, priority, notification, and later
-- lifecycle transitions are deliberately absent from this creation snapshot.
--
-- Existing escalation events are backfilled so an upgrade cannot leave an
-- escalated run without durable work. This migration validates existing run
-- events while adding its source-key constraint and does not rewrite history.
-- ============================================================================


ALTER TABLE resolution_run_events
    ADD CONSTRAINT uq_resolution_run_events_follow_up_source
        UNIQUE (
            tenant_id, run_id, sequence, event_id, escalation_reason
        );


CREATE TABLE human_follow_up_work_items (
    tenant_id UUID NOT NULL,
    work_item_id UUID NOT NULL,
    run_id UUID NOT NULL,
    escalation_sequence BIGINT
        GENERATED ALWAYS AS (2::BIGINT) STORED,
    escalation_event_id UUID NOT NULL,
    reason TEXT NOT NULL,
    status TEXT NOT NULL,
    opened_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT pk_human_follow_up_work_items
        PRIMARY KEY (tenant_id, work_item_id),

    CONSTRAINT uq_human_follow_up_work_items_run
        UNIQUE (tenant_id, run_id),

    CONSTRAINT uq_human_follow_up_work_items_escalation
        UNIQUE (tenant_id, escalation_event_id),

    CONSTRAINT fk_human_follow_up_work_items_run
        FOREIGN KEY (tenant_id, run_id)
        REFERENCES resolution_runs (tenant_id, run_id)
        ON DELETE RESTRICT,

    CONSTRAINT fk_human_follow_up_work_items_escalation
        FOREIGN KEY (
            tenant_id, run_id, escalation_sequence,
            escalation_event_id, reason
        )
        REFERENCES resolution_run_events (
            tenant_id, run_id, sequence, event_id, escalation_reason
        )
        ON DELETE RESTRICT,

    CONSTRAINT ck_human_follow_up_work_items_reason
        CHECK (reason = 'RETRY_ATTEMPT_LIMIT_REACHED'),

    CONSTRAINT ck_human_follow_up_work_items_status
        CHECK (status = 'OPEN')
);


INSERT INTO human_follow_up_work_items (
    tenant_id, work_item_id, run_id, escalation_event_id,
    reason, status, opened_at
)
SELECT
    tenant_id, gen_random_uuid(), run_id, event_id,
    escalation_reason, 'OPEN', occurred_at
FROM resolution_run_events
WHERE event_type = 'ESCALATION_REQUESTED';


CREATE FUNCTION prevent_human_follow_up_work_item_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'human follow-up creation snapshots are immutable; append a transition instead of using %',
        TG_OP
        USING ERRCODE = '55000';
END;
$$;


CREATE TRIGGER trg_human_follow_up_work_items_prevent_mutation
BEFORE UPDATE OR DELETE
ON human_follow_up_work_items
FOR EACH ROW
EXECUTE FUNCTION prevent_human_follow_up_work_item_mutation();


COMMENT ON TABLE human_follow_up_work_items IS
    'Immutable creation snapshots for resolver work opened by explicit exhausted-run escalation.';

COMMENT ON COLUMN human_follow_up_work_items.work_item_id IS
    'Stable identity used by later assignment, lifecycle, and notification capabilities.';

COMMENT ON COLUMN human_follow_up_work_items.escalation_event_id IS
    'Exact immutable ESCALATION_REQUESTED event that opened this work.';

COMMENT ON COLUMN human_follow_up_work_items.status IS
    'Initial OPEN state; later lifecycle changes must append attributable transitions.';

COMMENT ON COLUMN human_follow_up_work_items.opened_at IS
    'Application instant copied from the source escalation occurrence time.';

COMMENT ON COLUMN human_follow_up_work_items.recorded_at IS
    'Database instant at which the immutable work-item creation became durable.';
