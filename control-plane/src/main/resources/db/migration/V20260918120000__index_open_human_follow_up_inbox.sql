-- ============================================================================
-- Oldest-first resolver inbox access path
-- ============================================================================
--
-- Human follow-up creation snapshots remain authoritative and immutable. This
-- partial index supports tenant-scoped keyset reads of currently OPEN work;
-- it changes neither work-item meaning nor lifecycle state. The table is
-- expected to be small in the current development slice, so the transactional
-- index build and its table lock are acceptable. Reassess concurrent creation
-- before production-scale rollout.
-- ============================================================================


CREATE INDEX ix_human_follow_up_work_items_open_inbox
    ON human_follow_up_work_items (tenant_id, opened_at, work_item_id)
    WHERE status = 'OPEN';


COMMENT ON INDEX ix_human_follow_up_work_items_open_inbox IS
    'Supports tenant-scoped oldest-first keyset pagination of OPEN resolver work.';
