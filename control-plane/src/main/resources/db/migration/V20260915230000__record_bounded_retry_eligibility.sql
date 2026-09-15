-- ============================================================================
-- Versioned and bounded eligibility for explicit retries
-- ============================================================================
--
-- A retry decision must remain explainable after its governing policy changes.
-- New RETRY_STARTED events therefore record the exact policy revision, the
-- failed attempt it evaluated, and the total attempt ceiling. The source
-- attempt is bound back to the event's run and must be below that ceiling.
--
-- Existing retry events predate this decision snapshot and remain readable
-- with all three columns NULL. New application writes always supply all three.
-- This transactional expansion takes table locks and scans existing runs/events
-- to build uniqueness and validate constraints; no history is rewritten.
-- ============================================================================


-- The primary key already proves uniqueness; this shape is required to bind
-- the copied attempt number through a tenant-safe composite foreign key.
ALTER TABLE resolution_runs
    ADD CONSTRAINT uq_resolution_runs_retry_eligibility_subject
        UNIQUE (tenant_id, run_id, attempt_number);


ALTER TABLE resolution_run_events
    ADD COLUMN retry_policy_revision TEXT,
    ADD COLUMN retry_source_attempt_number INTEGER,
    ADD COLUMN retry_maximum_attempts INTEGER,
    ADD CONSTRAINT fk_resolution_run_events_retry_eligibility_subject
        FOREIGN KEY (tenant_id, run_id, retry_source_attempt_number)
        REFERENCES resolution_runs (tenant_id, run_id, attempt_number)
        ON DELETE RESTRICT,
    ADD CONSTRAINT ck_resolution_run_events_retry_eligibility
        CHECK (
            (
                event_type = 'RETRY_STARTED'
                AND (
                    (
                        retry_policy_revision IS NULL
                        AND retry_source_attempt_number IS NULL
                        AND retry_maximum_attempts IS NULL
                    )
                    OR (
                        retry_policy_revision IS NOT NULL
                        AND retry_source_attempt_number IS NOT NULL
                        AND retry_maximum_attempts IS NOT NULL
                        AND retry_actor_id IS NOT NULL
                        AND retry_authority_evidence_id IS NOT NULL
                        AND char_length(retry_policy_revision) BETWEEN 1 AND 200
                        AND retry_policy_revision ~ '^[a-z][a-z0-9.-]*(/[a-z0-9][a-z0-9.-]*)+$'
                        AND retry_source_attempt_number > 0
                        AND retry_source_attempt_number < retry_maximum_attempts
                    )
                )
            )
            OR (
                event_type <> 'RETRY_STARTED'
                AND retry_policy_revision IS NULL
                AND retry_source_attempt_number IS NULL
                AND retry_maximum_attempts IS NULL
            )
        );


COMMENT ON COLUMN resolution_run_events.retry_policy_revision IS
    'Exact retry policy revision evaluated for RETRY_STARTED; NULL only for legacy retry events or other event types.';

COMMENT ON COLUMN resolution_run_events.retry_source_attempt_number IS
    'Failed attempt evaluated by the retry policy; bound to the event run and NULL only for legacy retries or other events.';

COMMENT ON COLUMN resolution_run_events.retry_maximum_attempts IS
    'Total attempts, including the initial run, permitted by the recorded retry policy; NULL only for legacy retries or other events.';
