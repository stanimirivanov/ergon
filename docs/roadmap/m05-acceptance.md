# M05 bounded acceptance record

## TL;DR

Snapshot: 2026-10-29. Milestone: M05 - Adaptive Resolution Experience.

The [approved implementation sequence](m05-adaptive-resolution-experience.md)
is delivered: 28 stable coordinates, 42 merged PRs (22 Ergon, 20 ergon-ui).
The final backend and UI heads passed their owning CI gates. This establishes
bounded contract/browser implementation, not a live two-app deployment or
production-readiness claim. GitHub milestone state is not changed by this record.
Counts cover this approved sequence, not every historical M05 or Console PR.

## Acceptance traceability

Rows correspond to the plan's eight completion criteria. Backend tests exercise
real PostgreSQL/security boundaries where applicable; UI guides simulate the BFF.
Merged delivery links for every coordinate are in the plan's delivery table.

| Criterion | Delivered coordinates and representative evidence | Boundary retained |
|:--|:--|:--|
| Discover, claim, inspect, annotate, transfer and finish human work | PR6–PR12. [Owner notes tests](../../control-plane/src/test/kotlin/org/ergon/controlplane/followup/HumanFollowUpNotesIntegrationTest.kt), [atomic handover tests](../../control-plane/src/test/kotlin/org/ergon/controlplane/followup/HumanFollowUpHandoverIntegrationTest.kt), [completion/recovery tests](../../control-plane/src/test/kotlin/org/ergon/controlplane/followup/HumanFollowUpCompletionIntegrationTest.kt). UI #203/#205/#207/#209 include discovery, notes, handover and completion guides. | Fresh current ownership and revision checks; uncertain acknowledgement never grants authority. |
| Human-work completion is not verified case closure | PR11/PR12. [Terminal-work guards](../../control-plane/src/test/kotlin/org/ergon/controlplane/followup/HumanFollowUpCompletionGuardIntegrationTest.kt), separate [proof-finalization tests](../../control-plane/src/test/kotlin/org/ergon/controlplane/resolution/RunProofFinalizationIntegrationTest.kt). UI completion and proof-finalization guides keep the actions separate. | Only existing accepted-proof policy establishes verified closure; no administrative shortcut. |
| Complete responsive assigned Console over recorded facts | PR2–PR5, with existing supervision/control/execution contracts. [Run/policy contract](../runs.md), [browser control](../operations/browser-run-control.md), and UI's [27-state visual suite](https://github.com/stanimirivanov/ergon-ui/blob/9faee5546a1e09663db11bf72a2938fb51ffa3e8/apps/ergon-workbench-e2e/src/console.visual.spec.ts). | No invented multi-step spans, costs, check counts or inherited authority. |
| Separate requester identity, intake, progress, questions and answers | PR15–PR24. [Separate-session tests](../../control-plane/src/test/kotlin/org/ergon/controlplane/identity/RequesterBrowserSessionIntegrationTest.kt), [intake HTTP tests](../../control-plane/src/test/kotlin/org/ergon/controlplane/cases/RequesterCaseIntakeHttpIntegrationTest.kt), [progress tests](../../control-plane/src/test/kotlin/org/ergon/controlplane/cases/RequesterCaseProgressIntegrationTest.kt), [answer tests](../../control-plane/src/test/kotlin/org/ergon/controlplane/followup/RequesterInformationResponseIntegrationTest.kt). UI #213–#227 supply the bounded canvas and guides. | Explicit requester ownership; answers are untrusted input, not facts, consent or capability approval. |
| Revisioned priority, stable queue order and atomic handover | PR9/PR10/PR13/PR14. [Priority tests](../../control-plane/src/test/kotlin/org/ergon/controlplane/followup/HumanFollowUpPriorityIntegrationTest.kt) and handover concurrency tests above; UI #207/#211 cover confirmed intent/recovery. | No intermediate unowned handover window; priority is not queue order, SLA or risk. |
| Durable deduplicated notifications with independent target access | PR25/PR26. [Notification isolation/replay tests](../../control-plane/src/test/kotlin/org/ergon/controlplane/followup/RequesterInformationNotificationIntegrationTest.kt), [notification read contract](../decisions/0080-protect-bounded-notification-feeds.md); UI #229's resolver/requester chapters verify fresh session/target reads. | Historical pointers grant no current access; no private text, retroactive backfill or external delivery. |
| Local sign-out in both apps retires private browser state | PR27/PR28a/b. [Session retirement tests](../../control-plane/src/test/kotlin/org/ergon/controlplane/identity/adapter/inbound/security/BrowserLocalLogoutTest.kt), [ADR 0081](../decisions/0081-retire-local-browser-sessions.md); UI #231/#233 cover cache/request/CSRF withdrawal, late results, explicit retry and Back. | No global/provider revocation, accepted-work cancellation, implicit lease release or cross-tab erasure. |
| Negative, concurrency, tenant, keyboard, responsive and privacy verification with executable guides | Owning suites above plus the final CI evidence below. UI's [workbench sign-out chapter](https://github.com/stanimirivanov/ergon-ui/blob/9faee5546a1e09663db11bf72a2938fb51ffa3e8/apps/ergon-workbench-e2e/src/guide/workbench-sign-out-guide.ts) completes the 36-chapter simulated book. | Test/visual evidence covers its synthetic or controlled boundary; it is not deployment acceptance. |

