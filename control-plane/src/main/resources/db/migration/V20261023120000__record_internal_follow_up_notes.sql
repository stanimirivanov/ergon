-- Append-only internal commentary, not case evidence or resolution proof.
-- Authorship is derived from the referenced immutable CLAIMED event, avoiding
-- separately mutable or contradictory copies of actor/claim attribution.
-- Additive: old binaries ignore notes. Keep this schema and notes on rollback.
-- The redundant unique key enforces the event kind through a foreign key.
-- Its build scans ownership history under ALTER TABLE's exclusive lock; assess
-- history size and deploy in a bounded maintenance window. No data backfill.
ALTER TABLE human_follow_up_ownership_events
    ADD CONSTRAINT uq_human_follow_up_ownership_events_note_reference
    UNIQUE (tenant_id, work_item_id, ownership_revision, event_type);

CREATE TABLE human_follow_up_notes (
    tenant_id UUID NOT NULL,
    work_item_id UUID NOT NULL,
    note_id UUID NOT NULL,
    ownership_revision BIGINT NOT NULL,
    ownership_event_type TEXT GENERATED ALWAYS AS ('CLAIMED') STORED,
    body TEXT NOT NULL,
    authored_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT pk_human_follow_up_notes PRIMARY KEY (tenant_id, work_item_id, note_id),
    CONSTRAINT fk_human_follow_up_notes_owner
        FOREIGN KEY (tenant_id, work_item_id, ownership_revision, ownership_event_type)
        REFERENCES human_follow_up_ownership_events (tenant_id, work_item_id, ownership_revision, event_type)
        ON DELETE RESTRICT,
    CONSTRAINT ck_human_follow_up_notes_body
        CHECK (CHAR_LENGTH(body) BETWEEN 1 AND 4000 AND body ~ '[^[:space:]]')
);

-- Serves the bounded newest-first owner read with deterministic equal-time ties.
CREATE INDEX ix_human_follow_up_notes_latest
    ON human_follow_up_notes (tenant_id, work_item_id, recorded_at DESC, note_id DESC);

CREATE TRIGGER trg_human_follow_up_notes_prevent_mutation
BEFORE UPDATE OR DELETE ON human_follow_up_notes
FOR EACH ROW EXECUTE FUNCTION prevent_human_follow_up_ownership_event_mutation();

COMMENT ON TABLE human_follow_up_notes IS
    'Immutable internal commentary. Not observations, authorization or proof. Current owner access is application-enforced; not public/requester data.';
COMMENT ON COLUMN human_follow_up_notes.note_id IS
    'Client retry identity scoped to tenant/work item. Exact text and ownership coordinates are immutable.';
COMMENT ON COLUMN human_follow_up_notes.ownership_revision IS
    'Exact CLAIMED event supplies author and claim attribution, not current access authority.';
