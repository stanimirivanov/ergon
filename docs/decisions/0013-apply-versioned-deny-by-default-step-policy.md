# 0013. Apply versioned deny-by-default step policy

- Status: proposed
- Date: 2026-09-12
- Owners: Ergon maintainers

## Context

Evidence readiness says that a contract applies; it does not say that a step is
acceptable or who must approve it. Treating contract-declared risk or approval
as final would let authored instructions weaken platform safeguards. Creating a
durable run also requires an exact policy revision whose decision can be
replayed and explained.

## Decision

Introduce immutable, versioned capability policy rules. A capability without a
rule is denied. A matching rule calculates effective risk and approval as the
stricter of the contract declaration and policy minimum, so policy can never
downgrade either requirement.

Begin with policy revision `ergon.dev/policy/access-restoration/v1`. Its sole
rule covers `identity.account.unlock`, classifies it as at least `HIGH` risk,
and requires at least `REQUESTER` approval. Treat the risk order as
`LOW < MEDIUM < HIGH` and the approval order as
`NONE < REQUESTER < RESOLVER`.

Expose the result through a read-only resolution-plan query. Evaluate only the
first ordered step when evidence readiness is `READY`, and return the case
stream version, exact contract, and exact policy revision used. Describe policy
output as requirements rather than permission.

## Alternatives

Trusting contract declarations was rejected because contracts are configuration,
not an authority source. Default-allow rules were rejected because newly
registered capabilities would silently become plannable. Persisting approval or
execution state was deferred until actors, scopes, expiry, and capability
availability have explicit models.

## Consequences

The access-restoration path can now explain its next action and required human
authority without performing a write. Policy revisions become compatibility
boundaries: changing a rule's meaning requires a new revision, and future runs
must pin the revision they evaluate.

`HUMAN_APPROVAL_REQUIRED` does not prove that approval exists. Even a future
`NO_HUMAN_APPROVAL_REQUIRED` decision will not establish actor scope or tenant
capability availability. Those checks remain mandatory before execution.

## Verification

Domain tests prove strengthening, preservation of stricter contract requirements,
deny-by-default behavior, and unique rules. PostgreSQL-backed API tests prove
that no step is planned before readiness and that a ready access-restoration
case reports the expected policy revision and requester-approval requirement.