## Verification provenance

These are inspected historical CI results, not tests rerun by this documentation
change. PR merge state and checks were read from GitHub on the snapshot date.

| Final delivery | Tested source head | Evidence |
|:--|:--|:--|
| [Ergon #228](https://github.com/stanimirivanov/ergon/pull/228), merged 2026-10-28 | `4b0c95dd150043c024f7c463e42a1a5f7c0534e3` | [Run 40733301698](https://github.com/stanimirivanov/ergon/actions/runs/40733301698): policy, formatting, static analysis, reactor stages and aggregate build succeeded. Reactor stages own compilation, unit/PostgreSQL tests and packaging. |
| [ergon-ui #233](https://github.com/stanimirivanov/ergon-ui/pull/233), merged 2026-10-29 | `9faee5546a1e09663db11bf72a2938fb51ffa3e8` | [Run 40821142982](https://github.com/stanimirivanov/ergon-ui/actions/runs/40821142982): workspace/browser verification and pinned visual comparison succeeded. [Run 40821142981](https://github.com/stanimirivanov/ergon-ui/actions/runs/40821142981): simulated guide generation succeeded. |

The UI PR's earlier failed visual/generation attempts are superseded by these
successful final-head checks. Publishing was skipped for the PR by design;
generation success does not establish a deployed Pages site. No new production
session, identity-provider, connector or deployment check was performed here.

## Unverified deployment acceptance

There is no delivered live two-app acceptance gate spanning separately deployed
origins, actual OIDC redirects/client sessions, secure cookie behavior, backend
PostgreSQL and real UI traffic together. Backend security/integration tests and
simulated browser guides prove different boundaries and do not substitute for it.
This is the already reported deployment gap, not a newly imposed M05 feature.

A future deployment acceptance change must name its environment and evidence:
requester/resolver session separation; intake through real protected progress;
owner-only question/answer and notification access after handover; CSRF-protected
logout with private-state withdrawal; and a fresh authorized visit after retirement.
Use disposable identities/data and explicit negative/foreign-tenant checks. No
infrastructure, credentials or successful run of that gate is claimed by this PR.

## Scope after delivery

Attachments/scanning/retention, requester approval/contractual consent, arbitrary
forms, automatic contract/run selection, queue administration, SLA/due dates and
email/push/webhook delivery remain excluded. Multi-step execution and new proof
types belong to M04; general compiled claims/contradictions to M06; simulation and
promotion to M07; evidence-generated improvement proposals to M08; integration
SDKs to M09. Concept images are not source evidence for those features.

M06 is the next feature milestone in the published ordering and still needs a
reviewed PR-sized implementation plan. This audit does not select source formats,
new claim semantics, compiler authorization or runtime behavior. No GitHub
milestone is closed/reassigned automatically and no new implementation coordinate
is appended to the completed M05 sequence.
