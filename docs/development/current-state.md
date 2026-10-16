# Current implementation state

## TL;DR

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
  epoch. Handover is disabled by default; no live execution tree or lease exists.

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
    commits with grant/consumption before I/O. Exact replay never dispatches;
    explicit recovery reuses the original consumption key with current authority
    and supervision fencing. Execution and proof remain separate. See
    [execution/recovery](../run-capability-execution.md). The dependent UI/guide
    is not yet delivered in this backend-only change.

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
  reassignment, priority, due-time, completion, or
  notification.
- No automatic resolution-contract selection or multi-step interpreter.
- No AI-assisted semantic binding or Ergon evidence compiler.
- Only one built-in access-restoration vocabulary and deterministic connector.
- No public production authorization model for internal endpoints.
- No compensation engine, failure classification/backoff, autonomous retries,
  simulation lab, or improvement proposal generator.
- No writable runtime controls, adaptive canvas, or public SDK. The companion
  UI can confirm supervision handover and exact human approval prerequisites,
  not steer execution, derive grants or grant a lease.
- Browser sessions are process-local and have no logout or provider-revocation
  flow; supervisor recovery/forced takeover, follow-up reassignment and
  completion remain undefined.

The intended sequence is maintained in
[implementation milestones](../roadmap/milestones.md). Update this document
when a merged capability materially changes what the repository can do.
