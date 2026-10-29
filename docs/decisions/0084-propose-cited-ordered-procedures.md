# ADR 0084: Propose cited ordered procedures

- Status: Accepted
- Date: 2026-10-29
- Milestone: M06 - Evidence Compiler
- Deciders: Ergon maintainers
- Supersedes: none
- Superseded by: none

## Context

The approved M06-PR6 slice needs reviewable ordered instructions without equating
documentation with capability registration or executable plans. Existing syntax
projections preserve attribution but an ORDERED_LIST alone does not establish its
supported step structure. Optional API links need explicit source selection and
must not become implicit calls or remote reference resolution.

## Decision

Select an exact existing Markdown extraction and ORDERED_LIST observation. A
separate application-owned outline port identifies flat list items containing one
paragraph each. Preserve original list/item quotations and code-point positions;
number steps by one-based source order, retaining original author markers in quotes.
Reject nested, empty, mixed, code-block and multi-paragraph item structures rather
than silently discarding unsupported content. Existing compiler v1 projections stay
unchanged; the binding rule is `ordered-list-procedure-v1`.

An optional explicit binding selects an exact API_OPERATION observation from a
stored OpenAPI source in the same tenant. Preserve its source, extraction/compiler,
observation index, pointer and quotation. No automatic matching, capability mapping,
credential injection or invocation occurs. Authorization precedes each private
source read; inspect historical stored quotations without reinterpreting them under
a later compiler revision. Every proposal remains PROPOSED; review is M06-PR7.

Store an immutable, independently versioned procedure definition and relational
operation-source foreign keys in one transaction. Deferred integrity checks require
links to exactly match the nested definition, preventing partial or later-added
links. Same-actor exact retries preserve original recording time and provenance;
changed commands conflict. No existing claim, source, case or contract meaning changes.

## Alternatives considered

### Interpret every Markdown structure as executable steps

- Benefits: broader input acceptance.
- Costs and risks: ambiguous order and silently lost constraints or nested semantics.
- Reason not selected: start with an explicit, verifiable supported subset.

### Flatten or automatically invoke linked API operations

- Benefits: fewer human binding actions.
- Costs and risks: inferred intent and unreviewed external effects.
- Reason not selected: links are documentary declarations, never execution authority.

## Consequences

### Positive

Review can inspect ordered instructions and exact source/API attribution. Domain
values remain independent of CommonMark, JSON, HTTP and persistence libraries.

### Negative

Only flat single-paragraph steps are supported. Referenced operation metadata and
quotations increase payload size; aggregate budgets must reject excess explicitly.

### Neutral or follow-up

Limits are 20 steps/bindings, a 256-unit title, 16 KiB command JSON, and 256 KiB of
aggregate UTF-8 quotations. Titles and pointers must be valid Unicode; NUL quotations
are unsupported by the versioned JSONB format. Existing compiler budgets also apply.
Review, contradiction adjudication and bundle publication remain later slices.

## Compatibility and migration

Add a new procedure table and operation-source links. Deploy the migration before
the additive API. Reverting application code retains immutable audit records; do not
reverse a shared migration. The definition schema is `ergon.dev/procedure-definition/v1`.

## Security and operations

Use existing workbench session/CSRF and current tenant-wide resolver authority.
Clients select identities and indices, never quotations, actors or accepted status.
Responses use no-store. Fail closed on stale selection, invalid references, malformed
structure and unsupported durable schema. No worker, dependency or remote effect is
introduced. Source references remain protected by tenant-scoped foreign keys.

## Validation

Test Unicode/CRLF quotations, source order, rejected structures, explicit operation
selection, authorization ordering, immutable retry recovery and atomic reference
integrity. Run repository policy, focused tests/static checks and full verification;
report unavailable Docker coverage exactly. PR sizing follows coherence rather than
numeric line counts, as explicitly requested for this delivery.
