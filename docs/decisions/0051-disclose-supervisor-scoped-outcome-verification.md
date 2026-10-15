# ADR 0051: Disclose supervisor-scoped outcome verification

- Status: Proposed
- Date: 2026-10-15
- Milestone: M04 - Guarded Execution and Verification
- Deciders: Ergon maintainers
- Supersedes:
- Superseded by:

## Context

The user approved current supervisors with current tenant-wide RESOLVER authority
reading the pinned outcome condition, a current verification assessment and its
exact selected post-action binding/source, or immutable accepted proof and its
supporting binding/source. ADR 0050's pinned prefix deliberately cannot provide
post-action proof. Execution success is not outcome proof; a matching read is not
the durable acceptance transaction already implemented under ADRs 0023 and 0024.

## Decision

Add `outcomeVerification` to the protected Console response after the existing
current authority/supervision gate. Keep pinned evidence unchanged. Project one
condition from the exact pinned contract. Report NOT_ASSESSED/RUN_NOT_VERIFYING
outside VERIFYING and VERIFIED_RESOLVED, without evaluating case history.

For VERIFYING, reuse the runtime's evaluator and case-event conversion against
one loaded case history. Report PENDING with its existing missing/mismatch reason,
or MATCHING for the internal evaluator's read-only Accepted result. Disclose only
its selected binding/source, not all later observations or an arbitrary matching
historical fact. Preserve source occurrence and binding times, not invented case
event database recording times absent from the consumed port.

For VERIFIED_RESOLVED, recover stored acceptance rather than reassessing. Check
the run/condition/pin, state revision/update, evidence identities/positions/value,
successful receipt and linked case closure against durable records. Case closure
immediately follows the assessed version. Do not require exact equality between
case JSON occurrence nanoseconds and relational acceptance timestamps rounded to
database precision; identities and stream positions establish the paired decision.
No later acceptance enters a snapshot that observed VERIFYING. Reads do not lock
writers, mutate state, grant authority or invoke a remote capability.

## Alternatives considered

### Expose the complete current case or reuse the pinned prefix

- Benefits: existing read APIs without a dedicated projection.
- Costs and risks: broadens disclosure or mistakes pre-action evidence for proof.
- Reason not selected: disclose only the evaluator/acceptance's exact citation.

### Treat a matching assessment as accepted completion

- Benefits: fewer visible states.
- Costs and risks: later evidence may change a match; no durable closure exists.
- Reason not selected: MATCHING and ACCEPTED have distinct meanings and contracts.

## Consequences

### Positive

The Console can explain pending verification and inspect attributable durable
proof without confusing receipts, current matches and committed completion.

### Negative

Only one account-access condition is supported. As in the existing internal
assessment, case-event replay loads the complete history internally; narrow output
bounds disclosure, not query/replay cost. This is not a scalable multi-check engine.

### Neutral or follow-up

Browser acceptance commands, new proof types, general contradictions, continuous
revocation, monitoring and broader runtime controls remain separate capabilities.

## Compatibility and migration

No DDL, backfill, configuration flag or new endpoint. Deploy backend first. Older
clients ignore the additive field; newer clients distinguish omission from every
supported status and reject explicit null/inconsistent proof as a complete read
failure. Rolling back this read projection changes no stored acceptance or case.
Existing internal proof endpoints retain their original terminology and behavior.

## Security and operations

Current tenant authority and supervision are checked before contract/case/proof
queries. Preserve neutral denial and no-store responses. Never disclose unrelated
later sources, authority/event identities or connector payloads. Source content
and references remain untrusted text. Authorization and verification are snapshots,
not continuous revocation or external-system observation by the browser.

## Validation

Exercise the actual PostgreSQL execution/proof lifecycle through the protected
Console use case, including missing evidence, later mismatch, mutable match,
immutable acceptance/replay and closure. Preserve gate-before-read and tenant/
supervisor denial regressions. UI codec, component, Chromium and executable-guide
checks prove fail-closed attribution, compatibility, withdrawal and accessibility;
the guide is synthetic, not deployed OIDC/database integration evidence.
