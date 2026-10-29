# M05 adaptive resolution delivery plan

- Status: Bounded implementation sequence delivered; acceptance snapshot 2026-10-29.

## TL;DR

**Milestone:** M05 - Adaptive Resolution Experience.

Finish the resolver experience and deliver a separately authenticated requester
canvas over the existing case/runtime boundaries. All 28 plan coordinates below
are delivered through 42 merged PRs: 22 Ergon and 20 ergon-ui, including PR28's
requester/workbench split. The [acceptance record](m05-acceptance.md) maps criteria
to tests, guides and final CI evidence. Live two-app deployment acceptance remains
unverified; bounded implementation completion is not production readiness.
GitHub owns live issue/milestone state. No new runtime protocol is authorized.

## Completion criteria

- A resolver can discover, claim and inspect escalated work from structured
  evidence, record internal notes, transfer ownership and finish a human-work
  assignment with attributable, revision-checked recovery.
- Administrative work completion never implies `VERIFIED_RESOLVED`; only the
  existing accepted-proof boundary establishes verified case closure.
- Assigned supervisors can use the complete responsive Console over recorded
  facts, existing approval/execution controls and independently accepted proof.
  No six-stage execution, cost or check count is synthesized from concept art.
- A separate requester deployment and identity client support authenticated
  intake, narrowly scoped case progress and explicit information requests/responses.
  Requester answers remain untrusted observations, not facts, consent or approval.
- Priority is revisioned without changing oldest-first queue/cursor semantics.
  Direct handover never creates an intermediate unowned window.
- Notifications are durable, deduplicated in-app deliveries. Opening one requires
  fresh target access; no notification grants authority or restores private data.
- Both browser applications support explicit local sign-out and retirement of
  private DOM, cache subscriptions, requests and ephemeral CSRF state.
- Relevant negative, concurrency, tenant, keyboard, responsive and privacy paths
  pass their owning gates. UI slices include reviewed executable guide updates.

## Delivery sequence

IDs are stable plan coordinates, not GitHub issue numbers. The merged PR column
records delivery as of 2026-10-29; split coordinates retain their original IDs.
Dependencies/groups preserve the agreed merge ordering, not unfinished work.
Each UI delivery includes its executable guide. Unrelated harness PRs, including
Ergon #208, are outside the 42-delivery count.

