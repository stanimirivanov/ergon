# ADR 0061: Disclose the exact supervised run's pinned contract

- Status: Accepted
- Date: 2026-10-20
- Milestone: M02 - Evidence and contracts
- Deciders: Ergon maintainers; standing Resolver Console authorization/disclosure approval
- Supersedes:
- Superseded by:

## Context

The Console shows pinned contract identity and one runtime step, but cannot
explain the immutable applicability, required evidence, declared instructions
and outcome condition behind that identity. Gateway history cannot supply
those declarations, and internal contract publication APIs are not browser
contracts. Original authoring YAML is deliberately not stored (ADR 0009).

## Decision

Expose read-only `GET /bff/v1/tenants/{tenantId}/resolution-runs/{runId}/pinned-contract`.
Resolve the confidential session actor and check current tenant-wide RESOLVER
evidence and exact current supervision at one observation instant before private
run or revision reads. Terminal runs qualify; no control lease, originating actor,
CSRF setup or execution rollout flag is required. Access can change afterwards;
the read promises neither continuous authority nor an atomic authorization snapshot.

Use existing application-owned run and immutable tenant contract repositories
inside the existing local transaction boundary. Fetch only the tenant/key/revision
in that run, never latest or a client-selected revision. Missing durable run/revision,
wrong revision identity or unmatched pinned step/capability is an invariant failure,
not supported absence or a fallback. No case/evidence or provider lookup is needed.

Return exact run identity, supervision epoch, access observation, pinned step,
contract database recording instant and normalized v1alpha1 definition. Preserve
applicability equality values, required-evidence order, up to 50 declared steps
with their own risk/approval, and the outcome condition. Domain validation bounds
collections and values; HTTP DTOs are distinct from storage/domain representations.
Recording time need not precede authorization observation under clock correction.

Declarations are not assessed readiness, historical selection reasoning, effective
runtime policy, approval decisions, grants, executed steps, causal/DAG spans or
accepted proof. Runtime policy can strengthen the declared safeguards. The current
runtime pins one step; other declared steps are not automatically scheduled.
Return normalized meaning, never purported original YAML or raw persistence JSON.
Inspection does not revalidate reference registration or tenant capability
availability, and never certifies that declared actions can currently run.

Success and known failures are no-store. Inaccessible run context shares neutral
`404 assigned-resolution-run-not-found` before private lookup. Malformed UUIDs
use `400 invalid-run-pinned-contract`; storage/invariant failure uses cause-free
`503 run-pinned-contract-unavailable`. Existing safe identity problems remain
403 and disabled browser sessions return 503 browser-authentication-unavailable.

## Alternatives considered

### Reuse internal contract retrieval or expose a tenant contract catalog

- Benefits: less browser-specific mapping.
- Costs and risks: expands disclosure beyond the independently authorized run.
- Reason not selected: internal endpoints are not a browser authorization boundary.

### Infer a plan from receipt/admission history or return authoring YAML

- Benefits: reuses existing observations or resembles the future Studio.
- Costs and risks: confuses declarations with execution; original YAML is absent.
- Reason not selected: neither approach represents immutable contract meaning honestly.

### Add a planner, interpreter or separate contract projection store

- Benefits: broader future functionality.
- Costs and risks: new semantics, infrastructure and authority beyond this read.
- Reason not selected: existing immutable repositories satisfy the current need.

## Consequences

### Positive

Supervisors can understand the actual contract definition and distinguish its
declared instructions from recorded execution and runtime-effective safeguards.
No schema, module, dependency, queue, cache or new command authority is introduced.

### Negative

An additional protected read and protocol must be maintained. Definitions remain
untrusted display content and require strict client decoding. This is not a
durable record of why a historical planner selected a contract.

### Neutral or follow-up

Deliver backend first; dependent ergon-ui inspection and executable guide wait
for review/merge. Catalog/editing/publication, visual authoring, general graphs,
readiness reassessment, execution of additional steps and new evidence types
remain separate capabilities.

## Compatibility and migration

The GET is additive and uses existing immutable schema/codecs and indexes.
No migration, data rewrite, backfill or new flag is required. Deploy backend
before UI. Unknown missing routes must remain unsupported in that future UI,
not a fabricated empty definition or an internal fallback. Application rollback
removes the read only; existing run/contract records and command semantics remain.

## Security and operations

The tenant path is scope, not authority. Current supervisor/resolver gates precede
private lookup; a revision identity is never transferable permission. No case
content, private actor, authority evidence, machine subject, grant, provider
reference, payload, cause or credential is returned. Do not log definitions or
invoke/navigate their strings. One bounded immutable definition is read without
connector I/O or a long-lived transaction. Storage corruption is an operational
failure, not an invitation to retry execution. Production IdP/ingress/rate policy
and provider safety remain outside simulated verification.

## Validation

Application tests prove authority-first lookup, exact tenant/revision/step
attribution, strengthened-policy distinction, transaction cleanup and failure
propagation. Real PostgreSQL/HTTP tests prove bounded declared order and privacy,
non-latest/foreign revision exclusion, terminal access, handover and expiry,
neutral denial before private lookup, no command effects, no-store coordinate/
identity/storage failures and disabled-browser behavior. Run focused repository
policy and full Maven verification. No schema upgrade path changes; the existing
full reactor retains its migration gates. Browser presentation/guide follow
backend merge and must preserve these declaration-versus-execution distinctions.
