# Resolution policy

> **TL;DR:** Policy revision `ergon.dev/policy/access-restoration/v1` allows
> planning `identity.account.unlock` only with `HIGH` effective risk and at
> least `REQUESTER` approval. A policy requirement is never authorization.

## Planning boundary

Query `GET /internal/v1/tenants/{tenantId}/cases/{caseId}/resolution-plan`.
The response embeds the evidence-readiness snapshot and the exact policy
revision. `nextStep` is absent until readiness is `READY`; once present, it
identifies the contract's first ordered step, its declarations, and the
effective policy requirements.

Policy rules are deny-by-default. A matching rule takes the stricter risk and
approval from the contract declaration and policy minimum. The current order is
`LOW < MEDIUM < HIGH` for risk and `NONE < REQUESTER < RESOLVER` for approval.
Policy therefore cannot downgrade a contract requirement.

`HUMAN_APPROVAL_REQUIRED` means a later workflow must obtain and verify the
specified authority. `NO_HUMAN_APPROVAL_REQUIRED` would remove only the human-
approval prerequisite; it would not establish actor scope, tenant capability
availability, or permission to execute. `DENIED` supplies a stable reason and
must not be overridden by model output or retrieved content.

The endpoint is read-only. It does not persist a policy snapshot, create a run,
authenticate an actor, record an approval, or invoke a capability.
