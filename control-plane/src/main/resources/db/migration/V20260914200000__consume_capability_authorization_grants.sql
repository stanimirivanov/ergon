-- ============================================================================
-- Tenant capability routes and single-use authorization consumption
-- ============================================================================
--
-- A tenant route names the connector selected for one registered capability.
-- An immutable consumption reserves one still-current authorization grant
-- through that route before any credential lookup or external call occurs.
-- Composite foreign keys preserve the exact grant scope and selected route;
-- uniqueness spends each grant at most once under concurrent commands.
--
-- Routes are provisioned outside the runtime API in this slice. Both tables are
-- append-only; route replacement, connector health, invocation, and receipts
-- remain later transitions rather than mutations of these audit records.
-- ============================================================================


CREATE TABLE tenant_capability_routes (
    tenant_id UUID NOT NULL,
    capability TEXT NOT NULL,
    connector TEXT NOT NULL,
    registered_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT pk_tenant_capability_routes
        PRIMARY KEY (tenant_id, capability),

    CONSTRAINT uq_tenant_capability_routes_connector
        UNIQUE (tenant_id, capability, connector),

    CONSTRAINT ck_tenant_capability_routes_capability
        CHECK (
            length(capability) BETWEEN 1 AND 150
            AND capability ~ '^[a-z][a-z0-9]*(\.[a-z][a-z0-9-]*)+$'
        ),

    CONSTRAINT ck_tenant_capability_routes_connector
        CHECK (
            length(connector) BETWEEN 1 AND 100
            AND connector ~ '^[a-z][a-z0-9]*([.-][a-z0-9]+)*$'
        )
);


CREATE FUNCTION prevent_tenant_capability_route_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'tenant capability routes are immutable; append a future route transition instead of using %',
        TG_OP
        USING ERRCODE = '55000';
END;
$$;


CREATE TRIGGER trg_tenant_capability_routes_prevent_mutation
BEFORE UPDATE OR DELETE
ON tenant_capability_routes
FOR EACH ROW
EXECUTE FUNCTION prevent_tenant_capability_route_mutation();


ALTER TABLE capability_authorization_grants
    ADD CONSTRAINT uq_capability_authorization_grants_consumption_scope
        UNIQUE (
            tenant_id, authorization_grant_id, run_id, case_id,
            policy_revision, step_id, capability, authorized_at, expires_at
        );


CREATE TABLE capability_authorization_consumptions (
    tenant_id UUID NOT NULL,
    authorization_consumption_id UUID NOT NULL,
    authorization_grant_id UUID NOT NULL,
    run_id UUID NOT NULL,
    case_id UUID NOT NULL,
    policy_revision TEXT NOT NULL,
    step_id TEXT NOT NULL,
    capability TEXT NOT NULL,
    connector TEXT NOT NULL,
    grant_authorized_at TIMESTAMPTZ NOT NULL,
    grant_expires_at TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT pk_capability_authorization_consumptions
        PRIMARY KEY (tenant_id, authorization_consumption_id),

    CONSTRAINT uq_capability_authorization_consumptions_grant
        UNIQUE (tenant_id, authorization_grant_id),

    CONSTRAINT fk_capability_authorization_consumptions_grant
        FOREIGN KEY (
            tenant_id, authorization_grant_id, run_id, case_id,
            policy_revision, step_id, capability,
            grant_authorized_at, grant_expires_at
        )
        REFERENCES capability_authorization_grants (
            tenant_id, authorization_grant_id, run_id, case_id,
            policy_revision, step_id, capability, authorized_at, expires_at
        )
        ON DELETE RESTRICT,

    CONSTRAINT fk_capability_authorization_consumptions_route
        FOREIGN KEY (tenant_id, capability, connector)
        REFERENCES tenant_capability_routes (tenant_id, capability, connector)
        ON DELETE RESTRICT,

    CONSTRAINT ck_capability_authorization_consumptions_validity
        CHECK (
            consumed_at >= grant_authorized_at
            AND consumed_at < grant_expires_at
        )
);


CREATE FUNCTION prevent_capability_authorization_consumption_mutation()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION
        'capability authorization consumptions are immutable; a grant can be spent only once instead of using %',
        TG_OP
        USING ERRCODE = '55000';
END;
$$;


CREATE TRIGGER trg_capability_authorization_consumptions_prevent_mutation
BEFORE UPDATE OR DELETE
ON capability_authorization_consumptions
FOR EACH ROW
EXECUTE FUNCTION prevent_capability_authorization_consumption_mutation();


COMMENT ON TABLE tenant_capability_routes IS
    'Immutable tenant configuration selecting one connector for a registered capability.';

COMMENT ON COLUMN tenant_capability_routes.connector IS
    'Stable routing identifier only; credentials and live connector health are not stored here.';

COMMENT ON TABLE capability_authorization_consumptions IS
    'Immutable reservations that spend one current grant before connector invocation.';

COMMENT ON COLUMN capability_authorization_consumptions.connector IS
    'Tenant-configured connector route selected when the grant was consumed.';

COMMENT ON COLUMN capability_authorization_consumptions.grant_authorized_at IS
    'Copied inclusive start of the grant validity interval.';

COMMENT ON COLUMN capability_authorization_consumptions.grant_expires_at IS
    'Copied exclusive end of the grant validity interval.';

COMMENT ON COLUMN capability_authorization_consumptions.consumed_at IS
    'Application-clock instant at which the grant was irrevocably reserved.';

COMMENT ON COLUMN capability_authorization_consumptions.recorded_at IS
    'Database instant at which the immutable consumption became durable.';
