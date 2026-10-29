-- Bounded original source bytes; immutable revision identity and submitter attribution.
CREATE TABLE knowledge_source_revisions (
    tenant_id UUID NOT NULL,
    source_id UUID NOT NULL,
    digest TEXT NOT NULL,
    format TEXT NOT NULL,
    content BYTEA NOT NULL,
    submitted_by UUID NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT pk_knowledge_source_revisions PRIMARY KEY (tenant_id, source_id, digest),
    CONSTRAINT fk_knowledge_source_submitter FOREIGN KEY (tenant_id, submitted_by)
        REFERENCES human_actors (tenant_id, actor_id),
    CONSTRAINT ck_knowledge_source_digest CHECK (digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_knowledge_source_content_digest CHECK (encode(sha256(content), 'hex') = digest),
    CONSTRAINT ck_knowledge_source_format CHECK (format IN ('COMMONMARK', 'OPENAPI_3_0_JSON')),
    CONSTRAINT ck_knowledge_source_size CHECK (octet_length(content) <= 65536)
);

CREATE FUNCTION reject_knowledge_source_mutation() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'knowledge source revisions are immutable';
END;
$$;

CREATE TRIGGER trg_knowledge_source_immutable
BEFORE UPDATE OR DELETE ON knowledge_source_revisions
FOR EACH ROW EXECUTE FUNCTION reject_knowledge_source_mutation();
