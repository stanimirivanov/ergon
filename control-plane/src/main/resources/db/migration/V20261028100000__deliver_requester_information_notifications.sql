-- Prospective in-app question/answer delivery, committed with immutable sources.
-- No historical backfill, text copy or authority grant. Recipients are derived
-- from immutable parents: case requester for a question; a captured CLAIMED
-- ownership revision for an answer. Neither asserts current target access.
-- Empty additive tables; no source scan/index rebuild. Trigger/FK installation
-- briefly blocks source writes. Old and new writers share the same source INSERT.

CREATE TABLE requester_information_request_notifications (
    tenant_id UUID NOT NULL,
    request_id UUID NOT NULL,
    delivered_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_requester_information_request_notifications PRIMARY KEY (tenant_id, request_id),
    CONSTRAINT fk_information_request_notification_source
        FOREIGN KEY (tenant_id, request_id)
        REFERENCES requester_information_requests (tenant_id, request_id) ON DELETE RESTRICT
);

CREATE TABLE requester_information_response_notifications (
    tenant_id UUID NOT NULL,
    response_id UUID NOT NULL,
    recipient_ownership_revision BIGINT NOT NULL,
    delivered_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_requester_information_response_notifications PRIMARY KEY (tenant_id, response_id),
    CONSTRAINT fk_information_response_notification_source
        FOREIGN KEY (tenant_id, response_id)
        REFERENCES requester_information_responses (tenant_id, response_id) ON DELETE RESTRICT,
    CONSTRAINT ck_information_response_notification_revision CHECK (recipient_ownership_revision > 0)
);

-- Validate the immutable response -> question -> work -> CLAIMED epoch path
-- without copying work/actor coordinates or adding source-wide unique indexes.
-- This is historical referential integrity, not current ownership authorization.
CREATE FUNCTION check_information_response_notification_recipient()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM requester_information_responses r
        JOIN requester_information_requests q
            ON q.tenant_id = r.tenant_id AND q.request_id = r.request_id
        JOIN human_follow_up_ownership_events e
            ON e.tenant_id = q.tenant_id AND e.work_item_id = q.work_item_id
        WHERE r.tenant_id = NEW.tenant_id AND r.response_id = NEW.response_id
            AND e.ownership_revision = NEW.recipient_ownership_revision AND e.event_type = 'CLAIMED'
    ) THEN
        RAISE EXCEPTION 'response notification requires its source work claimed epoch' USING ERRCODE = '23503';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_information_response_notification_recipient
BEFORE INSERT ON requester_information_response_notifications
FOR EACH ROW EXECUTE FUNCTION check_information_response_notification_recipient();

CREATE FUNCTION deliver_information_request_notification()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO requester_information_request_notifications (tenant_id, request_id)
    VALUES (NEW.tenant_id, NEW.request_id);
    RETURN NEW;
END;
$$;

CREATE FUNCTION deliver_information_response_notification()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    -- Capture the owner visible at this SELECT, not a commit-order guarantee.
    -- Do not lock work here: answers already lock case, while questions lock
    -- work then case. Reversing that order would introduce a deadlock path.
    -- No owner/active work means no resolver delivery, not a rejected answer.
    INSERT INTO requester_information_response_notifications (
        tenant_id, response_id, recipient_ownership_revision
    )
    SELECT NEW.tenant_id, NEW.response_id, o.ownership_revision
    FROM requester_information_requests q
    JOIN human_follow_up_work_items w
        ON w.tenant_id = q.tenant_id AND w.work_item_id = q.work_item_id
    JOIN human_follow_up_current_ownership o
        ON o.tenant_id = w.tenant_id AND o.work_item_id = w.work_item_id
    JOIN cases c ON c.tenant_id = q.tenant_id AND c.case_id = q.case_id
    WHERE q.tenant_id = NEW.tenant_id AND q.request_id = NEW.request_id
        AND w.status = 'OPEN' AND c.status = 'OPEN' AND o.current_claim_id IS NOT NULL
        AND NOT EXISTS (
            SELECT 1 FROM human_follow_up_completions completion
            WHERE completion.tenant_id = w.tenant_id AND completion.work_item_id = w.work_item_id
        );
    RETURN NEW;
END;
$$;

-- Source uniqueness fences replay. A delivery failure aborts the source write;
-- never swallow conflicts or recreate missing historical alerts on lookup.
CREATE TRIGGER trg_information_request_deliver_notification
AFTER INSERT ON requester_information_requests
FOR EACH ROW EXECUTE FUNCTION deliver_information_request_notification();
CREATE TRIGGER trg_information_response_deliver_notification
AFTER INSERT ON requester_information_responses
FOR EACH ROW EXECUTE FUNCTION deliver_information_response_notification();

CREATE FUNCTION prevent_information_notification_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'information notification deliveries are immutable' USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_information_request_notifications_prevent_mutation
BEFORE UPDATE OR DELETE ON requester_information_request_notifications
FOR EACH ROW EXECUTE FUNCTION prevent_information_notification_mutation();
CREATE TRIGGER trg_information_request_notifications_prevent_truncate
BEFORE TRUNCATE ON requester_information_request_notifications
FOR EACH STATEMENT EXECUTE FUNCTION prevent_information_notification_mutation();
CREATE TRIGGER trg_information_response_notifications_prevent_mutation
BEFORE UPDATE OR DELETE ON requester_information_response_notifications
FOR EACH ROW EXECUTE FUNCTION prevent_information_notification_mutation();
CREATE TRIGGER trg_information_response_notifications_prevent_truncate
BEFORE TRUNCATE ON requester_information_response_notifications
FOR EACH STATEMENT EXECUTE FUNCTION prevent_information_notification_mutation();

COMMENT ON TABLE requester_information_request_notifications IS
    'Prospective immutable requester question deliveries. Source question and immutable case binding supply recipient; no text, read state or authority.';
COMMENT ON TABLE requester_information_response_notifications IS
    'Prospective immutable answer deliveries to the ownership epoch selected at insertion. No owner at selection means no delivery or later catch-up.';
COMMENT ON COLUMN requester_information_response_notifications.recipient_ownership_revision IS
    'Historical CLAIMED epoch on the source question work, not the question author or continuing ownership. Resolve actor only through that immutable event.';
COMMENT ON COLUMN requester_information_request_notifications.delivered_at IS
    'Database insertion time, not question occurrence, browser observation, read acknowledgement or a cross-clock ordering guarantee.';
COMMENT ON COLUMN requester_information_response_notifications.delivered_at IS
    'Database insertion time, not answer occurrence, browser observation, read acknowledgement or a commit-order routing guarantee.';
