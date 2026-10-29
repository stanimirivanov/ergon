# Current implementation state

## TL;DR

M05's 28 approved implementation coordinates are delivered through 42 merged PRs
(22 Ergon, 20 ergon-ui), ending with UI PR #233. The
[acceptance record](../roadmap/m05-acceptance.md) maps criteria to source/tests,
guides and successful final backend/UI CI runs. Real two-app/OIDC deployment
acceptance remains unverified; this is bounded implementation, not production
readiness or a general compiler/runtime claim.

M05-PR27 adds CSRF-protected local browser logout as an M03 prerequisite. Only
the configured client's authenticated POST `/bff/logout` retires its HTTP session,
security context, CSRF, local authorized clients and cookie. No provider revocation,
ownership change or accepted-work cancellation. PR28a/b UI and guides merged in
ergon-ui PRs #231/#233. See [ADR 0081](../decisions/0081-retire-local-browser-sessions.md).

M05-PR25c adds fixed-client, tenant/recipient-scoped notification feeds capped at
50 deliveries with explicit truncation. The deployment selects requester questions
or resolver handover/answer metadata. Resolver authority is checked before/after
the read; alerts contain no private text and never restore target access.
Protected targets must be fetched anew. PR26 UI/guide is merged in ergon-ui PR #229; see
[ADR 0080](../decisions/0080-protect-bounded-notification-feeds.md).

M05-PR25b prospectively delivers requester questions to their immutable case
requester and answers to the active owner visible at delivery selection. A
captured CLAIMED epoch is historical, not current target access; released or
completed work produces no resolver alert. No text is copied and replay never
backfills or retargets delivery. PR25c's protected feeds consume these records; see
[ADR 0079](../decisions/0079-deliver-requester-information-notifications.md).

M05-PR25a prospectively delivers one immutable in-app notification in the same
transaction as a follow-up handover. Source replay cannot duplicate delivery;
recipient/tenant integrity and mutation guards live in PostgreSQL. PR25c now
exposes the protected feed. See [ADR 0078](../decisions/0078-deliver-atomic-handover-notifications.md).

M05-PR23b exposes requester-only CSRF-protected case intake and historical recovery.
The session chooses the creator; narrow receipts never assert current state.
Input rejection is distinct from unexpected defects. No migration/dependency/flag;
PR24 UI/guide follows backend review/merge. See [intake](../requester-case-intake.md).

The PR23a atomic intake foundation uses existing immutable case events/bindings,
now consumed by PR23b's browser command. Existing legacy writers stay unchanged.
See [requester intake](../requester-case-intake.md).

