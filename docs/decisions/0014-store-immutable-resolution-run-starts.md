# 0014. Store immutable resolution-run starts

- Status: proposed
- Date: 2026-09-13
- Owners: Ergon maintainers

## Context

Readiness and policy planning are repeatable queries, but they do not preserve
which evidence and rules an execution attempt used. Approval and execution need
a durable identity and stable inputs. Starting from a stale case version could
mix a previously displayed plan with newer evidence, while treating a start as
authorization would collapse policy requirements and actor authority.

## Decision

Create one immutable, tenant-scoped run-start snapshot only from a ready,
policy-allowed plan. Pin the case stream version, exact contract and policy
revisions, first step, capability, effective risk, and approval requirement.
Derive `WAITING_FOR_APPROVAL` when a human approval is required and
`READY_FOR_AUTHORIZATION` otherwise; neither state authorizes execution.

Require a quoted case-version `If-Match` precondition. Re-evaluate the plan in a
transaction, then lock the case projection and compare its version immediately
before insert. Retain a foreign key to the final case event so the database also
proves that the evidence boundary existed in the same tenant.

Make start rows immutable. Future approval and execution transitions append run
events and use separate projections rather than modifying the original inputs.
The first runtime slice permits one run per case until retry and replanning
semantics define how attempts relate.

## Alternatives

Persisting only a pointer to the case was rejected because later contract or
policy lookup would not fully explain the original decision. Creating a run
from the earlier read-only plan response was rejected because the case might
advance between requests. Marking a no-approval run ready to execute was
rejected because human approval is only one authorization prerequisite.

## Consequences

A run can be retrieved and audited without recomputing current configuration.
Case writes serialize with run creation at the final version check, and tenant-
scoped keys and foreign keys prevent cross-tenant references. The immutable row
adds storage duplication intentionally to preserve historical meaning.

This increment does not model actors, approval grants, capability availability,
tool schemas, execution events, receipts, retries, or outcome verification. The
one-run-per-case constraint must be revisited together with explicit attempt,
retry, and replanning semantics.

## Verification

Domain tests cover initial-state derivation and snapshot invariants. PostgreSQL-
backed API tests cover successful start and retrieval, stale and not-ready
rejection, duplicate prevention, tenant isolation, and database-enforced
immutability. Migration verification covers empty and supported upgrade paths.
