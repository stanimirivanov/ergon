-- Recipient-scoped recent notification reads join immutable parents rather than copying
-- actor IDs into question/answer deliveries. Existing case/work question-history
-- indexes, response-question uniqueness and notification PKs cover the next hops.
-- These transactional builds scan retained bindings/ownership and block writes:
-- assess row count, lock window, I/O, index/WAL growth and disk before rollout.
-- Additive; no backfill or writer change. Retain indexes on application rollback.
CREATE INDEX ix_case_requesters_notification_recipient
    ON case_requesters (tenant_id, requester_actor_id, case_id);

CREATE INDEX ix_follow_up_ownership_notification_recipient
    ON human_follow_up_ownership_events (tenant_id, resolver_actor_id, work_item_id, ownership_revision)
    WHERE event_type = 'CLAIMED';
