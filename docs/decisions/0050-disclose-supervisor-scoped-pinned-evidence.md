# ADR 0050: Disclose supervisor-scoped pinned evidence

- Status: Proposed
- Date: 2026-10-15
- Milestone: M06 - Evidence compiler
- Deciders: Ergon maintainers
- Supersedes:
- Superseded by:

## Context

The Console exposes a run's evidence boundary but no inspectable evidence.
The user approved current supervisors with current tenant-wide RESOLVER
authority reading pinned case facts and their exact linked sources. Existing
account-access bindings provide attribution, not generalized claims, confidence,
validity intervals or contradiction assessments.

## Decision

Add `pinnedEvidence` to the existing protected Console response. Establish the
current supervisor/resolver gate before querying case material. Disclose only
account-access bindings whose stream positions are at or before the run's
immutable case boundary, and only the earlier connector sources they reference.
Do not expose unrelated observations, later evidence, case goals or a whole
transcript. Preserve observation, binding and database recording instants.

Use a consumer-owned read port implemented by a case-owned PostgreSQL adapter.
One statement joins the exact tenant/case, bounded binding prefix and sources;
missing attribution fails closed rather than dropping a binding. Verify subject,
source order and pinned coordinates across records. No schema or evidence writes
are needed. Return the first 100 bindings in stream order with explicit truncation;
do not select a mutable latest value or imply complete history when truncated.

The browser validates the same attribution before caching. RTK owns the snapshot;
keyed binding components own only modal interaction. Source content and references
remain plain untrusted text. Revalidation, access failure, handover and navigation
withdraw the inspector with the private resource. No new cache or application
framework is introduced.

## Alternatives considered

### Return all case observations or the latest facts

- Benefits: broader context with existing query APIs.
- Costs and risks: exceeds approved scope and changes pinned meaning as the case evolves.
- Reason not selected: expose only attributable material inside the pinned prefix.

### Render historical value changes as contradictions

- Benefits: resembles the concept graph without a new domain model.
- Costs and risks: normal temporal changes become false conflict claims.
- Reason not selected: contradiction scope, validity and resolution need a separate reviewed model.

## Consequences

### Positive

Supervisors can inspect actual binding/source relationships without transcript
reconstruction, invented trust scores or proof claims.

### Negative

Only the registered account-access property is supported. Bounded large prefixes
are explicitly incomplete; general evidence compilation remains undelivered.

### Neutral or follow-up

General claims, contradiction review, validity, confidence, post-run evidence and
outcome verification remain separate capabilities.

## Compatibility and migration

No migration or backfill. Deploy backend first; older UIs ignore the additive
field and newer UIs treat omission as unsupported, not empty. Explicit null or
malformed attribution fails the complete read. Application rollback removes
disclosure without altering stored evidence or the existing handover constraints.

## Security and operations

Keep tenant predicates on every join. Current assignment alone without current
resolver evidence grants nothing. The existing session and no-store response
apply. Do not fetch opaque references, execute source instructions or persist
private content in URLs/storage/logs. Reads are snapshots, not continuous
revocation; the browser rechecks explicitly. The row/text bounds limit response
material, not total case history or query execution time.

## Validation

Real PostgreSQL/browser-security tests cover exact attribution, pinned exclusion,
unbound sources, missing/out-of-range cases, truncation, tenant/actor denial and
handover. UI protocol/component/Chromium checks cover malformed attribution,
plain-text rendering, modal retirement, keyboard and narrow reflow. The executable
guide is explicitly synthetic, not proof of a live IdP/deployment integration.
