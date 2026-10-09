# Ergon implementation milestones

## TL;DR

- Milestones are outcome-oriented and titled `MNN - Outcome`.
- Each work item should fit one independently reviewable pull request.
- M01 through M03 establish the case, evidence, contract, identity, and policy
  foundations; their delivered work is already represented in repository
  history.
- M04 completes guarded execution, outcome verification, retry, and escalation.
- M05 is building durable human follow-up and an assigned-supervisor Resolver
  Console; the target Console also needs work in M03, M04, and M06.
- GitHub owns live issue state; this file owns intended sequence and boundaries.
- M08 is the published high-water mark; published milestone numbers are never
  removed, reused, or renumbered.

## Milestone index

| Milestone title | Short message |
|:--|:--|
| M01 - Resolution foundation | Establish the product boundary, case kernel, and reviewable engineering baseline. |
| M02 - Evidence and contracts | Turn source observations into typed evidence governed by immutable contracts. |
| M03 - Human authority and policy | Prove who may approve or authorize each capability under versioned policy. |
| M04 - Guarded Execution and Verification | Execute once, verify outcomes independently, and terminate bounded recovery explicitly. |
| M05 - Human follow-up and resolver console | Make escalation actionable without reconstructing the case from transcripts. |
| M06 - Evidence compiler | Compile attributable knowledge and schemas into reviewable evidence. |
| M07 - Simulation and improvement | Replay decisions and propose reviewed improvements from measured outcomes. |
| M08 - Ecosystem and production readiness | Integrate channels and connectors and operate Ergon securely at scale. |

## M01 - Resolution foundation

**Message:** Establish the product boundary, case kernel, and reviewable
engineering baseline.

Delivered outcomes include the Ergon product and architecture boundary,
contributor standards, append-only case identity and observations, optimistic
concurrency, and an ordered timeline projection.

Remaining foundation work must be independently valuable; do not attach new
runtime behavior to M01 merely because it is foundational.

## M02 - Evidence and contracts

**Message:** Turn source observations into typed evidence governed by immutable
contracts.

Delivered outcomes include account-access facts bound to source observations,
strict contract document validation, tenant-scoped immutable revisions,
semantic-reference validation, case pinning, readiness evaluation, and the
first versioned policy plan.

Future evidence types and contract language revisions belong here only when
they do not require compiler or simulation behavior from later milestones.

## M03 - Human authority and policy

**Message:** Prove who may approve or authorize each capability under versioned
policy.

Delivered outcomes include immutable run starts, expiring approval requests,
scoped authority evidence, JWT-to-tenant actor resolution, attributable approval
decisions, bounded grants, and one-time consumption through tenant capability
routes.

Broader identity providers, role administration, and production route
provisioning remain separate security capabilities.

## M04 - Guarded Execution and Verification

**Message:** Execute once, verify outcomes independently, and terminate bounded
recovery explicitly.

Delivered outcomes include idempotent connector invocation and receipts,
receipt-backed run transitions, post-action proof assessment, atomic proof
acceptance and case closure, linked fresh-authority retries, a versioned retry
ceiling, and explicit resolver escalation after exhaustion.

Completion means the deterministic access-restoration slice demonstrates both
verified success and an auditable exhausted failure without confusing action
completion with outcome proof.

## M05 - Human follow-up and resolver console

**Message:** Make escalation actionable without reconstructing the case from
transcripts.

Candidate PR-sized outcomes:

- Delivered: create and retrieve one durable human follow-up work item from an
  escalated run.
- Delivered: expose a bounded oldest-first inbox for open human follow-up work.
- Delivered: let one authorized resolver claim open work with immutable ownership.
- Delivered: expose a bounded active-work view for the authenticated owner.
- Delivered: route follow-up creation to an immutable named queue and support
  exact queue filtering in the shared inbox.
- Delivered: establish a confidential OIDC BFF session boundary without
  exposing provider tokens to browser code.
- Delivered: expose the shared resolver inbox through a read-only browser
  session contract without promoting internal bearer routes.
- Delivered: protect browser commands with session CSRF tokens and expose
  idempotent resolver claiming through the confidential BFF.
- Delivered: expose the authenticated resolver's active claimed work through a
  browser-specific, token-free session contract.
- Delivered: expose an owner-scoped case summary from existing observations,
  pinned contract identity, and immutable resolution-run facts.
- Delivered: expose the failed connector outcome and exhausted-retry decision
  that caused an owned escalation, without browser-visible private attribution.
- Delivered: accept a revision-checked first-claim command with a durable
  exact-replay receipt on the internal resolver boundary.
- Delivered: expose revision-checked first claiming through a browser-specific
  CSRF-protected contract without private resolver attribution.
- Delivered: release current ownership and reacquire work through revisioned
  internal commands while preserving immutable claim history.
- Delivered: expose current ownership revisions and a CSRF-protected browser
  release/later-claim cycle without private attribution.
- Define priority, reassignment, and completion semantics separately.
- Add idempotent notification delivery after durable work exists.
- Establish explicit run-supervisor assignment and assigned-only browser reads
  before exposing active runs in the Console.
- Deliver a responsive, accessible active-run Console from recorded facts,
  without presenting assignment as a lease or inventing execution spans.
- Implement the adaptive requester canvas over the same case API.

Completion means a resolver can accept and continue an escalated case from
structured evidence, and an explicitly assigned supervisor can inspect an
active run without relying on transcript reconstruction or tenant-wide run
disclosure.

### Planned Resolver Console issue sequence

