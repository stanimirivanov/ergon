# 0033. Separate Ergon local PostgreSQL identity

- Status: proposed
- Date: 2026-09-18
- Milestone: M08 - Ecosystem and production readiness
- Deciders: Ergon maintainers
- Supersedes: none

## TL;DR

Run local Ergon PostgreSQL under the Compose project `ergon`, initialize the
database and role as `ergon`, and store data in the explicit
`ergon-postgres-data` volume. Preserve and detach predecessor volumes rather
than mutating or deleting them.

## Context

After retiring every predecessor executable and adopting Ergon build
coordinates, local Compose still initializes `rag_help_center` with the `rag`
role in a project-derived predecessor volume. These defaults are visible in
runtime configuration and can cause new Ergon data to share storage identity
with obsolete article, retrieval, and embedding objects.

PostgreSQL applies `POSTGRES_DB`, `POSTGRES_USER`, and `POSTGRES_PASSWORD` only
when initializing an empty data directory. Renaming those values while
reattaching an initialized predecessor volume can leave the requested database
and role absent. Deleting or rewriting that volume would destroy local state
without an explicit data-retention decision.

## Decision

Set the Compose project name to `ergon`. Initialize the development database,
user, and password as `ergon`, and use the explicit Docker volume name
`ergon-postgres-data`. Align the control-plane local startup example with those
defaults. Publish PostgreSQL on IPv4 loopback only because the checked-in
credentials are intentionally suitable only for host-local development.

Do not attach, modify, migrate, or delete a predecessor volume. Operators stop
the old Compose project without `--volumes`, start the new project, and export
and import only the local Ergon data they deliberately choose to carry forward.

## Alternatives considered

### Reuse and mutate the predecessor volume

- Benefits: existing local data remains immediately attached.
- Costs and risks: role, ownership, privileges, obsolete objects, and partial
  initialization differ between installations and cannot be inferred safely.
- Reason not selected: an automated in-place mutation would make unverified
  assumptions about persisted state.

### Delete and recreate the predecessor volume

- Benefits: guarantees a clean database under the new defaults.
- Costs and risks: irreversibly deletes local Ergon and predecessor data.
- Reason not selected: cleanup is not required for identity separation and
  destructive deletion needs explicit operator intent.

### Keep predecessor database identifiers

- Benefits: no local configuration transition.
- Costs and risks: active runtime examples continue to imply that Ergon shares
  the predecessor product's storage identity.
- Reason not selected: the new explicit volume provides a reversible boundary
  without deleting the old state.

## Consequences

### Positive

Fresh local environments use only Ergon names, and the explicit volume remains
stable if the checkout directory changes. Predecessor state stays recoverable
but cannot be selected accidentally by the checked-in Compose definition.

### Negative

The first start creates an empty Ergon database. Local data needed from the old
volume requires an intentional export and import. An old Compose container may
hold port `5432` until its project is stopped.

### Neutral or follow-up

The GitHub repository and checkout directory keep their factual current names.
Production database naming, credentials, provisioning, backup, restore, and
data migration remain deployment-specific work.

## Compatibility and migration

Before starting the new definition, stop the old Compose project without
`--volumes`. Starting the new definition creates `ergon-postgres-data`; the old
volume remains unchanged. Migrate required local data through an explicit
logical export and import after reviewing which schemas belong to Ergon.

Rollback stops the `ergon` project without `--volumes`, restores the preceding
Compose definition, and starts the predecessor project. Both volumes remain
available until an operator separately authorizes deletion.

## Security and operations

The checked-in `ergon` password is development-only and must never be reused in
shared or production environments. Compose binds host port `5432` to
`127.0.0.1`; clients in another container or on another host require an
explicitly designed network and credential arrangement. Production credentials
continue to come from the existing `ERGON_DATABASE_*` variables. The cutover
performs no network or privilege expansion.

Operators should use `docker compose ls` to identify an overridden predecessor
project name and `docker volume ls` to confirm both volumes before any manual
cleanup. Cleanup commands are intentionally not automated by this repository.

## Validation

Render the Compose model and verify its project, database, role, health check,
loopback port binding, and explicit volume name. Run the complete Maven reactor
with Docker-backed PostgreSQL tests to prove the application schema remains
independent of the development database's chosen name and role.
