-- Retain one durable receipt per client claim intent. Existing first claims
-- remain valid without a command receipt; new commands insert the claim and
-- receipt in one application transaction. The event reference also permits a
-- later claim cycle without changing this receipt's identity or meaning.
-- This additive migration creates an empty table and takes no backfill lock.

CREATE TABLE human_follow_up_claim_commands (
    tenant_id UUID NOT NULL,
    work_item_id UUID NOT NULL,
    command_id UUID NOT NULL,
    expected_ownership_revision BIGINT NOT NULL,
    resulting_ownership_revision BIGINT NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT pk_human_follow_up_claim_commands
        PRIMARY KEY (tenant_id, work_item_id, command_id),
    CONSTRAINT fk_human_follow_up_claim_commands_event
        FOREIGN KEY (tenant_id, work_item_id, resulting_ownership_revision)
        REFERENCES human_follow_up_ownership_events (
            tenant_id, work_item_id, ownership_revision
        )
        ON DELETE RESTRICT,
    CONSTRAINT ck_human_follow_up_claim_commands_revision
        CHECK (
            expected_ownership_revision >= 0
            AND resulting_ownership_revision = expected_ownership_revision + 1
        )
);

CREATE FUNCTION prevent_human_follow_up_claim_command_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'human follow-up claim command receipts are immutable; % is not permitted',
        TG_OP
        USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER trg_human_follow_up_claim_commands_prevent_mutation
BEFORE UPDATE OR DELETE
ON human_follow_up_claim_commands
FOR EACH ROW
EXECUTE FUNCTION prevent_human_follow_up_claim_command_mutation();

COMMENT ON TABLE human_follow_up_claim_commands IS
    'Immutable receipt binding one tenant-scoped claim intent to its ownership event.';
COMMENT ON COLUMN human_follow_up_claim_commands.expected_ownership_revision IS
    'Current ownership revision asserted by the client when the command first applied.';
COMMENT ON COLUMN human_follow_up_claim_commands.resulting_ownership_revision IS
    'Claim event produced by the command; replay never creates another event.';
COMMENT ON COLUMN human_follow_up_claim_commands.recorded_at IS
    'Database instant at which the command receipt became durable.';
