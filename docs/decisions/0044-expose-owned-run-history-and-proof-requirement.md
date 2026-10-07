# ADR 0044: Expose owned run history and the pinned proof requirement

- Status: Proposed
- Date: 2026-10-05
- Milestone: M05 - Human follow-up and resolver console
- Deciders: Ergon maintainers
- Supersedes:
- Superseded by:

## Context

The owner-scoped browser case summary identifies the escalated run, its failed
connector result, and the exhausted-retry decision. It does not show the
predecessor attempt or the ordered durable transitions that led to handoff.
Nor does it show the pinned condition that would have counted as successful
resolution. A resolver cannot distinguish the intended outcome from the failed
action without consulting internal APIs.

The run-start snapshots, run events, capability receipts, and pinned contract
already hold these facts. An escalated run is **not** in `VERIFYING`: outcome
proof is assessed only while verifying, and accepted proof closes the run and
case. Consequently, neither a failed connector nor an exhausted retry can be
reported as failed or accepted outcome proof. A browser trace fabricated from
these records would also misrepresent durable transitions as live tool spans.

## Decision

Extend the existing
`GET /bff/v1/tenants/{tenantId}/human-follow-ups/{workItemId}/case-summary`
response with `runHistory` and `outcomeProof`. Keep the session, current
ownership, open-work, tenant-wide resolver authority, and non-disclosing `404`
boundary from [ADR 0040](0040-expose-owned-follow-up-case-summary-through-browser-session.md).
No case, run, event, receipt, or contract data is read before that gate passes.

`runHistory.attempts` presents the linked attempts in attempt order, with
browser-safe, durable capability-result and retry-transition facts. Verify
tenant, case, contract, step, capability, attempt number, predecessor link,
event sequence, and terminal state relationships rather than trusting a
collection of independently valid records. Do not infer missing events, live
spans, latency, cost, approval, or authorization state. Retain the existing
failed-execution and escalation fields for their exact handoff meaning.

`outcomeProof` presents the fact and expected value from the **pinned contract
revision**, not a later contract or current case fact. For this escalated
handoff it reports `assessmentStatus: NOT_ASSESSED` and
`reason: RUN_NOT_VERIFYING`. This is a statement about assessment eligibility,
not a claim that the condition failed, that the latest fact is wrong, or that
resolution succeeded. Do not call the internal proof-assessment endpoint to
manufacture a result for an ineligible run.

Assemble the additional reads inside the existing application read
transaction and validate cross-record consistency. The transaction boundary
alone does not promise a single point-in-time database snapshot at the
default isolation level. This contract relies on immutable source records,
the owner gate, and explicit consistency checks; it does not advertise a live
or atomic cross-resource view.

## Alternatives considered

### Let the browser join internal run and contract APIs

- Benefits: no additional browser response fields.
- Costs and risks: exposes internal bearer boundaries and splits authorization
  from ownership of the follow-up work.
- Reason not selected: the browser must receive only a deliberately scoped
  projection after one ownership gate.

### Copy run history and proof onto the follow-up item

- Benefits: a single-row detail read.
- Costs and risks: duplicates authoritative facts and requires synchronization
  and migration rules when attempts or evidence evolve.
- Reason not selected: existing durable records can be read and checked without
  a new source of truth.

### Derive a proof result from failed execution

- Benefits: a simple success/failure badge for the UI.
- Costs and risks: confuses tool outcome with the separately assessed outcome
  condition, making an unassessed requirement look disproven.
- Reason not selected: proof meaning is a safety and compatibility boundary.

## Consequences

### Positive

The owning resolver can inspect the bounded attempt chain and the intended
outcome without provider credentials or a fabricated execution trace. The UI
can label proof as unassessed and distinguish durable history from live state.

### Negative

The detail query reads more durable records and its additive response fields
become browser compatibility contracts. Every new relationship adds an
invariant to validate, and the unpaginated chain is appropriate only while
the retry policy's attempt ceiling keeps it bounded.

### Neutral or follow-up

This decision does not add live run streaming, a trace tree, approval or
capability commands, proof assessment for non-verifying runs, case completion,
or an evidence graph. A future live console needs separately authorized
read/write contracts and a decision about snapshot consistency.

## Compatibility and migration

The browser response is additive; existing paths, persistence, and internal
APIs remain unchanged. No SQL migration is required. Deploy the backend
before a browser client that requires the fields. Reverting the backend after
such a client deploy requires reverting or tolerantly degrading that client;
no persisted-data rollback is involved.

## Security and operations

The verified session actor, exact current ownership, open work, and current
resolver authority still gate all case and run reads. The projection excludes
provider-operation references, idempotency and authorization identifiers,
human authority attribution, and raw model/tool payloads. Render the pinned
fact/value and any source text as untrusted text. The route remains read-only,
no-store, and unavailable when browser authentication is disabled. Missing or
contradictory referenced records fail as internal invariants, without leaking
protected work through differentiated existence responses.

## Validation

Application and browser tests cover ownership-first ordering, cross-tenant
and cross-owner non-disclosure, ordered and linked attempts, durable event and
receipt consistency, pinned proof-condition provenance, the explicit
`NOT_ASSESSED`/`RUN_NOT_VERIFYING` state, and omission of private identifiers.
Focused repository-policy and full Maven verification protect documentation,
API, persistence, and authorization behavior. A later consistency requirement
must be demonstrated with a concurrent-read test before changing isolation or
claiming a point-in-time view.
