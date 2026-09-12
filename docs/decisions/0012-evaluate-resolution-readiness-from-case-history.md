# 0012. Evaluate resolution readiness from case history

- Status: proposed
- Date: 2026-09-12
- Owners: Ergon maintainers

## Context

A pinned contract identifies stable instructions but does not establish that
the case contains its required evidence or satisfies its applicability rule.
Creating a durable run at this point would imply policy, capability, and tool-
schema snapshots that have not been designed. Reading independent projections
could also combine different case versions during concurrent updates.

## Decision

Expose a read-only readiness query with four explicit outcomes: waiting for a
contract, waiting for evidence, not applicable, and ready. Evaluate against one
authoritative case-event history and return its stream version. Recover the
pinned contract separately because published revisions are immutable.

Build an evidence snapshot by taking the latest bound value for each supported
fact type in stream order. Report every missing required fact before evaluating
applicability; incomplete evidence must never be interpreted as evidence that a
contract does not apply. Compare opaque fact values exactly.

For the first slice, map `AccountAccessStateBound` to
`account.access.state`. Keep the pure evaluator in the domain and the event-to-
fact mapping in the application boundary.

## Alternatives

Creating a resolution run was deferred because it cannot yet pin policy and
tool contracts honestly. Evaluating read projections was rejected because
multiple queries could observe different case versions. Returning HTTP errors
for ordinary missing evidence was rejected because waiting and non-applicability
are expected planning outcomes, not request failures.

## Consequences

Callers can explain why the access-restoration contract is or is not ready at a
specific case version without causing side effects. `READY` grants no authority
and cannot be used as outcome proof. A later start-run command must use an
optimistic case-version precondition and revalidate policy, capability
availability, and every snapshot it persists.

Adding a registered fact does not automatically make it available to readiness
evaluation; its typed case event and explicit application mapping must arrive
together. Unknown or corrupt durable contract references remain operator
failures and fail closed.

## Verification

Domain tests cover missing-evidence precedence, exact applicability mismatch,
and readiness. PostgreSQL-backed API tests cover every waiting/ready outcome,
latest-evidence semantics, returned case versions, and tenant isolation.
