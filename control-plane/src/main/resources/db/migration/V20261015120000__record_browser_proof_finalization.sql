-- Browser proof finalization receipts bind immutable acceptance to the exact
-- reviewed evidence and supervisor epoch, with the deciding resolver attestation.
-- Existing internal acceptance remains unchanged; no backfill or new authority.
-- Write acceptance/closure first, then this receipt in the same transaction.
-- The supporting UNIQUE scans/locks the acceptance table and builds an index;
-- assess lock time, disk and WAL before deploying to a populated installation.
-- Deploy schema/code before enabling commands. Disable the flag for application
-- rollback; retain the additive table and all accepted proof/closure history.

ALTER TABLE resolution_outcome_proof_acceptances
    ADD CONSTRAINT uq_outcome_proof_reviewed_identity
    UNIQUE (tenant_id, run_id, case_stream_version, fact_id, observation_id);

CREATE TABLE resolution_run_proof_commands (
    tenant_id UUID NOT NULL,
    command_id UUID NOT NULL,
    run_id UUID NOT NULL,
    actor_id UUID NOT NULL,
    supervision_revision BIGINT NOT NULL,
    supervised_at TIMESTAMPTZ NOT NULL,
    authority_evidence_id UUID NOT NULL,
    authority TEXT GENERATED ALWAYS AS ('RESOLVER') STORED,
    expected_run_version BIGINT NOT NULL,
    case_stream_version BIGINT NOT NULL,
    fact_id UUID NOT NULL,
    observation_id UUID NOT NULL,

    CONSTRAINT pk_run_proof_commands PRIMARY KEY (tenant_id, command_id),
    CONSTRAINT uq_run_proof_command_acceptance UNIQUE (tenant_id, run_id),
    CONSTRAINT fk_run_proof_command_acceptance
        FOREIGN KEY (tenant_id, run_id, case_stream_version, fact_id, observation_id)
        REFERENCES resolution_outcome_proof_acceptances (
            tenant_id, run_id, case_stream_version, fact_id, observation_id
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_run_proof_command_supervision
        FOREIGN KEY (tenant_id, run_id, supervision_revision, actor_id, supervised_at)
        REFERENCES resolution_run_supervision_events (
            tenant_id, run_id, supervision_revision, supervisor_actor_id, occurred_at
        ) ON DELETE RESTRICT,
    CONSTRAINT fk_run_proof_command_authority
        FOREIGN KEY (tenant_id, authority_evidence_id, actor_id, authority)
        REFERENCES approval_authority_evidence (tenant_id, evidence_id, actor_id, authority)
        ON DELETE RESTRICT,
    CONSTRAINT ck_run_proof_command_versions CHECK (
        expected_run_version = 1 AND case_stream_version BETWEEN 1 AND 9007199254740990
    )
);

CREATE TRIGGER trg_run_proof_commands_prevent_mutation
BEFORE UPDATE OR DELETE ON resolution_run_proof_commands
FOR EACH ROW EXECUTE FUNCTION prevent_resolution_outcome_proof_acceptance_mutation();

COMMENT ON TABLE resolution_run_proof_commands IS
    'Immutable exact browser acceptance intent and attribution; acceptance owns proof timestamps and the paired closure. Not execution authority.';
COMMENT ON COLUMN resolution_run_proof_commands.authority_evidence_id IS
    'Tenant-wide resolver attestation checked at first acceptance, not a grant or evidence of current replay authority.';
COMMENT ON COLUMN resolution_run_proof_commands.supervised_at IS
    'Exact historical supervision event instant, not the proof acceptance time.';