| ID | Repository | Outcome | Depends on | Group | Merged PRs |
|:--|:--|:--|:--|:--|:--|
| M05-PR1 | Ergon | Reconcile M05 acceptance and published milestone ownership | None | G0 | [#184](https://github.com/stanimirivanov/ergon/pull/184) |
| M05-PR2 | ergon-ui | Approved visual baselines and Console chrome | PR1 | G1 | [#195](https://github.com/stanimirivanov/ergon-ui/pull/195) |
| M05-PR3 | ergon-ui | Recorded-execution workspace | PR2 | G2 | [#197](https://github.com/stanimirivanov/ergon-ui/pull/197) |
| M05-PR4 | ergon-ui | Authorization and recovery presentation | PR3 | G3 | [#199](https://github.com/stanimirivanov/ergon-ui/pull/199) |
| M05-PR5 | ergon-ui | Evidence and outcome-proof presentation | PR2 | G2 | [#201](https://github.com/stanimirivanov/ergon-ui/pull/201) |
| M05-PR6 | ergon-ui | Shared and owned follow-up redesign | PR2 | G2 | [#203](https://github.com/stanimirivanov/ergon-ui/pull/203) |
| M05-PR7 | Ergon | Owner-attributed internal notes | PR1 | G1 | [#186](https://github.com/stanimirivanov/ergon/pull/186) |
| M05-PR8 | ergon-ui | Structured notes in owned work | PR6, PR7 | G3 | [#205](https://github.com/stanimirivanov/ergon-ui/pull/205) |
| M05-PR9 | Ergon | Atomic current-owner follow-up handover | PR7 | G2 | [#188](https://github.com/stanimirivanov/ergon/pull/188) |
| M05-PR10 | ergon-ui | Confirmed handover and uncertain recovery | PR6, PR9 | G3 | [#207](https://github.com/stanimirivanov/ergon-ui/pull/207) |
| M05-PR11 | Ergon | Administrative human-work completion | PR7, PR9 | G3 | [#190](https://github.com/stanimirivanov/ergon/pull/190), [#192](https://github.com/stanimirivanov/ergon/pull/192) |
| M05-PR12 | ergon-ui | Confirmed work completion and recovery | PR8, PR10, PR11 | G4 | [#209](https://github.com/stanimirivanov/ergon-ui/pull/209) |
| M05-PR13 | Ergon | Revisioned priority, unchanged queue order (PR13a/b/c) | PR9, PR11a | G4–G6 | [#194](https://github.com/stanimirivanov/ergon/pull/194), [#196](https://github.com/stanimirivanov/ergon/pull/196), [#198](https://github.com/stanimirivanov/ergon/pull/198) |
| M05-PR14 | ergon-ui | Priority display and owner changes | PR6, PR13 | G7 | [#211](https://github.com/stanimirivanov/ergon-ui/pull/211) |
| M05-PR15 | Ergon | Separate requester browser boundary (M03 prerequisite) | PR1 | G1 | [#200](https://github.com/stanimirivanov/ergon/pull/200) |
| M05-PR16 | Ergon | Requester-scoped case progress | PR15 | G2 | [#202](https://github.com/stanimirivanov/ergon/pull/202) |
| M05-PR17 | ergon-ui | First usable authenticated requester app | PR2, PR16 | G3 | [#213](https://github.com/stanimirivanov/ergon-ui/pull/213) |
| M05-PR18 | ergon-ui | Complete bounded requester canvas | PR17 | G4 | [#215](https://github.com/stanimirivanov/ergon-ui/pull/215) |
| M05-PR19 | Ergon | Attributable requester-information requests (PR19a/b/c) | PR7, PR16 | G3–G5 | [#204](https://github.com/stanimirivanov/ergon/pull/204), [#206](https://github.com/stanimirivanov/ergon/pull/206), [#210](https://github.com/stanimirivanov/ergon/pull/210) |
| M05-PR20 | ergon-ui | Information-request composer in owned work | PR8, PR19 | G6 | [#217](https://github.com/stanimirivanov/ergon-ui/pull/217), [#219](https://github.com/stanimirivanov/ergon-ui/pull/219) |
| M05-PR21 | Ergon | Replay-safe requester responses | PR19 | G6 | [#212](https://github.com/stanimirivanov/ergon/pull/212), [#214](https://github.com/stanimirivanov/ergon/pull/214), [#216](https://github.com/stanimirivanov/ergon/pull/216) |
| M05-PR22 | ergon-ui | Adaptive response forms | PR18, PR21 | G7 | [#221](https://github.com/stanimirivanov/ergon-ui/pull/221), [#223](https://github.com/stanimirivanov/ergon-ui/pull/223), [#225](https://github.com/stanimirivanov/ergon-ui/pull/225) |
| M05-PR23 | Ergon | Authenticated, requester-owned case intake (PR23a/b) | PR16 | G3–G4 | [#218](https://github.com/stanimirivanov/ergon/pull/218), [#220](https://github.com/stanimirivanov/ergon/pull/220) |
| M05-PR24 | ergon-ui | Intake submission and uncertain recovery | PR17, PR23b | G5 | [#227](https://github.com/stanimirivanov/ergon-ui/pull/227) |
| M05-PR25 | Ergon | Durable deduplicated in-app notifications (PR25a/b/c) | PR9, PR19, PR21 | G7–G9 | [#222](https://github.com/stanimirivanov/ergon/pull/222), [#224](https://github.com/stanimirivanov/ergon/pull/224), [#226](https://github.com/stanimirivanov/ergon/pull/226) |
| M05-PR26 | ergon-ui | Notification surfaces with fresh target access | PR10, PR22, PR25 | G10 | [#229](https://github.com/stanimirivanov/ergon-ui/pull/229) |
| M05-PR27 | Ergon | Safe local browser logout (M03 prerequisite) | PR15 | G2 | [#228](https://github.com/stanimirivanov/ergon/pull/228) |
| M05-PR28 | ergon-ui | Sign-out and private-state retirement (PR28a/b, M03 prerequisite) | PR17, PR27 | G4 | [#231](https://github.com/stanimirivanov/ergon-ui/pull/231), [#233](https://github.com/stanimirivanov/ergon-ui/pull/233) |

M05-PR11 is delivered in two backend PRs to retain its sizing boundary:
[PR11a](https://github.com/stanimirivanov/ergon/issues/189) establishes terminal
facts and active-work guards; [PR11b](https://github.com/stanimirivanov/ergon/issues/191)
adds the authenticated completion command
and exact recovery. This shared foundation also guards PR13 priority writes.
PR12 followed both backend parts. The original plan IDs remain unchanged.

M05-PR13 was split before implementation to preserve reviewable boundaries:
PR13a ([issue #193](https://github.com/stanimirivanov/ergon/issues/193)) supplies
the shared priority schema and verified integrity; PR13b
([issue #195](https://github.com/stanimirivanov/ergon/issues/195)) adds the authenticated
current-owner command and exact recovery; PR13c
([issue #197](https://github.com/stanimirivanov/ergon/issues/197)) projects current priority through
active browser reads without changing queue order or cursors. The schema has two
independent consumers, so this is a consumed foundation, not a placeholder layer.
The merge order was PR13a (G4), PR13b (G5), PR13c (G6), then PR14 UI/guide
(G7). Producer rollout required compatible readers/UI. No new milestone or plan
coordinate was introduced.

Groups identify dependency-ready merge waves, not authorization for concurrent
repository changes. Follow the agreed one-repository-at-a-time workflow and wait
for backend review/merge before dependent UI. Each PR targets roughly 200–500
handwritten changed lines; re-slice an overrun before implementation rather than
dropping tests. No placeholder interfaces, services or temporary stubs are planned.

M05-PR19 was delivered in three backend PRs: PR19a establishes tested domain/storage
foundations (G3); [PR19b](https://github.com/stanimirivanov/ergon/issues/205) adds the authenticated current-owner command and exact
recovery (G4); [PR19c](https://github.com/stanimirivanov/ergon/issues/209) adds protected owner history and requester reads (G5).
Each depends on the previous part. Command and reads consume the storage
foundation independently. All three preceded PR20's composer/guide and
PR21's responses. PR19a exposed no HTTP endpoint.
The original plan coordinate and milestone remain unchanged.

## Boundaries and reuse

M05-PR21 was split into three backend deliveries before implementation:
PR21a ([issue #211](https://github.com/stanimirivanov/ergon/issues/211)) supplies
tested immutable answer storage (G6); PR21b ([issue #213](https://github.com/stanimirivanov/ergon/issues/213)) adds authenticated requester recording
and exact recovery (G7); PR21c ([issue #215](https://github.com/stanimirivanov/ergon/issues/215)) adds protected audience-specific reads (G8).
Each depends on the preceding part. Command and reads independently consume the
storage foundation; no HTTP producer existed in PR21a. PR22 followed all three.
PR20 was delivered as history inspection (ergon-ui PR #217) and confirmed
composition/recovery (ergon-ui PR #219). Accounting for that split and PR21's
three deliveries, PR22's three UI deliveries and PR23's two backend deliveries,
the plan delivers 42 PRs: 22 Ergon and 20 ergon-ui, including PR25's split below
and PR28's requester/workbench split.
Stable plan coordinates are unchanged.

M05-PR22 was delivered as resolver answer inspection (ergon-ui PR #221), requester
history (PR #223) and final-answer submission/recovery (PR #225).

M05-PR23 was split before implementation: PR23a
([issue #217](https://github.com/stanimirivanov/ergon/issues/217), G3) establishes
atomic application/storage intake and historical recovery, immediately consumed
by existing protected progress; PR23b
([issue #219](https://github.com/stanimirivanov/ergon/issues/219), G4) adds the authenticated requester command
and HTTP/session/CSRF tests. No unused table or browser endpoint is added by PR23a.
PR23b followed PR23a; PR24 UI/guide followed both backend reviews/merges (G5).
The existing case and requester schema is reused; no new migration is planned.

PR24 was delivered in [ergon-ui PR #227](https://github.com/stanimirivanov/ergon-ui/pull/227).
PR25 was split before implementation: PR25a ([issue #221](https://github.com/stanimirivanov/ergon/issues/221), G7)
adds source-pinned prospective handover delivery, immediately consumed by every
committed handover writer (merged Ergon PR #222); PR25b ([issue #223](https://github.com/stanimirivanov/ergon/issues/223), G8) adds question/answer delivery with explicit
recipient/source policies (merged Ergon PR #224); PR25c ([issue #225](https://github.com/stanimirivanov/ergon/issues/225), G9) adds authenticated bounded notification
reads without granting target access. Each followed the previous part. PR26
UI/guide followed all three backend reviews/merges. No standalone unused
notification framework, browser endpoint or historical backfill is added by PR25a.

PR26 was delivered in ergon-ui PR #229 after backend PR #226. PR27's local
session contract merged in Ergon PR #228. PR28a delivered requester sign-out
in ergon-ui PR #231; PR28b delivered workbench sign-out and its final reviewed
visual baselines in PR #233. Both include executable guide coverage.

The accepted bounded scope excludes attachments/scanning/retention, requester
capability approvals or contractual consent, arbitrary schema-driven forms,
automatic contract selection/run start, queue administration, SLA/due dates and
email/push/webhook delivery. Changing an exclusion requires an explicit plan review.

Multi-step execution, compensation and new proof types remain M04 capabilities;
compiled claims/contradictions belong to M06, simulation/promotion to M07 and
evidence-generated improvement proposals to M08. Studio authoring is not an M05
deliverable. The seven concept views span these boundaries, not seven M05 pages.

PR15, PR27 and PR28 are the only cross-milestone prerequisites: M03 gains a
separate requester client and local logout, not a new authority algebra or global
provider revocation. They retain M03 issue ownership: three plan coordinates,
four actual deliveries after PR28's split (4/42 PRs, about 10%).
There are no interface stubs to retire and no new third-party runtime dependency
is justified. Reuse Spring Security, RTK Query/Effect, React Hook Form, ui-web
modality and Playwright; borrow interaction patterns without importing a help-desk,
dialogue, graph or event-publication framework. Detailed contracts and any required
ADRs belong with the implementing slice, not this planning record.

## Verification and rollout limits

Ergon slices run `./mvnw -B -ntp -pl :repository-policy verify` and
`./mvnw -B -ntp verify`. SQL changes include
actual PostgreSQL empty/upgrade, tenant and concurrency evidence under the
[migration criteria](../development/sql-migrations.md).

UI slices use existing `pnpm repository:check`, `pnpm architecture:check`,
`pnpm verify`, `pnpm guide:check` and `pnpm guide:generate` from ergon-ui.
These are required future checks, not results reported by this plan.

PR2 supplies approved visual baselines; PR17 adds requester scope/build/browser
coverage to the existing harness with its first usable application. Current
guides simulate the BFF: they do not prove live OIDC, PostgreSQL or provider
behavior. A live two-app deployment acceptance gate remains a reported gap.

Requester authentication, intake and ownership changes are the main sizing and
security risks. Review exact authorization, replay and disclosure contracts in
their issues. Completion writes require all active-work readers to understand
the new lifecycle before enabling them; rollback disables writes while retaining
compatible read guards, additive schema and immutable audit, not erasing facts.