M05-PR21c adds nullable, narrow recorded answers to existing protected requester
and resolver question histories without changing question order, bounds or access.
Requester history survives closure; resolver disclosure requires active ownership,
an OPEN bound case and fresh authority. POST receipts stay unchanged; no schema,
dependency or flag is added. PR22 UI/guide follows backend review/merge. See
[protected history](../requester-information-requests.md#protected-history).

M05-PR21b adds requester-only CSRF-protected answering and exact recovery under
explicit ownership and an OPEN case lock. Receipts contain no actor or resolver
context; no case/run/proof state changes. See
[answer command](../requester-information-requests.md#answer-command-and-recovery).

M05-PR21a supplies immutable, tenant-safe answer storage and whole-winner replay
under the PR21b command, without observation-stream or runtime/proof changes.
See [response foundation](../requester-information-requests.md#response-foundation).

M05-PR19c adds bounded current-owner question history and requester-safe case
questions over PR19a/b storage and commands. UI/guide follow backend review/merge.
Questions remain untrusted text, not delivery, evidence or approval.
See [requester information requests](../requester-information-requests.md).

M05-PR16 adds requester-owned case progress with explicit immutable ownership,
not approval authority. Legacy cases remain unowned/hidden; trusted bootstrap is
required for legacy cases; new intake binds its verified creator. No source or
runtime evidence is disclosed by progress.
See [requester case progress](../requester-case-progress.md).

M05-PR15 (M03 prerequisite) adds an opt-in, separately deployed requester browser
audience with its own confidential OIDC client and cookie. Only login, tenant
session and CSRF reads were initially allowed; PR16 adds the explicitly owned
progress read. All resolver/internal routes remain denied.
Case progress, intake, requester UI/guide and logout remain separate slices.
See [requester deployment](../authentication.md#requester-browser-deployment).

M05-PR13c projects current priority/revision through authorized browser shared
and owned work and owned case summaries, including while commands are disabled.
No assignment means NORMAL/0; value/revision are read together from immutable
history. PR13b's command still requires exact active ownership and fresh resolver
authority, including replay. PR14 UI/guide follows; keep the producer disabled
until compatible UI is deployed. Priority is administrative metadata
and never changes existing queue order, ownership, risk or runtime/proof state.
See [priority foundation](../human-follow-up.md#priority-foundation).

M05-PR11 now includes a separately default-off session/CSRF administrative completion
command and authorized exact-intent historical recovery. Current reads and
existing writers reject terminal work while opening, ownership, notes, case/run
state and proof remain intact. M05-PR12 UI/guide is merged in ergon-ui PR #209.
Deploy terminal schema/all guards before enabling compatible backend/UI; retain
guards/audit after first use.
See [completion foundation](../human-follow-up.md#administrative-completion-foundation).

Owner-scoped browser follow-up handover now supports exact current-claim/revision
transfer to a currently authorized same-tenant resolver, atomic ownership and
historical same-intent acknowledgement. It defaults off; the dependent UI/guide
is not included. See [handover](../human-follow-up.md#direct-owner-handover).

M05 internal follow-up notes now have a current-owner/session/CSRF BFF boundary
and append-only tenant-safe persistence. Exact text/claim/revision retries return
the original record; current ownership and authority are checked again even on
replay. Reads expose the newest 100 attributable notes with explicit truncation,
not evidence, proof or requester data. The companion UI/guide shipped as M05-PR8.

- `domain-kernel` and `control-plane` contain the active Ergon implementation.
- The first access-restoration vertical slice now reaches verified resolution,
  bounded retry, explicit escalation, and durable human follow-up using
  deterministic local adapters.
- All predecessor RAG Help Center executables have been retired; their APIs,
  storage, and events are not Ergon compatibility boundaries.
- Internal endpoints are development boundaries and must not be treated as a
  production authorization model.
- The BFF exposes shared discovery, CSRF-protected claiming, resolver-owned
  active work, owner-scoped case context, and assigned-only active-run
  discovery/detail snapshots. Initial assignment is machine-controlled;
  confirmed browser handover changes current supervision with a separate audit
  epoch. Browser control now has a separately default-off backend lease/fence;
  UI/countdown and guide updates are merged. No live multi-step
  execution tree is inferred. See [control rollout](../operations/browser-run-control.md).
- M05's remaining resolver/requester work follows the agreed
  [delivery plan](../roadmap/m05-adaptive-resolution-experience.md). Notes and
  backend handover are implemented; handover UI, priority, administrative completion,
  requester app and notifications remain planned. M01–M04 are delivered only for the bounded
  access-restoration slice; GitHub owns formal milestone state.

## Active Ergon slice

The current slice can:

1. open an append-only case with optimistic stream concurrency;
2. record attributable connector observations;
3. bind typed account-access facts to source observations;
4. validate, publish, and pin immutable resolution-contract revisions;
5. evaluate readiness and deny-by-default policy requirements;
6. start an immutable resolution run pinned to case, contract, policy, step,
   capability, risk, and approval meaning;
7. record scoped, expiring human authority evidence and resolve authenticated
   JWT subjects to tenant actors;
8. request and record one approval decision;
9. derive, consume, and invoke a narrowly scoped capability grant through a
   deterministic identity connector;
10. store/replay a terminal provider receipt and project it into run state;
11. assess and atomically accept post-action outcome proof, closing the case
    only as `VERIFIED_RESOLVED`;
12. start one attributed successor for a failed run under a versioned retry
    ceiling;
13. record explicit resolver escalation when that retry budget is exhausted,
    leaving the case open;
14. atomically open and retrieve one immutable-source human follow-up work item
    for that escalation;
15. discover unclaimed work through a bounded, oldest-first resolver inbox;
16. claim open work once with immutable resolver and authority attribution,
    mirrored into append-only ownership history and a current-owner projection;
    and
17. page through the authenticated resolver's active claimed work; and
18. route creation to an immutable `access-restoration` queue and filter the
    shared inbox by exact queue key; and
19. establish an optional confidential OIDC BFF that resolves a browser session
    to a tenant actor without exposing provider tokens; and
20. expose the resolver's shared inbox through that session with an independent,
    read-only browser contract; and
21. issue session-bound CSRF tokens and claim follow-up work through a
    browser-specific command contract; and
22. expose active work owned by the browser-session resolver without returning
    provider identity or authority attribution; and
23. expose one owned escalation's case observations, pinned contract, immutable
    run snapshot, ordered durable attempt history, failed connector outcome,
    exhausted-retry decision, and pinned but unassessed outcome-proof condition
    without disclosing claim, provider-operation, or authority attribution; and
24. claim first-owner work through an internal revision-checked command with a
    durable exact-replay receipt; and
25. submit the same first-claim intent through an independent CSRF-protected
    browser contract without exposing resolver or authority attribution; and
26. release an exact active claim on the internal resolver boundary, return
    work to its original shared queue, and acquire a later distinct claim using
    a new revision-checked command while preserving historical attribution;
    release is disabled by default until upgraded ownership readers are deployed;
    and
27. read current ownership revisions in the browser inbox and owned-work page,
    release an exact owned claim through the CSRF-protected BFF, and submit a
    later revision-checked browser claim without exposing private attribution;
    and
28. record one machine-authenticated initial run-supervisor assignment and
    let its exact assignee discover and read a narrow run snapshot only while
    current resolver authority remains; and
29. retain runtime-measured terminal connector gateway timing on the exact
    immutable receipt and disclose a sanitized optional execution through the
    assigned-only Console read. Historical timing remains absent, cost is not
    reported, and the workbench now renders the sanitized member; and
30. expose the assigned snapshot's complete immutable transition prefix through
    its observed revision, with sanitized state metadata and receipt alignment.
    This is at most two recorded events per attempt, not a multi-step executor
    or inspected proof payload; and
31. confirm a revision-checked browser supervisor handover to another current
    tenant resolver, preserve initial assignment and immutable transfer audit,
    exactly replay the originating command, and gate subsequent discovery and
    Console access against current supervision. Recipient IDs are bounded and
    authority-gated; commands are disabled by default until all backend readers
    are upgraded. No lease, steering, notification or execution permission is added; and
32. review the latest human approval request in the assigned Console and confirm
    APPROVED/REJECTED with current supervision, exact authority/scope and request
    validity. Immutable browser command receipts support exact historical replay.
    Approval commands are disabled by default; they never create a grant, invoke
    a connector or advance run state. Existing bearer decisions remain compatible; and
33. inspect existing account-access bindings and only their linked connector
    sources inside an assigned run's pinned case prefix. Current supervision
    and resolver authority gate disclosure; unbound/later material is excluded.
    A bounded 100-binding history reports truncation without inferring latest
    truth, confidence, contradiction meaning or outcome proof; and
34. inspect one pinned outcome condition, current post-action assessment and
    immutable accepted proof with only its exact selected citation. Current
    supervision and resolver authority gate disclosure. A mutable MATCHING result
    is not acceptance or closure; and
35. accept exact reviewed matching proof through a supervisor-authorized,
    CSRF-protected browser command. Run completion, case closure and attributed
    command replay receipt commit atomically. This defaults off under
    `ERGON_RUN_PROOF_FINALIZATION_ENABLED` and never invokes a connector. See
    [outcome verification](../run-outcome-verification.md); and
36. admit and execute the exact approved pinned capability through a separate
    default-off, session/CSRF browser command. Immutable admission/attempt audit
    commits with grant/consumption before I/O. Replay of admitted intent never dispatches;
    explicit recovery reuses the original consumption key with current authority
    and supervision fencing. Execution and proof remain separate. See
    [execution/recovery](../run-capability-execution.md). Its dependent UI and
    executable guide are merged; and
37. confirm an eligible failed run's fresh successor through a separately
    default-off browser retry command. Current planning, runtime retry, atomic
    successor supervision and exact command audit share one transaction. Nothing
    inherits execution authority and no connector is invoked. Every assignment
    reader must support nullable machine provenance before enabling; after first
    use preserve audit and recover forward. The dependent retry UI and guide are
    merged. See [browser retry](../run-supervisor-retry.md); and
38. confirm exhausted failed-run escalation through a separately default-off
    supervisor/resolver BFF command. Exact reviewed exhaustion, runtime terminal
    transition, unassigned human work and attributed command audit commit together.
    Replay acknowledges creation without current access or ownership. No case
    state change or connector invocation occurs. The dependent UI and guide are
    merged. See [browser escalation](../run-supervisor-escalation.md); and
39. confirm untouched-run cancellation through a separate default-off supervisor/
    resolver BFF command. One RUN_CANCELLED event, terminal state and attributed
    exact-command audit commit together before any durable grant or execution
    record. Legacy approval/authorization writers share the run fence. No provider,
    case, proof, retry-budget or human-work change occurs. UI and guide follow
    backend review/merge. See [cancellation](../run-supervisor-cancellation.md); and
40. coordinate exclusive authenticated-session browser control with fixed
    120-second leases, monotonic acquisition fences, exact lifecycle receipts
    and immutable admissions across all seven browser command families. Protection
    defaults off under `ERGON_RUN_CONTROL_ENABLED`; deploy schema, all writers
    and compatible UI before enabling. Control loss does not revoke admitted
    work. The dependent UI/guide waits for backend review/merge. See
    [control rollout](../operations/browser-run-control.md); and
41. expose bounded browser execution-admission history to the current supervisor
    with current resolver authority, including prior epochs and terminal runs.
    Admissions are not spans or outcomes; private identities/replay coordinates
    remain hidden. Its UI and guide are merged. See
    [admissions](../run-capability-execution.md#inspect-recorded-admissions).
42. recover a direct runtime retry-successor link under independently current
    source and target supervision/resolver gates, including terminal runs and
    internal retries. Hidden and absent targets share null; no control lease,
    command replay or execution authority is granted. No schema/flag change;
    dependent navigation UI and guide are merged. See
    [successor recovery](../run-supervisor-retry.md#recover-a-visible-successor).

43. record append-only gateway preparations and measured completions separately
    from browser admissions and the winning receipt. Current supervisors can
    inspect bounded, sanitized cross-epoch/terminal/internal history; unknown
    effects and missing completion are never reported as fabricated outcomes.
    No backfill, live status, costs or new authority. Schema precedes upgraded
    writers; its dependent UI and guide are merged. See
    [gateway-attempt history](../run-invocation-history.md).

44. inspect only the exact immutable normalized contract pinned by a currently
    supervised run, including terminal work. Required evidence, applicability,
    declared capability order/safeguards and outcome condition remain separate
    from effective runtime policy, readiness, performed steps and accepted proof.
    No schema, command or new flag; dependent UI and guide are merged.
    See [pinned contract inspection](../run-pinned-contract.md).

45. re-evaluate that exact pinned contract against the run's immutable case prefix,
    using the existing readiness evaluator and only its selected required
    account-access binding/source, independently of the first-100 history cap.
    Explicit evaluator identity and fixed inputs distinguish this from current-case
    readiness, historical planner reasoning, authority and accepted proof.
    No migration, command or new flag; dependent UI/guide waits for backend merge.
    See [pinned readiness inspection](../run-pinned-readiness.md).

Detailed HTTP semantics live with their capabilities:

- [contracts](../contracts.md)
- [policy](../policy.md)
- [runs and outcome proof](../runs.md)
- [approvals](../approvals.md)
- [human authority](../human-authority.md)
- [authentication](../authentication.md)
- [authorization grants](../authorization-grants.md)
- [capability invocation](../capability-invocations.md)
- [human follow-up](../human-follow-up.md)
- [pinned run evidence](../pinned-run-evidence.md)
- [outcome verification](../run-outcome-verification.md)
- [confirmed failed-run retry](../run-supervisor-retry.md)
- [confirmed exhausted-run escalation](../run-supervisor-escalation.md)
- [untouched-run cancellation](../run-supervisor-cancellation.md)
- [exclusive browser control](../operations/browser-run-control.md)

## Runtime and data

`control-plane` is a Spring Boot MVC application backed by PostgreSQL and
Flyway. Its active configuration requires:

- `ERGON_DATABASE_URL`
- `ERGON_DATABASE_USERNAME`
- `ERGON_DATABASE_PASSWORD`

Human-authenticated endpoints also use:

- `ERGON_HUMAN_JWT_ISSUER_URI`
- `ERGON_HUMAN_IDENTITY_PROVIDER`

The browser session boundary additionally uses:

- `ERGON_BROWSER_SESSION_ENABLED` (disabled by default)
- a Spring OAuth client registration named `ergon-workbench`

When the JWT pair is absent, protected operations fail closed while the
application can still start for unprotected development behavior. See the
[control-plane README](../../control-plane/README.md).

## Rewrite boundary

No predecessor executable applications remain. The final article ingestion
service and the Kafka development broker used only by its outbox publisher were
removed after every event consumer had been retired. Its article HTTP API,
aggregate, projections, idempotency records, and integration events were not
migrated. Superseded ADRs 0001 through 0003 remain historical input; a future
compiler slice must define Ergon source and evidence contracts from its own
requirements.

The predecessor gateway and its unused Spring Cloud edge were removed; future
Ergon ingress requires an explicit security and deployment design. The
incomplete Q&A coordinator and its exclusive MongoDB and Redis development
infrastructure were also removed rather than migrated. It had no inbound API
or production model adapter, and its question/answer model is not an Ergon
compatibility boundary.

The predecessor hybrid retrieval service was removed after its only runtime
consumer was retired. Its article/chunk HTTP contract and ranking output were
not migrated; superseded ADR 0005 remains historical input for a future
evidence-bundle retrieval design.

The unconsumed embedding worker, its Spring AI dependency, and its Ollama
development service were removed without migrating article-specific chunking
or vector projections. Superseded ADR 0004 remains historical input for M06.
The pgvector-capable PostgreSQL image remains compatible with the target
architecture and existing local predecessor vector objects.

Reusable predecessor principles recorded in superseded ADRs include append
concurrency, separate occurrence and recording time, transactional outbox, and
idempotent consumers. They must be justified by an active Ergon slice before
reuse. The old article aggregate, Q&A contract, MongoDB conversation model,
predecessor service topology, and provisional tenant header are not
compatibility requirements. Existing local PostgreSQL volumes may retain
predecessor objects; this retirement performs no destructive data cleanup.

The active Maven reactor uses the `org.ergon:ergon` parent and future container
images use the `ergon` namespace; see
[ADR 0032](../decisions/0032-adopt-ergon-build-coordinates.md). Local Compose
initializes an `ergon` database and role in the explicit
`ergon-postgres-data` volume while preserving predecessor volumes for manual
recovery; see
[ADR 0033](../decisions/0033-separate-ergon-local-postgresql-identity.md). The
GitHub SCM URL retains its factual existing repository name.

## Deliberate limitations

- No queue administration, configurable routing, automatic assignment,
  administrative reassignment, priority, due-time, completion, or
  notification.
- No automatic resolution-contract selection or multi-step interpreter.
- No AI-assisted semantic binding or Ergon evidence compiler.
- Only one built-in access-restoration vocabulary and deterministic connector.
- No public production authorization model for internal endpoints.
- No compensation engine, failure classification/backoff, autonomous retries,
  simulation lab, or improvement proposal generator.
- No general pause/cancel/branch controls, adaptive canvas, or public SDK.
  Narrow supervisor-confirmed execution, proof finalization and failed-run retry
  do not establish general steering. Browser control is a separate explicit lease;
  retry, control and pinned-readiness UI/guide slices are merged.
- Browser sessions are process-local and have no logout or provider-revocation
  flow; supervisor recovery/forced takeover, administrative follow-up reassignment and
  completion are not implemented. Planned bounded behavior does not establish
  the later slices' exact authorization, replay or lifecycle contracts.

The intended sequence is maintained in
[implementation milestones](../roadmap/milestones.md). Update this document
when a merged capability materially changes what the repository can do.
