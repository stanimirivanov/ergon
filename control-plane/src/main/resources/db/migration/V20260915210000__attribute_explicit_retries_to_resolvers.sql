-- ============================================================================
-- Authenticated resolver attribution for explicit retries
-- ============================================================================
--
-- A retry changes durable execution state even though it does not itself invoke
-- a capability. New retry events therefore retain the authenticated actor and
-- the current tenant-wide RESOLVER attestation used by the application.
--
-- Columns remain nullable so retry events written before this migration stay
-- readable. New application writes always supply both values. A generated
-- authority discriminator and composite foreign key prove that attributed
-- evidence belongs to the same actor and carries RESOLVER authority.
-- ============================================================================


ALTER TABLE resolution_run_events
    ADD COLUMN retry_actor_id UUID,
    ADD COLUMN retry_authority_evidence_id UUID,
    ADD COLUMN retry_authority TEXT
        GENERATED ALWAYS AS (
            CASE WHEN retry_authority_evidence_id IS NOT NULL THEN 'RESOLVER' END
        ) STORED,
    ADD CONSTRAINT fk_resolution_run_events_retry_authority
        FOREIGN KEY (
            tenant_id, retry_authority_evidence_id, retry_actor_id, retry_authority
        )
        REFERENCES approval_authority_evidence (
            tenant_id, evidence_id, actor_id, authority
        )
        ON DELETE RESTRICT,
    ADD CONSTRAINT ck_resolution_run_events_retry_attribution
        CHECK (
            (
                event_type = 'RETRY_STARTED'
                AND (
                    (retry_actor_id IS NULL AND retry_authority_evidence_id IS NULL)
                    OR (retry_actor_id IS NOT NULL AND retry_authority_evidence_id IS NOT NULL)
                )
            )
            OR (
                event_type <> 'RETRY_STARTED'
                AND retry_actor_id IS NULL
                AND retry_authority_evidence_id IS NULL
            )
        );


COMMENT ON COLUMN resolution_run_events.retry_actor_id IS
    'Authenticated human actor who requested the retry; NULL only for legacy retry events or other event types.';

COMMENT ON COLUMN resolution_run_events.retry_authority_evidence_id IS
    'Current tenant-wide RESOLVER attestation used to authorize the retry; NULL only for legacy retries or other events.';

COMMENT ON COLUMN resolution_run_events.retry_authority IS
    'Generated RESOLVER discriminator used to bind retry attribution to matching authority evidence.';
