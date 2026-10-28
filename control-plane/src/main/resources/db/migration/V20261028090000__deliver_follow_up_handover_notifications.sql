-- Prospectively deliver committed follow-up handovers to their exact recipient.
-- The handover receipt remains authoritative; this immutable in-app delivery
-- contains routing coordinates only, never case text or renewed authority.
-- One transaction installs the source key, relation and source INSERT trigger.
-- ALTER TABLE builds a unique index while holding ACCESS EXCLUSIVE on handovers;
-- budget source row count, index/WAL headroom and a short maintenance window.
-- No historical backfill: replay of an old receipt must not create a new alert.
-- Old and new binaries use the same source INSERT; both get atomic delivery.

-- The recipient is part of the reference so a delivery cannot substitute actors.
ALTER TABLE human_follow_up_handovers
    ADD CONSTRAINT uq_follow_up_handover_notification_recipient
    UNIQUE (tenant_id, work_item_id, command_id, recipient_actor_id);

CREATE TABLE human_follow_up_handover_notifications (
    tenant_id UUID NOT NULL,
    work_item_id UUID NOT NULL,
    command_id UUID NOT NULL,
    recipient_actor_id UUID NOT NULL,
    delivered_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT pk_human_follow_up_handover_notifications
        PRIMARY KEY (tenant_id, work_item_id, command_id),
    CONSTRAINT fk_follow_up_handover_notification_source
        FOREIGN KEY (tenant_id, work_item_id, command_id, recipient_actor_id)
        REFERENCES human_follow_up_handovers
            (tenant_id, work_item_id, command_id, recipient_actor_id)
        ON DELETE RESTRICT
);

-- Recipient-local newest-first listing, with source coordinates breaking ties.
-- There is no globally enumerable identity or mutable unread counter.
CREATE INDEX ix_follow_up_handover_notifications_recipient
    ON human_follow_up_handover_notifications
        (tenant_id, recipient_actor_id, delivered_at DESC, work_item_id DESC, command_id DESC);

CREATE FUNCTION deliver_follow_up_handover_notification()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    INSERT INTO human_follow_up_handover_notifications (
        tenant_id, work_item_id, command_id, recipient_actor_id
    ) VALUES (
        NEW.tenant_id, NEW.work_item_id, NEW.command_id, NEW.recipient_actor_id
    );
    RETURN NEW;
END;
$$;

-- The source PK already fences duplicates. Do not hide a delivery inconsistency
-- with ON CONFLICT: a failed delivery must roll back the source and ownership.
CREATE TRIGGER trg_follow_up_handover_deliver_notification
AFTER INSERT ON human_follow_up_handovers
FOR EACH ROW EXECUTE FUNCTION deliver_follow_up_handover_notification();

CREATE FUNCTION prevent_follow_up_handover_notification_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'handover notification deliveries are immutable'
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_follow_up_handover_notifications_prevent_mutation
BEFORE UPDATE OR DELETE ON human_follow_up_handover_notifications
FOR EACH ROW EXECUTE FUNCTION prevent_follow_up_handover_notification_mutation();

CREATE TRIGGER trg_follow_up_handover_notifications_prevent_truncate
BEFORE TRUNCATE ON human_follow_up_handover_notifications
FOR EACH STATEMENT EXECUTE FUNCTION prevent_follow_up_handover_notification_mutation();

COMMENT ON TABLE human_follow_up_handover_notifications IS
    'Prospective immutable in-app handover deliveries. Source tuple deduplicates; recipient is pinned to the receipt. Not current ownership or target access.';
COMMENT ON COLUMN human_follow_up_handover_notifications.delivered_at IS
    'Database delivery insertion time, not handover occurrence, UI observation or read acknowledgement. No cross-clock ordering is implied.';
