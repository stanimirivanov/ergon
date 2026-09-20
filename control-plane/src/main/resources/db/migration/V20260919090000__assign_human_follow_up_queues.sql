-- ============================================================================
-- Immutable named queue for human follow-up work
-- ============================================================================
--
-- A work item's queue is selected when the item opens and remains part of its
-- immutable creation snapshot. Existing work belongs to access-restoration,
-- the only route that can currently open follow-up work. The retained default
-- lets the previous application version continue inserting during a
-- schema-first rolling deployment; new writers always provide the value.
--
-- PostgreSQL can install this constant default without a row-by-row UPDATE, so
-- the immutability trigger remains intact. Constraint validation scans the
-- existing table, and the transactional index build takes its normal locks.
-- Current development data is small; reassess validation and concurrent index
-- creation before a production-scale rollout.
-- ============================================================================


ALTER TABLE human_follow_up_work_items
    ADD COLUMN queue_key TEXT NOT NULL DEFAULT 'access-restoration';


ALTER TABLE human_follow_up_work_items
    ADD CONSTRAINT ck_human_follow_up_work_items_queue_key
        CHECK (queue_key ~ '^[a-z][a-z0-9-]{0,62}$') NOT VALID;


ALTER TABLE human_follow_up_work_items
    VALIDATE CONSTRAINT ck_human_follow_up_work_items_queue_key;


CREATE INDEX ix_human_follow_up_work_items_open_queue_inbox
    ON human_follow_up_work_items (
        tenant_id, queue_key, opened_at, work_item_id
    )
    WHERE status = 'OPEN';


COMMENT ON COLUMN human_follow_up_work_items.queue_key IS
    'Immutable named queue selected when the work item opens; not resolver assignment.';

COMMENT ON INDEX ix_human_follow_up_work_items_open_queue_inbox IS
    'Supports tenant-and-queue-scoped oldest-first keyset pagination of OPEN resolver work.';
