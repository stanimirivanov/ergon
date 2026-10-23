# M05 adaptive resolution delivery plan

- Status: Agreed implementation sequence; entries are planned, not delivery claims.

## TL;DR

**Milestone:** M05 - Adaptive Resolution Experience.

Finish the resolver experience and deliver a separately authenticated requester
canvas over the existing case/runtime boundaries. The 28 plan coordinates below
deliver 29 PRs: 13 Ergon (PR11 is split) and 16 ergon-ui; GitHub owns their live
state. Each backend contract
is reviewed and merged before its dependent UI, which includes its user-guide
update. This plan does not implement or authorize a new runtime protocol.

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

IDs are stable plan coordinates, not GitHub issue numbers. All rows start as
planned; follow linked issues/PRs for actual delivery. One row has one reason to
exist and includes its implementation, tests and documentation. Presentation-only
refactors remain separate from new business behavior.

| ID | Repository | Outcome | Depends on | Group |
|:--|:--|:--|:--|:--|
| [M05-PR1](https://github.com/stanimirivanov/ergon/issues/183) | Ergon | Reconcile M05 acceptance and published milestone ownership | None | G0 |
| M05-PR2 | ergon-ui | Approved visual baselines and Console chrome | PR1 | G1 |
| M05-PR3 | ergon-ui | Recorded-execution workspace | PR2 | G2 |
| M05-PR4 | ergon-ui | Authorization and recovery presentation | PR3 | G3 |
| M05-PR5 | ergon-ui | Evidence and outcome-proof presentation | PR2 | G2 |
| M05-PR6 | ergon-ui | Shared and owned follow-up redesign | PR2 | G2 |
| M05-PR7 | Ergon | Owner-attributed internal notes | PR1 | G1 |
| M05-PR8 | ergon-ui | Structured notes in owned work | PR6, PR7 | G3 |
| [M05-PR9](https://github.com/stanimirivanov/ergon/issues/187) | Ergon | Atomic current-owner follow-up handover | PR7 | G2 |
| M05-PR10 | ergon-ui | Confirmed handover and uncertain recovery | PR6, PR9 | G3 |
| M05-PR11 | Ergon | Administrative human-work completion | PR7, PR9 | G3 |
| M05-PR12 | ergon-ui | Confirmed work completion and recovery | PR8, PR10, PR11 | G4 |
| M05-PR13 | Ergon | Revisioned priority, unchanged queue order | PR1 | G1 |
| M05-PR14 | ergon-ui | Priority display and owner changes | PR6, PR13 | G3 |
| M05-PR15 | Ergon | Separate requester browser boundary (M03 prerequisite) | PR1 | G1 |
| M05-PR16 | Ergon | Requester-scoped case progress | PR15 | G2 |
| M05-PR17 | ergon-ui | First usable authenticated requester app | PR2, PR16 | G3 |
| M05-PR18 | ergon-ui | Complete bounded requester canvas | PR17 | G4 |
| M05-PR19 | Ergon | Attributable requester-information requests | PR7, PR16 | G3 |
| M05-PR20 | ergon-ui | Information-request composer in owned work | PR8, PR19 | G4 |
| M05-PR21 | Ergon | Replay-safe requester responses | PR19 | G4 |
| M05-PR22 | ergon-ui | Adaptive response forms | PR18, PR21 | G5 |
| M05-PR23 | Ergon | Authenticated, requester-owned case intake | PR16 | G3 |
| M05-PR24 | ergon-ui | Intake submission and uncertain recovery | PR17, PR23 | G4 |
| M05-PR25 | Ergon | Durable deduplicated in-app notifications | PR9, PR19, PR21 | G5 |
| M05-PR26 | ergon-ui | Notification surfaces with fresh target access | PR10, PR22, PR25 | G6 |
| M05-PR27 | Ergon | Safe local browser logout (M03 prerequisite) | PR15 | G2 |
| M05-PR28 | ergon-ui | Sign-out and private-state retirement (M03 prerequisite) | PR17, PR27 | G4 |

M05-PR11 is delivered in two backend PRs to retain its sizing boundary:
[PR11a](https://github.com/stanimirivanov/ergon/issues/189) establishes terminal
facts and active-work guards; PR11b then adds the authenticated completion command
and exact recovery. This shared foundation also guards PR13 priority writes.
PR12 waits for both backend parts. The original plan IDs remain unchanged.

Groups identify dependency-ready merge waves, not authorization for concurrent
repository changes. Follow the agreed one-repository-at-a-time workflow and wait
for backend review/merge before dependent UI. Each PR targets roughly 200–500
handwritten changed lines; re-slice an overrun before implementation rather than
dropping tests. No placeholder interfaces, services or temporary stubs are planned.

## Boundaries and reuse

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
provider revocation. They retain M03 issue ownership (3/29 PRs, about 10%).
There are no interface stubs to retire and no new third-party runtime dependency
is justified. Reuse Spring Security, RTK Query/Effect, React Hook Form, ui-web
modality and Playwright; borrow interaction patterns without importing a help-desk,
dialogue, graph or event-publication framework. Detailed contracts and any required
ADRs belong with the implementing slice, not this planning record.

## Verification and rollout limits

Ergon slices run `./mvnw -B -ntp -pl :repository-policy verify` and
`./mvnw -B -ntp verify` (the `.cmd` wrappers on Windows). SQL changes include
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
