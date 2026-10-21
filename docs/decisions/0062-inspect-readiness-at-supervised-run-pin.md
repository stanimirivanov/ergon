# ADR 0062: Inspect readiness at the supervised run pin

- Status: Accepted
- Date: 2026-10-21
- Milestone: M02 - Evidence and contracts
- Deciders: Maintainer standing approval for supervisor authorization/disclosure; implementation review
- Supersedes:
- Superseded by:

## Context

The Console can inspect immutable contract declarations and the first hundred
pinned account-access bindings, but it cannot identify the latest fact that the
readiness evaluator selects at that fixed boundary. A truncated history cannot
answer that question. The existing readiness query evaluates the latest case and
is an internal development boundary, not a safe browser substitute.

## Decision

Add a protected read of the exact run's contract and case prefix through
`GET /bff/v1/tenants/{tenantId}/resolution-runs/{runId}/pinned-readiness`.
Reuse current tenant-wide RESOLVER and exact supervisor gating before all private
evidence lookup, including terminal attempts. No originating actor, control lease,
CSRF setup or enabled execution command is required.

Label the result as a deterministic **re-evaluation**, using explicit evaluator
identity `ergon.dev/evaluation/pinned-readiness/v1`. It is not a stored historical
planner decision, an explanation of selection, current-case readiness, execution
eligibility, reference registration, connector availability or accepted proof.

The existing evaluator retains missing-evidence precedence, declaration-order
missing names and exact case-sensitive equality. The current supported fact is
`account.access.state`; unsupported required facts remain missing, never silently
satisfied. Return only the selected required supported binding and its exact
citation. Applicability values remain absent until all requirements exist.

Reuse the case-owned evidence adapter's attribution mapping and tenant/boundary
query. A second consumed port requests at most one binding by descending stream
version. This is independent of the existing ascending first-100 history. SQL
order interpolates only private ASC/DESC literals; all external data is bound.
One statement bounds source material and excludes unbound/later observations.
No new table, index, migration, dependency, module or model/provider is introduced.

A short required application transaction joins immutable pin reads and this
bounded projection. Authorization is observation-time, not continuous or an atomic
snapshot across mutable authority queries. Storage, missing boundaries and corrupt
attribution fail closed as no-store cause-free 503, never missing evidence.
Unauthorized access is the existing neutral 404 before case reads. Malformed
UUIDs use a dedicated no-store 400; identity/disabled-session failures remain safe.

## Alternatives considered

### Use the latest-case readiness endpoint

- Benefits: Existing implementation.
- Costs and risks: Wrong evidence boundary and an unreviewed internal API.
- Reason not selected: Later changes must not rewrite the meaning of a pinned run.

### Derive selection from the first hundred history rows

- Benefits: No new query.
- Costs and risks: Truncation can exclude the actual selected fact.
- Reason not selected: It can report the wrong value and assessment.

### Present a historical planner explanation

- Benefits: Closer to the future full decision-inspection experience.
- Costs and risks: That historical decision payload was not persisted.
- Reason not selected: Re-evaluation must not invent durable reasoning.

## Consequences

### Positive

The Console can relate contract requirements to one attributable selected fact,
including beyond bounded history, without conflating declarations with actions.

### Negative

The result covers only registered evaluator support, currently account access;
it is not a general evidence compiler or contradiction model. Existing indexes
scope the tenant/case, but ordering by stream version can scan and sort that
case's bindings before LIMIT. The returned source payload is bounded; database
work and latency are not claimed to be constant. A measured large-case workload
may justify a separate stream-order index; no performance SLA is claimed.

### Neutral or follow-up

Backend review and merge precede the dependent complete UI and executable guide.
Other evidence types require reviewed semantics and a new evaluator identity when
incompatible. No production behavior is learned or changed from this read.

## Compatibility and migration

Additive route; existing Console and pinned-contract schemas remain unchanged.
The internal pin result gains consumed case coordinates but its existing HTTP
projection stays explicit. No schema or flag changes; deploy backend before UI.
Missing routes must remain unsupported in the future client, not fabricated empty
assessments. Rollback removes presentation/read access without changing stored data.

## Security and operations

Use only exact tenant/run pins after existing current supervisor/resolver access.
Disclose at most one selected binding/source and fifty missing names. Untrusted
source text is inert data; no raw causes, identities of actors/authority evidence,
provider-operation references or unrelated observations are added. No remote I/O,
write, lock acquisition for commands, automatic renewal or execution follows.

## Validation

Application tests cover missing precedence, exact equality, unsupported requirements,
private-read gating, boundary consistency and failures. Real PostgreSQL/HTTP tests
cover the post-100 selection, later/unbound exclusion, tenancy, handover/terminal
access, safe problems and source mapping. Existing history tests protect the
unchanged first-100 semantics. Full Maven verification and repository policy remain
required; simulated sessions do not certify production identity or provider safety.
