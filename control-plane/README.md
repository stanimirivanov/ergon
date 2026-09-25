# Ergon control plane

## Purpose

`control-plane` is the active Spring Boot application for Ergon cases,
evidence bindings, contract revisions, planning, authority, approvals,
capability execution, outcome proof, retries, and escalation.
Escalation atomically opens durable resolver follow-up work.
Resolvers can discover unclaimed work through a bounded oldest-first inbox and
filter it by immutable named queue before acquiring authority-attributed
ownership. They can recover their active claimed work through a separately
paginated resolver-owned view.

It is a modular monolith: capability packages contain application-owned ports
and inbound/outbound adapters while `domain-kernel` remains framework-free.

## Runtime

The application requires PostgreSQL. Flyway owns schema creation and evolution.

| Variable | Meaning |
|:--|:--|
| `ERGON_DATABASE_URL` | PostgreSQL JDBC URL |
| `ERGON_DATABASE_USERNAME` | Database user |
| `ERGON_DATABASE_PASSWORD` | Database password |
| `ERGON_HUMAN_JWT_ISSUER_URI` | Trusted human JWT issuer; optional only for unprotected development paths |
| `ERGON_HUMAN_IDENTITY_PROVIDER` | Stable provider name paired with the issuer |
| `ERGON_BROWSER_SESSION_ENABLED` | Enables the confidential OIDC BFF; defaults to `false` |

The JWT variables are a pair. Protected human operations fail closed when the
trust mapping is absent. Enabling browser sessions additionally requires the
Spring OAuth client registration `ergon-workbench`, including a secret supplied
by deployment configuration. See [authentication](../docs/authentication.md)
for the exact registration contract. The server listens on port `8090`.

```powershell
$env:ERGON_DATABASE_URL = "jdbc:postgresql://localhost:5432/ergon"
$env:ERGON_DATABASE_USERNAME = "ergon"
$env:ERGON_DATABASE_PASSWORD = "ergon"
.\mvnw.cmd -pl control-plane -am spring-boot:run
```

The credentials above match the development-only root `docker-compose.yml`.
Never reuse them outside local development.

### Local database identity cutover

Compose uses the project name `ergon` and the explicit volume
`ergon-postgres-data`. It does not attach or delete a predecessor
`rag-help-center_postgres-data` volume. PostgreSQL initialization variables are
applied only to an empty data directory, so silently reusing the old volume
would not create the new database or role reliably.

The development database publishes port `5432` on IPv4 loopback only. The
control plane can therefore connect from the host, but a containerized client
needs an explicit network and must not rely on this host-only topology.

Before the first start after this change, stop a predecessor Compose project
without deleting its volumes. Replace the project name when it was overridden
locally:

```powershell
docker compose -p rag-help-center down
docker compose up -d postgres
```

Export and import any local Ergon data that must move to the new database;
there is no automatic cross-volume migration. To roll back, stop the new
project without `--volumes`, restore the previous Compose definition, and
restart the predecessor project so it reattaches its preserved volume. See
[ADR 0033](../docs/decisions/0033-separate-ergon-local-postgresql-identity.md).

## Interfaces and limitations

Capability contracts are documented under [`docs`](../docs), especially
[runs](../docs/runs.md), [contracts](../docs/contracts.md),
[policy](../docs/policy.md), and [authentication](../docs/authentication.md).
The current inventory is in [current state](../docs/development/current-state.md).

Browser endpoints under `/bff` use an optional confidential OIDC session and
never return provider tokens to browser code. They fail with `503` while the
feature is disabled. The BFF exposes the tenant actor session, shared resolver
inbox, and CSRF-protected idempotent claim command; item details, owned work,
and other mutations remain internal.
The current server-side session store is process-local and is not suitable for
a multi-instance deployment.

Endpoints under `/internal` are not public or production-ready authorization
boundaries. Tenant IDs in paths are scope selectors, not credentials.

## Verify

Docker must be running so Testcontainers executes PostgreSQL tests.

```powershell
.\mvnw.cmd -B -ntp -pl control-plane -am verify
```

Run the root reactor verification before merge.
