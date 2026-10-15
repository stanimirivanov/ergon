# Pinned run evidence

## TL;DR

Current supervisors with current tenant-wide RESOLVER evidence can inspect the
run's pinned account-access bindings and their exact connector sources. They
cannot read unrelated observations or later case evidence through this projection.
Bindings establish attribution, not confidence, contradictions or verified truth.

## Browser contract

`GET /bff/v1/tenants/{tenantId}/resolution-runs/{runId}/console` adds
`pinnedEvidence`: `caseId`, `caseStreamVersion`, ordered `facts`, and `truncated`.
The coordinates must exactly match the run's pinned case and evidence version.
An empty list means no supported binding exists inside that prefix, not that the
case has no observations. Older-server omission means unsupported; null is invalid.

Each fact contains identity, stream position, `property=account.access.state`,
account reference, ACTIVE/LOCKED value, binding and recording instants, and its
linked `source`. Source fields preserve observation identity, stream position,
CONNECTOR origin, provider, opaque reference, untrusted content, occurrence and
recording instants. References are text, not instructions to navigate or fetch.

## Scope and history

Source position must precede binding position, which cannot exceed the pinned
boundary. Account reference must match the source reference. Unbound observations,
post-pin bindings and post-pin sources are excluded. Multiple values in stream
order are historical bindings, not simultaneous truth claims or contradiction
assessments. Clock corrections do not change stream order.

At most 100 bindings are returned in ascending stream order. `truncated=true`
means later bindings within the same pinned prefix were omitted. Never infer a
latest value, total count or complete history from a truncated response. No
pagination or source-fetch endpoint is introduced.

## Authorization and lifecycle

The existing session actor, exact current supervision and current resolver gate
run before private evidence is read. Fresh requests after authority expiry or
handover fail without revealing the former run's evidence. This is snapshot
authorization, not a continuous revocation guarantee. Rechecking or withdrawing
the private Console resource also closes source inspection.

## Deployment and limits

No migration, configuration flag, evidence mutation, compiler service or new
dependency is required. Deploy the additive backend contract before relying on
it in the UI; older servers remain inspectable with an unsupported message.
Confidence, validity windows, general claims, contradiction review, proof and
post-action evidence require their own reviewed contracts. The separate
[outcome verification](run-outcome-verification.md) projection now permits only
its exact selected post-action citation; this pinned prefix is unchanged. See
[ADR 0050](decisions/0050-disclose-supervisor-scoped-pinned-evidence.md).
