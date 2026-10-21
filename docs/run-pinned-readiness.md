# Readiness at the run pin

## TL;DR

Inspect the exact run's immutable evidence prefix and contract with the current,
version-labelled evaluator. This is re-evaluation, not stored historical planning,
current readiness, capability authority or outcome proof.

## Browser contract

`GET /bff/v1/tenants/{tenantId}/resolution-runs/{runId}/pinned-readiness`
requires the confidential session's current tenant-wide RESOLVER authority and
exact supervision, including terminal runs. Authorization precedes all private
evidence reads. No lease, original actor, CSRF or execution flag is needed.

The no-store response identifies run, case, pinned stream version, supervision
epoch, observation instant, exact contract key/revision and
`evaluator=ergon.dev/evaluation/pinned-readiness/v1`.

## Assessment and provenance

- `WAITING_FOR_EVIDENCE`: required facts are missing in contract order;
  `applicability=null` preserves missing-evidence precedence.
- `NOT_APPLICABLE`: all requirements exist but exact equality differs.
- `READY`: all requirements exist and exact equality holds.

Applicability contains fact, expectedValue and actualValue only after all required
evidence exists. selectedEvidence contains at most one currently supported
account-access binding and its exact source, only when required by this contract.
Unknown required fact types remain missing. Strings are inert untrusted data.

Selection uses the latest binding in **stream order at or before the run pin**,
not occurrence/recording times or latest case state. It is independent of the
Console's ascending first-100 history and may identify a binding outside that
window. Later bindings, sources bound only later and unrelated observations are
excluded. Citation metadata keeps observation, binding and database recording
times distinct; timestamps are not durations or causal ordering.

## Meaning and limits

This read uses the existing deterministic readiness evaluator on fixed inputs.
It does not claim to reproduce a persisted historical decision or selection
explanation. It assesses no current-case changes, reference registration,
tenant connector availability, effective policy, grant, execution eligibility,
side effect, verification result or case closure. READY does not authorize action.

The current evaluator supports account.access.state only. More evidence types,
confidence, validity and contradiction semantics are separate reviewed capabilities.

## Failures and rollout

Denied current access uses neutral no-store 404 assigned-resolution-run-not-found
before evidence lookup. Malformed coordinates use 400 invalid-run-pinned-readiness.
Storage, missing pin/boundary and attribution failures return cause-free no-store
503 run-pinned-readiness-unavailable, never successful missing-evidence results.
Existing session/identity/disabled-browser responses retain their safe meaning.

No migration, dependency or new flag is needed. Existing tenant/case indexes
scope the query, but stream-order selection may scan/sort that case's bindings
before LIMIT. Returned source material is bounded, not database work or latency;
a large-case benchmark and any resulting stream-order index remain separate work.
The application transaction contains only local reads and does not promise
continuous or atomic cross-query authority. Deploy backend before the dependent
UI and guide; unknown missing routes are unsupported, not empty assessments.
Rollback removes read/presentation without mutating durable facts.

See [ADR 0062](decisions/0062-inspect-readiness-at-supervised-run-pin.md).
