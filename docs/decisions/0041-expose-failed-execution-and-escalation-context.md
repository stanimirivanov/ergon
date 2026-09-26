# 0041. Expose failed execution and escalation context to the owning resolver

- Status: proposed
- Date: 2026-09-26
- Milestone: M05 - Human follow-up and resolver console
- Deciders: Ergon maintainers
- Supersedes: none

## TL;DR

Extend the owner-scoped browser case summary with the failed connector outcome
and exact exhausted-retry decision that caused human handoff. Reuse immutable
receipts and escalation events, validate them against the owned run inside the
existing transaction, and omit provider-operation and authority attribution.

## Context

The owner-scoped case summary exposes case evidence and the escalated run
snapshot, but it does not say when the connector failed, which connector was
involved, or which retry ceiling ended automated recovery. A resolver therefore
still has to reconstruct important execution and handoff context from internal
records before deciding how to continue the case.

The terminal connector receipt and escalation event already exist as immutable,
tenant-scoped records. Copying them into follow-up storage would create a second
source of truth. Exposing their complete internal forms would disclose provider
operation references, resolver identity, and authority-evidence attribution
that the browser does not need.

## Decision

Extend the existing owner-scoped case-summary application query and browser DTO.
After proving current ownership and validating the case, run, and escalated
state, read the run's terminal connector receipt and escalation event in the
same read transaction.

Require the receipt to identify the same case, run, policy, step, and capability
as the resolution run and to have outcome `FAILED`. Require the escalation to
identify the work item's exact event and reason, the same run attempt, and the
work-item opening instant. The escalation must not predate connector completion.
Missing or contradictory durable sources are internal invariant failures.

Return a `failedExecution` object containing connector name, failed outcome,
completion instant, and recording instant. Return an `escalation` object
containing retry-policy revision, source attempt, maximum attempts, occurrence
instant, and recording instant. Do not return authorization-consumption IDs,
grant IDs, provider-operation references, idempotency keys, resolver actor IDs,
or authority-evidence IDs.

## Alternatives considered

### Add a separate execution-history endpoint

- Benefits: execution history could evolve independently.
- Costs and risks: another owner-scoped route, request, and cache for two facts
  that are mandatory parts of the current escalation.
- Reason not selected: this slice has one failed receipt and one terminal
  escalation event, both required to interpret the existing summary.

### Return the complete receipt and escalation event

- Benefits: no browser-specific mapping decisions.
- Costs and risks: exposes internal authorization and provider correlation
  identifiers and broadens the compatibility boundary unnecessarily.
- Reason not selected: least disclosure provides every fact needed by the
  resolver without exporting internal attribution.

### Copy handoff details onto the work item

- Benefits: fewer joins and reads.
- Costs and risks: duplicates authoritative immutable records and can introduce
  contradictory meanings during future evolution.
- Reason not selected: the existing transaction can assemble and validate the
  authoritative records without a migration.

## Consequences

### Positive

The workbench can explain which execution failed and why bounded recovery ended
without accessing internal bearer routes or reconstructing private records.
Cross-record validation makes a malformed handoff fail closed.

### Negative

The summary adds two database reads and new additive response fields. Browser
clients that choose to consume them must validate their shape and relationships.

### Neutral or follow-up

This decision does not expose provider error payloads, predecessor-run history,
approval decisions, outcome-proof assessments, or lifecycle mutations. The UI
presentation remains a separate PR-sized task.

## Compatibility and migration

The browser response receives additive `failedExecution` and `escalation`
objects. Existing persistence and internal routes are unchanged, and no SQL
migration is required. Deploy the backend before a UI version that requires the
new fields. Rollback restores the earlier response shape without data changes.

## Security and operations

The existing session, tenant actor, ownership, open-state, current-authority,
no-store, and non-disclosing `404` rules remain unchanged. Receipt and
escalation records are not read until ownership and the base case/run invariants
pass. Provider-operation and human-attribution identifiers stay server-side.
The additional reads remain local database work inside the existing read
transaction; no remote call is introduced.

## Validation

Application tests verify ownership-first read order, source consistency, and
rejection of a non-failed receipt. Browser integration tests verify the new
response fields and omitted sensitive identifiers. Full Maven verification,
including Docker-backed PostgreSQL tests, protects the existing security,
transaction, and API behavior.
