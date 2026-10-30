# Ergon implementation milestones

## TL;DR

- Milestones are outcome-oriented and titled `MNN - Outcome`.
- Each work item should fit one independently reviewable pull request.
- M01–M04 are delivered for the bounded deterministic access-restoration slice;
  this is not general execution or production readiness.
- M05's approved bounded resolver/requester implementation is delivered through
  42 merged PRs; its [acceptance record](m05-acceptance.md) distinguishes CI-backed
  delivery from the unverified live two-app deployment gate.
- GitHub owns live issue state; this file owns intended sequence and boundaries.
- M09 is the published high-water mark; published milestone numbers are never
  removed, reused, or renumbered.

## Milestone index

| Milestone title | Short message |
|:--|:--|
| M01 - Case and Evidence Foundation | Establish Ergon's architecture and a tenant-safe, evidence-backed case timeline. |
| M02 - Resolution Contracts and Planning | Publish immutable resolution contracts, pin them to cases, and determine whether execution is ready. |
| M03 - Policy and Human Authority | Ensure that only an authenticated human with current, correctly scoped authority can approve an action. |
| M04 - Safe Execution and Outcome Proof | Execute the access-restoration contract idempotently and close the case only after independent proof. |
| M05 - Adaptive Resolution Experience | Deliver the requester canvas and resolver console over the shared case APIs. |
| M06 - Evidence Compiler | Compile documents and API descriptions into attributable facts, procedures, and contradictions. |
| M07 - Simulation and Promotion | Replay resolution contracts against deterministic scenarios and gate their promotion. |
| M08 - Evidence-Backed Improvement Loop | Turn completed and failed cases into reviewable documentation, contract, and test proposals. |
| M09 - Integration Ecosystem | Publish connector and widget SDKs and integrate Ergon with external systems of engagement. |

## M01 - Case and Evidence Foundation

**Message:** Establish Ergon's architecture and a tenant-safe, evidence-backed case timeline.

Delivered outcomes include the Ergon product and architecture boundary,
contributor standards, append-only case identity and observations, optimistic
concurrency, and an ordered timeline projection.

Remaining foundation work must be independently valuable; do not attach new
runtime behavior to M01 merely because it is foundational.

## M02 - Resolution Contracts and Planning

**Message:** Publish immutable resolution contracts, pin them to cases, and determine whether execution is ready.

Delivered outcomes include account-access facts bound to source observations,
strict contract document validation, tenant-scoped immutable revisions,
semantic-reference validation, case pinning, readiness evaluation, and the
first versioned policy plan.

Future evidence types and contract language revisions belong here only when
they do not require compiler or simulation behavior from later milestones.

Current supervisors can now inspect their run's exact normalized immutable
contract without exposing a tenant catalog or internal publication API, under
[ADR 0061](../decisions/0061-disclose-supervisor-scoped-pinned-contract.md).
Declarations remain separate from assessed readiness, effective safeguards and
recorded execution. The dependent Console UI and guide are merged.
The bounded readiness inspection re-evaluates the exact pinned prefix with the current
version-labelled readiness evaluator and only its selected citation, under
[ADR 0062](../decisions/0062-inspect-readiness-at-supervised-run-pin.md).
It is not historical planner reasoning or current-case/connector readiness;
its dependent UI and guide are merged.

## M03 - Policy and Human Authority

**Message:** Ensure that only an authenticated human with current, correctly scoped authority can approve an action.

Delivered outcomes include immutable run starts, expiring approval requests,
scoped authority evidence, JWT-to-tenant actor resolution, attributable approval
decisions, bounded grants, and one-time consumption through tenant capability
routes.

Broader identity providers, role administration, and production route
provisioning remain separate security capabilities.

M05's M03 prerequisites now deliver a separate requester browser client and
CSRF-protected local logout, including requester/workbench private-state retirement.
They do not add global identity-provider revocation or a new authority algebra;
see [M05 acceptance](m05-acceptance.md).

## M04 - Safe Execution and Outcome Proof

**Message:** Execute the access-restoration contract idempotently and close the case only after independent proof.

