-- Bounded newest-first history uses one access path per audience. The work path
-- also supports the ownership-event foreign key's tenant/work prefix; the case
-- path serves immutable requester ownership without joining private work data.
-- Additive and compatible with existing writers. Transactional index builds scan
-- retained questions and block writes until commit: budget table-sized I/O,
-- index/WAL growth and a deployment window; preserve indexes on application rollback.
CREATE INDEX ix_requester_information_requests_work_history
    ON requester_information_requests (tenant_id, work_item_id, recorded_at DESC, request_id DESC);

CREATE INDEX ix_requester_information_requests_case_history
    ON requester_information_requests (tenant_id, case_id, recorded_at DESC, request_id DESC);