These are proposed issue titles, not claims of delivered behavior. Each task
is one coherent, reviewable capability; UI-bearing changes include responsive
layout, accessibility, state binding, focused tests, and an updated executable
user-guide scenario. Backend-first contracts and browser composition may need
separate pull requests because they live in different repositories.

1. **M05 - Human follow-up and resolver console:** Assign resolution-run
   supervisors and expose assigned-only active-run browser reads. Use a
   dedicated machine-authenticated assignment command; do not allow browser
   self-assignment. See [ADR 0045](../decisions/0045-assign-resolution-run-supervisors.md).
2. **M05 - Human follow-up and resolver console:** Deliver the active-run
   Resolver Console for assigned supervisors. Compose the entire responsive
   three-pane read experience, including loading, revalidation, absence, and
   terminal states, from the reviewed BFF contract.
3. **M04 - Guarded Execution and Verification:** Record and project ordered
   resolution-step execution with attributable measurements. The current run
   pins one step; a six-stage waterfall, spans, latency, and cost must be
   recorded before the UI can show them as facts.
4. **M05 - Human follow-up and resolver console:** Add exclusive supervisor
   control, conflict visibility, and explicit handover. Assignment alone is
   not an expiring lease or permission to steer a run.
5. **M03 - Human authority and policy:** Review and decide pending capability
   authorization from the Resolver Console. Bind browser actions to current
   approval and deterministic gate contracts, not the recorded policy label.
6. **M06 - Evidence compiler:** Review attributable claims and contradictions
   from the Resolver Console. Compile typed claims, validity, citations, and
   conflict meaning rather than relabeling raw observations as verified facts.
7. **M04 - Guarded Execution and Verification:** Track verification checks and
   accepted outcome proof in the Resolver Console. Display real check state
   and proof provenance, never a synthetic completion count.
8. **M04 - Guarded Execution and Verification:** Steer supervised runs through
   deterministic, auditable commands. Define bounded command and authority
   semantics before enabling the mock's Steer control.

The concept images are a visual direction, not source data. The first two
issues do not promise lease countdowns, live spans, cost totals, verified
claims, contradictions, approval commands, or a multi-check proof result.

## M06 - Evidence compiler

**Message:** Compile attributable knowledge and schemas into reviewable evidence.

Candidate outcomes include one document format, one API-schema format, preserved
source spans, typed claims and procedures, protocol/semantic separation,
contradiction detection, evidence bundles, and review workflows. Model output
remains an untrusted proposal and cannot grant authority.

Completion means one real source can produce attributable evidence that a
contract can consume and a human can review.

## M07 - Simulation and improvement

**Message:** Replay decisions and propose reviewed improvements from measured
outcomes.

Candidate outcomes include pinned run fixtures, deterministic connector/model
adapters, safety and proof assertions, cost/latency budgets, contract-promotion
gates, and evidence-backed documentation, contract, and regression proposals.

Completion means a contract revision is promoted only after reproducible
evaluation, and production behavior never changes through silent learning.

## M08 - Ecosystem and production readiness

**Message:** Integrate channels and connectors and operate Ergon securely at
scale.

Delivered outcomes include removal of the predecessor API gateway, its
standalone deployment assets, and the now-unused Spring Cloud edge. This does
not establish an Ergon production ingress or authorization boundary. The
incomplete predecessor Q&A coordinator and its exclusive MongoDB and Redis
development dependencies were also retired without adopting its answer model
as an Ergon contract. The now-unconsumed hybrid retrieval service was retired
without adopting its article/chunk search contract; its superseded ranking ADR
remains historical input for M06 evidence retrieval. The resulting unconsumed
embedding worker, Spring AI dependency, and Ollama development service were
also retired without prematurely adopting article-specific chunks or vectors.
Finally, the article ingestion service and the Kafka development broker used
only by its outbox publisher were retired. All predecessor executable modules
are now gone; their article APIs, storage model, and event contracts were not
adopted as Ergon compatibility boundaries.
The active Maven reactor and future container images now use Ergon coordinates
under [ADR 0032](../decisions/0032-adopt-ergon-build-coordinates.md), without
combining that compatibility change with repository or database identity
migrations.
Local PostgreSQL now uses a separate Ergon Compose project, database, role, and
explicit volume under
[ADR 0033](../decisions/0033-separate-ergon-local-postgresql-identity.md),
preserving predecessor volumes rather than deleting or mutating them.

Candidate outcomes include guarded MCP and help-desk integrations, connector and
widget SDKs, production authentication/authorization, observability, durable
workers, rate and cost bounds, retention/deletion, backup/restore, staged
rollout, SLOs, threat modeling, and licensing/dependency review.

Completion means Ergon can be deployed and recovered with explicit security,
reliability, cost, ownership, and compatibility controls.

## Planning rules

- The published milestone high-water mark is `M08`. It may advance but MUST
  NOT decrease, including when the newest milestone is completed, deprecated,
  or superseded. A future milestone therefore starts at M09.
- The repository-policy checker retains the high-water value independently of
  this document and validates sequential index entries, matching detail
  sections, and identical non-empty messages.
- GitHub owns live issue state, labels, assignees, and milestone assignment.
- This file owns intended sequencing until an issue is created.
- Every issue names exactly one milestone using its exact title.
- Move an issue only when its outcome dependency changes; update this roadmap
  when that changes the intended sequence.
- Split a work item that reveals multiple independently valuable or risky
  changes before implementation.
- A milestone may complete with deferred work only when its message remains
  true and the deferral is recorded.