Delivered outcomes include idempotent connector invocation and receipts,
receipt-backed run transitions, post-action proof assessment, atomic proof
acceptance and case closure, linked fresh-authority retries, a versioned retry
ceiling, and explicit resolver escalation after exhaustion.

Completion means the deterministic access-restoration slice demonstrates both
verified success and an auditable exhausted failure without confusing action
completion with outcome proof.

## M05 - Adaptive Resolution Experience

**Status:** Approved bounded implementation delivered as of 2026-10-29; live
two-app deployment acceptance is unverified. See the
[acceptance evidence and remaining limits](m05-acceptance.md).

**Message:** Deliver the requester canvas and resolver console over the shared case APIs.

Delivered outcomes and retained boundaries:

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
- Delivered: assigned-only run reads, explicit supervisor handover and fenced
  session control, distinct from follow-up ownership and capability authority.
- Delivered: responsive Consoles over recorded facts, with executable guides;
  visual refinement continues without inventing execution spans.
- Delivered: owner-attributed notes, direct handover, administrative work completion,
  revisioned priority and durable in-app notifications.
- Delivered: separately authenticated requester intake, progress and bounded
  information requests/responses, plus explicit sign-out in both applications.

Completion means the resolver and requester acceptance criteria in the
[approved M05 plan](m05-adaptive-resolution-experience.md#completion-criteria)
are demonstrated. Human-work completion does not establish verified case
resolution; requester answers do not grant capability authority. Attachments,
requester approvals, general graphs and simulation are explicit exclusions.

### Resolver Console capability lineage

The bounded capabilities below are merged, except where a broader outcome is
explicitly deferred. This is historical context, not the next issue sequence;
use the approved M05 plan's delivery ledger and acceptance record. Backend and UI
changes are reviewed in separate repositories, backend first, with an executable
guide in each UI slice.

1. **M05 - Adaptive Resolution Experience:** Assign resolution-run
   supervisors and expose assigned-only active-run browser reads. Use a
   dedicated machine-authenticated assignment command; do not allow browser
   self-assignment. See [ADR 0045](../decisions/0045-assign-resolution-run-supervisors.md).
2. **M05 - Adaptive Resolution Experience:** Deliver the active-run
   Resolver Console for assigned supervisors. Compose the entire responsive
   three-pane read experience, including loading, revalidation, absence, and
   terminal states, from the reviewed BFF contract.
3. **M04 - Safe Execution and Outcome Proof:** Record and project ordered
   resolution-step execution with attributable measurements. The current run
   pins one step; a six-stage waterfall, spans, latency, and cost must be
   recorded before the UI can show them as facts. The approved first backend
   slice records the existing gateway call's actual timing and exposes it
   through assigned-only reads under
   [ADR 0046](../decisions/0046-record-runtime-capability-invocation-timing.md).
   Measured execution is now consumed by the Console and executable guide.
   This slice exposes the existing immutable transition prefix, aligned
   with the assigned snapshot revision under
   [ADR 0047](../decisions/0047-expose-sequence-aligned-assigned-run-transitions.md).
   A multi-step executor and real cost attribution remain separate runtime
   capabilities; this receipt measurement does not deliver that whole waterfall.
   A bounded read-only admission-history projection now exposes existing initial
   and recovery admissions under current supervision/resolver authority, across
   handovers and terminal runs. These are not causal spans or per-call results;
   see [ADR 0058](../decisions/0058-disclose-supervisor-scoped-execution-admissions.md).
   Its dependent UI and guide are merged. The bounded relationship read
   recovers an independently visible direct retry successor, including terminal
   work, under [ADR 0059](../decisions/0059-recover-independently-visible-retry-successors.md).
   It grants no inherited access, command replay or execution authority. Its
   dependent navigation UI and guide are merged. Gateway-attempt history records and
   projects actual gateway preparations and measured returns/exceptions under
   [ADR 0060](../decisions/0060-record-observed-gateway-attempt-history.md).
   Preparation is not proof of dispatch; absent completion is not live status.
   Separate per-call samples do not replace the winning receipt or establish a
   multi-step waterfall. Its UI and guide are merged.
4. **M05 - Adaptive Resolution Experience:** Add exclusive supervisor
   control, conflict visibility, and explicit handover. Assignment alone is
   not an expiring lease or permission to steer a run.
   Confirmed browser handover now uses immutable supervision epochs and a
   current access projection under
   [ADR 0048](../decisions/0048-allow-revisioned-browser-supervisor-handover.md).
   Browser control leases now have a separate default-off backend under
   [ADR 0057](../decisions/0057-fence-browser-run-control.md); no lease countdown
   is inferred from a supervision transfer.
5. **M03 - Policy and Human Authority:** Review and decide pending capability
   prerequisites from the Resolver Console. Bind browser actions to current
   approval and deterministic gate contracts, not the recorded policy label.
   Supervisor-scoped review and confirmed decisions now use exact authority,
   supervision epochs and durable command replay; they never grant or execute.
   See [ADR 0049](../decisions/0049-confirm-supervisor-scoped-approval-decisions.md).
6. **M06 - Evidence Compiler:** Review attributable claims and contradictions
   from the Resolver Console. Compile typed claims, validity, citations, and
   conflict meaning rather than relabeling raw observations as verified facts.
   The first delivered boundary exposes existing pinned account-access bindings
   and only their exact linked sources under current supervisor/resolver access;
   see [ADR 0050](../decisions/0050-disclose-supervisor-scoped-pinned-evidence.md).
   General claims, confidence, validity and contradiction meaning remain deferred.
7. **M04 - Safe Execution and Outcome Proof:** Track verification checks and
   accepted outcome proof in the Resolver Console. Display real check state
   and proof provenance, never a synthetic completion count.
   Delivered for the existing single account-access condition: current assessment
   is distinct from immutable acceptance, with narrowly selected post-action
   provenance under [ADR 0051](../decisions/0051-disclose-supervisor-scoped-outcome-verification.md).
    Confirmed browser finalization binds exact reviewed proof to current supervision
    and resolver authority, with atomic closure and historical replay under
    [ADR 0052](../decisions/0052-finalize-reviewed-proof-through-browser.md).
   Multi-check verification and new proof types remain separate capabilities.
8. **M04 - Safe Execution and Outcome Proof:** Steer supervised runs through
   deterministic, auditable commands. Define bounded command and authority
   semantics before enabling the mock's Steer control.
   Backend admission/execution and explicit recovery of the existing approved
   pinned step now follow [ADR 0053](../decisions/0053-confirm-supervisor-capability-execution.md).
   Browser execution/recovery and its executable guide are merged.
   The retry extension confirms an eligible failed run's fresh successor
   and atomically assigns its supervision to the confirming current supervisor
   under [ADR 0054](../decisions/0054-confirm-supervisor-retry-and-successor-supervision.md).
   Its dependent UI and guide are merged. The maintainer-approved exhausted-run
   escalation boundary now confirms exact reviewed runtime exhaustion and
   atomically opens unassigned human work under
   [ADR 0055](../decisions/0055-confirm-supervisor-exhausted-run-escalation.md).
   Its dependent UI and executable guide are merged. The approved bounded
   command cancels only untouched runs before any authorization, under
   [ADR 0056](../decisions/0056-cancel-untouched-supervised-runs.md).
   Backend cancellation is independently default-off; its dependent UI and guide
   are merged. General pause/branch, compensation and
   cancellation of already authorized or in-flight work are not implemented.
9. **M02 - Resolution Contracts and Planning:** Inspect the exact supervised run's immutable
   normalized contract declarations under
   [ADR 0061](../decisions/0061-disclose-supervisor-scoped-pinned-contract.md).
   No catalog, authoring, new readiness assessment or multi-step executor is
   introduced. UI and executable guide are merged.
10. **M05 - Adaptive Resolution Experience:** Exclusive authenticated-session
    control now fences all seven browser command families with 120-second leases,
    immutable admission audit and exact historical recovery. Backend protection
    defaults off. UI and executable guide are merged before operational
    rollout; there is no forced takeover, autonomous renewal or runtime cancellation.
11. **M02 - Resolution Contracts and Planning:** Inspect deterministic readiness at the exact
    supervised run pin with selected stream-order provenance, independently of
    the first-100 history window, under
    [ADR 0062](../decisions/0062-inspect-readiness-at-supervised-run-pin.md).
    No current-case planning, stored historical reasoning, new readiness meaning,
    command or outcome proof is inferred. UI and guide are merged.

The concept images are a visual direction, not source data. Their general
multi-step execution, cost totals, compiled contradictions and multi-check proof
remain outside M05; existing leases and confirmed commands are delivered.

## M06 - Evidence Compiler

**Message:** Compile documents and API descriptions into attributable facts, procedures, and contradictions.

Candidate outcomes include one document format, one API-schema format, preserved
source spans, typed claims and procedures, protocol/semantic separation,
contradiction detection, evidence bundles, and review workflows. Model output
remains an untrusted proposal and cannot grant authority.

Completion means one real source can produce attributable evidence that a
contract can consume and a human can review.

Bounded implementation and PR14 evidence are mapped in the
[M06 acceptance record](m06-acceptance.md). The implemented source-to-contract
path preserves documentary/operational/authority boundaries. Local PostgreSQL
acceptance and live two-app/OIDC deployment remain unverified; implementation
coverage must not be read as a passing runtime or promotion gate.

## M07 - Simulation and Promotion

M07-PR1 supplies the [bounded fixture identity foundation](../simulation.md).
M07-PR2 adds bounded internal package registration and retrieval. M07-PR3 replays
pinned readiness and first-step policy through the existing planner. M07-PR4 adds
bounded approval, capability invocation and proof replay with isolated scripted
adapters. M07-PR5 checks pinned scripted model suggestions as untrusted inputs
against runtime planning and evidence. M07-PR6 adds typed domain assertions over
explicit observations, including missing/unsupported handling. Execution/report
orchestration and promotion remain later slices.

**Message:** Replay resolution contracts against deterministic scenarios and gate their promotion.

Candidate outcomes include pinned run fixtures, deterministic connector/model
adapters, safety and proof assertions, cost/latency budgets, contract-promotion
gates, and reproducible evaluation reports. Improvement proposals belong to M08.

Completion means a contract revision is promoted only after reproducible
evaluation, and production behavior never changes through silent learning.

## M08 - Evidence-Backed Improvement Loop

**Message:** Turn completed and failed cases into reviewable documentation, contract, and test proposals.

Candidate outcomes include evidence-linked diagnostic, documentation, contract
and regression proposals with explicit review, compatibility and promotion
boundaries. No proposal generator is delivered. Closed GitHub M08 issues for
predecessor retirement and build/database naming are enabling history, not
implementation of reviewed learning; they are not reassigned by this document.

Completion means measured case outcomes produce attributable, reviewable changes
without silently altering production behavior. Simulation gates remain in M07.

## M09 - Integration Ecosystem

**Message:** Publish connector and widget SDKs and integrate Ergon with external systems of engagement.

Delivered outcomes as ecosystem-enabling history (not completed integrations)
include removal of the predecessor API gateway, its
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

Completion means supported connector/widget SDKs and external integrations have
explicit security, reliability, cost, ownership and compatibility controls.
Production authentication, operations and recovery remain delivery prerequisites,
not capabilities established by predecessor cleanup.

## Planning rules

- The published milestone high-water mark is `M09`. It may advance but MUST
  NOT decrease, including when the newest milestone is completed, deprecated,
  or superseded. A future milestone therefore starts at M10.
- The repository-policy checker retains the high-water value independently of
  this document and validates sequential index entries, matching detail
  sections, and identical non-empty messages.
- GitHub owns live issue state, labels, assignees, milestone assignment and formal
  closure. The index/messages match the published catalog in both repositories;
  the checker remains offline and does not query GitHub.
- M01–M04 delivery assessments apply to the bounded first slice, not every future
  extension. An empty issue list or a completed prerequisite does not complete a
  milestone. M06–M09 still have undelivered outcomes; M05's bounded implementation
  delivery does not close its reported deployment-acceptance gap.
- This file owns intended sequencing until an issue is created.
- Every issue names exactly one milestone using its exact title.
- Move an issue only when its outcome dependency changes; update this roadmap
  when that changes the intended sequence.
- Split a work item that reveals multiple independently valuable or risky
  changes before implementation.
- A milestone may complete with deferred work only when its message remains
  true and the deferral is recorded.
