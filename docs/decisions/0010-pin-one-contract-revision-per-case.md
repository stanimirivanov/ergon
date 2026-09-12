# 0010. Pin one contract revision per case

- Status: proposed
- Date: 2026-09-12
- Owners: Ergon maintainers

## Context

Later planning and execution must use stable instructions even after newer
contract revisions are published. A mutable "current contract" reference would
make a case's meaning change over time. Treating a pin as source evidence would
also mix an application decision into the observation timeline.

## Decision

Append `ResolutionContractRevisionPinned` to the case stream with an exact key
and positive revision. Accept the command only when that immutable revision is
published for the same tenant and the caller supplies the current case version.
Allow one pin per case; changing it requires a future explicit replanning model.

Project the event synchronously into `case_resolution_contract_pins`. Tenant-
scoped foreign keys bind the row to its case event and published revision, and
PostgreSQL rejects updates and deletes. Expose the projection on the case
timeline header, outside its source-observation entries.

## Alternatives

Resolving a mutable latest-revision alias was rejected because replay would not
recover historical meaning. Copying the full contract into every case event was
rejected because the immutable revision store already owns that snapshot and a
tenant-safe foreign key prevents dangling references. Repinning in place was
rejected because it would erase a material planning decision.

## Consequences

Cases can now identify stable instructions, while automatic selection, registry
validation, applicability checks, policy, and execution remain separate. A
future replan needs a new event and a projection that preserves every selection
decision rather than changing this pin.

The case application depends on the contract repository port for lookup; it
does not read the contract table directly. The projection schema deliberately
has a foreign key to that table as a database integrity boundary.

## Verification

Domain tests enforce the single-pin invariant. PostgreSQL integration tests
cover successful pinning, optimistic concurrency, cross-tenant absence,
immutable projection timestamps, API visibility, and upgrade from the previous
schema.
