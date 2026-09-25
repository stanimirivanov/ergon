# 0040. Expose an owned follow-up case summary through the browser session

- Status: proposed
- Date: 2026-09-25
- Milestone: M05 - Human follow-up and resolver console
- Deciders: Ergon maintainers
- Supersedes: none

## TL;DR

Add an owner-scoped BFF query that assembles existing case observations,
pinned contract identity, and immutable resolution-run facts only after proving
that the session actor owns the open follow-up under current resolver authority.
Hide every failure of that visibility test behind one `404` response.

## Context

The browser can discover and claim work, then recover its active claims, but an
owned row contains only routing and ownership facts. A resolver cannot yet see
why the case exists or which deterministic run reached escalation. Calling
existing internal case and run routes from the browser would require provider
credentials and would authorize each resource independently from the owned
work that grants the resolver a reason to inspect it.

The required facts already exist in PostgreSQL. Creating another projection or
copying evidence into follow-up storage would add synchronization and data-
meaning risks without adding behavior.

## Decision

Add:

```text
GET /bff/v1/tenants/{tenantId}/human-follow-ups/{workItemId}/case-summary
```

Resolve the actor from the verified OIDC session. In one application-owned
transaction, first select the `OPEN` work item only when its immutable claim
belongs to that actor and the actor has current tenant-wide `RESOLVER`
authority. Only then read the referenced case timeline, run-start snapshot,
and run-state projection. Treat a missing or contradictory referenced record
as an internal invariant failure rather than disguising corrupt durable state.

Return the follow-up queue, escalation reason, and timing; the case header and
pinned contract revision; source-attributed observations in stream order; and
the immutable run inputs plus current `ESCALATED` state. Use browser-specific
DTOs. Omit resolver actor and authority-evidence identifiers, provider subject,
and tokens. Observation content remains untrusted data.

Absence, closed work, different ownership, and missing current authority all
return `resolver-follow-up-case-summary-not-found`. This non-disclosing result
prevents callers from probing protected work. The route inherits BFF session,
tenant-actor binding, no-store, and disabled-mode behavior. It is read-only and
does not require CSRF protection.

## Alternatives considered

### Let the browser compose internal case and run endpoints

- Benefits: no new aggregate response.
- Costs and risks: exposes internal bearer boundaries and separates data access
  from follow-up ownership.
- Reason not selected: it conflicts with the confidential BFF and least-
  disclosure model.

### Persist a copied case summary on the work item

- Benefits: one-row reads and a historical creation-time view.
- Costs and risks: duplicates evidence, introduces versioning semantics, and
  can drift from the referenced run and case.
- Reason not selected: this slice needs current structured context, not a new
  snapshot contract.

### Return every internal case and run field

- Benefits: maximum UI flexibility.
- Costs and risks: leaks attribution and persistence details and creates an
  unnecessarily broad compatibility boundary.
- Reason not selected: the resolver view needs a deliberately narrow contract.

## Consequences

### Positive

The workbench can render meaningful owned-work context without provider tokens,
cross-resource authorization logic, or transcript reconstruction. Existing
durable sources remain authoritative.

### Negative

One request performs several database reads, and the browser response becomes
an additive compatibility boundary. Large observation histories are returned
without pagination in this first owner-scoped detail.

### Neutral or follow-up

This decision does not add general case search, claim release, reassignment,
completion, approval actions, execution controls, semantic summaries, or UI.

## Compatibility and migration

The endpoint is additive and exists only when browser sessions are enabled.
No persisted schema or internal bearer route changes. Rollback removes the
route and application query. No SQL migration is required.

## Security and operations

The path tenant and work-item IDs select a resource but grant no authority.
The verified session actor, immutable ownership, open state, and current
resolver evidence jointly gate access. The ownership query executes before
case or run reads. Responses are no-store and exclude provider credentials and
private claim attribution. Clients must treat observation strings as untrusted
text. Same-origin HTTPS requirements from ADR 0036 continue to apply.

## Validation

Service tests verify ownership-first query order, one transaction, hidden-work
short-circuiting, and escalated-state consistency. Docker-backed HTTP and
persistence tests verify session and tenant isolation, current-authority and
ownership filtering, browser response shape, omitted private attribution,
stable non-disclosing `404`, no-store headers, and disabled-mode `503`. The
full Maven reactor protects existing persistence and API behavior.
