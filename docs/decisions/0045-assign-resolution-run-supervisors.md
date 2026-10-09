# ADR 0045: Assign resolution-run supervisors before browser disclosure

- Status: Proposed
- Date: 2026-10-09
- Milestone: M05 - Human follow-up and resolver console
- Deciders: Ergon maintainers
- Supersedes:
- Superseded by:

## Context

The browser Console currently discloses a failed run only through an open,
currently owned human follow-up. An in-progress run has no follow-up claim, so
that ownership rule cannot authorize live supervision. Internal run endpoints
are not browser contracts. Giving every tenant resolver every run would expand
case disclosure beyond the explicit supervisor chosen for this work. Neither a
follow-up claim nor a tenant-wide `RESOLVER` attestation is a run lease.

The first execution slice pins one contract step and records durable run states
and events. It does not record a live tool-span tree, model costs, or a lease
clock. The new read model must describe only recorded facts, including when a
run has terminated.

## Decision

Record one immutable initial supervisor assignment for a tenant-scoped
resolution run. It binds the existing run to one registered tenant actor who
has current, unexpired tenant-wide `RESOLVER` evidence when the assignment is
made. The assignment is an access relationship, not a lease, approval, claim
on human follow-up work, authorization to execute a capability, or permission
to change run state. A run without an assignment is not discoverable through
the new browser routes.

Only a dedicated machine-authenticated command may create the assignment:
`POST /internal/v1/tenants/{tenantId}/resolution-runs/{runId}/supervisor-assignments`.
The token must validate against the configured issuer and carry both audience
`ergon-run-supervision` and scope `ergon.run-supervision.assign`. Its signed
`ergon_tenant_id` claim must be a valid UUID equal to the path tenant; an
absent, malformed, or different claim grants nothing. The human browser
session, ordinary human bearer token, tenant path alone, and client-supplied
actor identifier cannot grant assignment authority. The first command creates
the immutable fact; an exact replay returns that fact without a second write.
A conflicting assignee or command fails without disclosing the incumbent.
No self-assignment, replacement, revocation, or handover is provided here.

The confidential BFF exposes two read-only, no-store contracts:

- `GET /bff/v1/tenants/{tenantId}/resolution-runs/assigned` returns a bounded
  keyset page of runs assigned to the verified session actor, ordered by
  `(assignedAt, assignmentId)`. Its current-work membership comprises
  `WAITING_FOR_APPROVAL`, `READY_FOR_AUTHORIZATION`, `VERIFYING`, and
  `ACTION_FAILED`; it excludes `VERIFIED_RESOLVED`, `SUPERSEDED`, and
  `ESCALATED`. It has no tenant-wide search or total count.
- `GET /bff/v1/tenants/{tenantId}/resolution-runs/{runId}/console` returns a
  browser-safe projection of the immutable run start and current run state for the exact
  assignee. The detail remains available after a run becomes terminal, subject
  to the same assignment and current-authority gate.

Both reads resolve the session actor within the requested tenant and require
current tenant-wide `RESOLVER` evidence as well as the exact immutable
assignment. An actor without current evidence receives an empty list and a
non-disclosing detail absence; another assignee cannot infer the run through
the detail route. Perform the assignment/authority gate before reading private
case or run details. A selected tenant ID is navigation context, not authority.

The list is a current best-effort view: state and assignment facts can change
between pages or between list and detail. Stable keyset ordering does not
promise historical point-in-time membership. Detail validates that the
selected run and current state agree. A later projection may add case evidence,
events, and pinned proof with explicit cross-record validation; this first
response must not invent absent stages, spans, latency, cost, approval
decisions, or proof.

## Alternatives considered

### Let any current tenant resolver inspect every active run

- Benefits: no assignment persistence or machine command.
- Costs and risks: broad disclosure of all cases to every resolver and no
  accountable supervisor identity.
- Reason not selected: current resolver authority is necessary but not
  sufficient for the approved least-privilege supervision boundary.

### Reuse human follow-up claims for supervision

- Benefits: an existing owner-scoped browser contract.
- Costs and risks: claims exist only after escalation and mean ownership of
  follow-up work, not responsibility for an active run.
- Reason not selected: it would either hide active runs or corrupt the claim's
  meaning.

### Allow browser self-assignment

- Benefits: no trusted assignment integration.
- Costs and risks: any resolver who learns a run ID could make themselves its
  assignee, collapsing the boundary into tenant-wide access.
- Reason not selected: assignment must originate from a separately authorized
  service, not from a browser user seeking disclosure.

## Consequences

### Positive

An assigned resolver can recover their active run set and inspect durable
context without promoting internal bearer APIs or receiving another resolver's
runs. Current authority expiry removes browser visibility without rewriting
the historical assignment. Terminal detail preserves an auditable read path.

### Negative

An incorrectly assigned or departed supervisor cannot be replaced through
this slice. Runs started before rollout remain invisible until a trusted
assignment is recorded. The new index, read joins, and machine ingress add
schema, contract, and operational obligations. List and detail may observe
different current states; callers must revalidate before relying on either.

### Neutral or follow-up

Explicit handover, lease ownership and countdown, steering, capability
approval commands, multi-step execution traces, measured latency/cost,
attributable claims, contradictions, and verification-check progress need
separate contracts. The UI must label the durable relationship as an assigned
supervisor, never as a lease owner.

## Compatibility and migration

Add a tenant-scoped assignment table with foreign keys to the exact run and
actor and a uniqueness constraint enforcing one initial assignee per run.
Do not rewrite existing run starts, follow-up claims, or case history; do not
backfill assignments from an unrelated owner. Deploy the migration and
machine-authenticated command before the browser relies on the two new reads.
Existing BFF routes remain unchanged. Once assignments exist, rolling back
the new reads removes access without deleting those facts; a replacement
assignment model must explicitly migrate or supersede them, not mutate history.

## Security and operations

Validate issuer, signature, expiration, audience, scope, and the signed
`ergon_tenant_id` claim against the path tenant before assignment executes.
The issuer must restrict the dedicated audience/scope and tenant claim to
trusted dispatcher clients provisioned for that tenant; the API cannot infer
trustworthy client provisioning from a JWT claim alone. Production IdP
provisioning of tenant-scoped dispatcher clients, ingress restriction,
credential rotation, and rate limiting are not established by this decision
and must be completed before describing the route as production-ready. Never
log tokens or expose machine identity claims through the BFF.

Browser reads retain the confidential same-origin session boundary and do
not return provider tokens, raw connector payloads, private authority
attestations, or another assignee's identity. Missing assignment, missing
current resolver evidence, wrong tenant, and other-assignee detail access are
non-disclosing. The machine command's conflict response omits the incumbent
actor. Audit assignment creation and rejected access without logging case
content or credential material.

## Validation

This change tests exact machine-command replay and conflicting intent through
the application service and PostgreSQL, immutable/tenant-safe assignment
persistence, keyset discovery, and application reads denied to a different
assignee, tenant, or expired resolver. Web-slice security tests exercise the
issuer, audience, scope, subject, and tenant-claim gate with synthetic JWTs.
Browser API tests cover no-store responses, bounded parameters, private-detail
absence, and the narrow recorded response shape. The focused repository-policy
check and full Maven verification must pass.

These tests do not prove simultaneous competing assignment commands, a real
IdP-issued JWT's signature/expiration validation, or a browser request through
the real session and PostgreSQL ports together. Production rollout additionally
requires those checks, tenant-scoped IdP client provisioning, and restricted
ingress for the remaining development-only internal routes.
