# Ergon control plane

## TL;DR

The Spring Boot control plane persists Ergon resolution and human follow-up
facts. A trusted machine can now record one initial run supervisor; only that
assigned resolver can discover active runs and read their recorded start and
current state through the browser BFF. Separately enabled commands confirm human
prerequisites, transfer supervision, admit approved pinned-capability execution
and finalize exact reviewed outcome proof.
Confirmed failed-run retries separately create and assign only a fresh successor;
they inherit no execution authorization.
Assignment is neither a control lease nor capability-execution authority.

## Purpose

`control-plane` is the active Spring Boot application for Ergon cases,
evidence bindings, contract revisions, planning, authority, approvals,
capability execution, outcome proof, retries, and escalation.
Escalation atomically opens durable resolver follow-up work.
Resolvers can discover unclaimed work through a bounded oldest-first inbox and
filter it by immutable named queue before acquiring authority-attributed
ownership. They can recover their active claimed work through a separately
paginated resolver-owned view and retrieve the case evidence plus immutable
run, failed-execution, exhausted-retry, and pinned proof-requirement facts
needed to continue one owned escalation. The attempt history is durable, not a
live execution trace; proof remains unassessed for the escalated run.
First claims also populate append-only ownership history and a current-owner
projection. An internal revision-checked claim command records a durable
client-command receipt for exact replay. Internal release and later claiming
append ownership events without rewriting the first claim.
Separately, a tenant-scoped machine command can assign one initial supervisor
to an existing resolution run. The assigned resolver can discover active runs
and read their pinned start/current-state snapshots through the BFF while
their resolver authority remains current. This assignment is not a lease or
permission to execute a capability. New terminal receipts also retain runtime
gateway timing, exposed through a sanitized optional assigned-Console execution
member. Assigned snapshots also include the complete immutable transition
prefix through their state revision, without event identities or proof payloads.
The companion workbench renders these facts; historical timing, multi-step
spans and unsupported cost are not inferred.
Assigned Console reads also disclose existing account-access bindings and only
their linked connector sources within the run's pinned case prefix. Current
supervision and resolver authority remain mandatory; later and unbound material
is excluded. The first 100 bindings are returned with explicit truncation, never
as confidence or contradiction assessments. No schema/flag change is needed;
see [pinned evidence](../docs/pinned-run-evidence.md).
The same protected read now projects one pinned outcome condition, current
verification and immutable accepted proof with only its exact post-action
binding/source. MATCHING is not durable acceptance. A separate session/CSRF
command accepts exact reviewed matching proof, atomically closes the case and
records an attributed replay receipt. It defaults off and never invokes a
connector. See [outcome verification](../docs/run-outcome-verification.md).
Release remains disabled by default during a mixed-version rollout. Set
`ERGON_HUMAN_FOLLOW_UP_RELEASE_ENABLED=true` only after every instance serving
ownership traffic runs the new lifecycle code. Once a release is durable,
rollback to an older binary is unsafe; disable new releases and recover forward.

It is a modular monolith: capability packages contain application-owned ports
and inbound/outbound adapters while `domain-kernel` remains framework-free.

## Runtime

M05-PR13c adds current `priority`/`priorityRevision` to browser shared/owned work
and owned case summaries, including while commands are disabled. No assignment
means NORMAL/0; immutable opening/internal retrieval payloads and oldest-first
queue/cursor semantics are unchanged. Deploy the existing priority schema first
and compatible PR14 UI/guide before command enablement; see
[priority foundation](../docs/human-follow-up.md#priority-foundation).

The application requires PostgreSQL. Flyway owns schema creation and evolution.

| Variable | Meaning |
|:--|:--|
| `ERGON_DATABASE_URL` | PostgreSQL JDBC URL |
| `ERGON_DATABASE_USERNAME` | Database user |
| `ERGON_DATABASE_PASSWORD` | Database password |
| `ERGON_HUMAN_JWT_ISSUER_URI` | Trusted human JWT issuer; optional only for unprotected development paths |
| `ERGON_HUMAN_IDENTITY_PROVIDER` | Stable provider name paired with the issuer |
| `ERGON_BROWSER_SESSION_ENABLED` | Enables the confidential OIDC BFF; defaults to `false` |
| `ERGON_HUMAN_FOLLOW_UP_RELEASE_ENABLED` | Enables internal release after every ownership reader and writer is upgraded; defaults to `false` |
| `ERGON_HUMAN_FOLLOW_UP_HANDOVER_ENABLED` | Enables exact current-owner browser transfer after additive schema and compatible readers/UI; defaults to `false` |
| `ERGON_HUMAN_FOLLOW_UP_COMPLETION_ENABLED` | Enables exact current-owner completion and historical acknowledgement after terminal schema/guards and compatible UI; defaults to `false` |
| `ERGON_HUMAN_FOLLOW_UP_PRIORITY_ENABLED` | Enables current-owner priority setting and exact active-ownership acknowledgement after priority schema and compatible PR13c readers/PR14 UI; defaults to `false` |
| `ERGON_RUN_SUPERVISOR_HANDOVER_ENABLED` | Enables confirmed browser handover only after every backend supervision reader is upgraded; defaults to `false` |
| `ERGON_RUN_SUPERVISOR_APPROVAL_ENABLED` | Enables supervisor-scoped confirmed human decisions after the additive schema/backend deployment; defaults to `false` |
| `ERGON_RUN_PROOF_FINALIZATION_ENABLED` | Enables exact reviewed proof acceptance after additive schema/backend deployment; defaults to `false` |
| `ERGON_RUN_SUPERVISOR_EXECUTION_ENABLED` | Enables confirmed pinned-capability admission and explicit recovery after additive schema/backend deployment; defaults to `false` |
| `ERGON_RUN_SUPERVISOR_RETRY_ENABLED` | Enables confirmed failed-run successors and atomic supervision after every assignment reader is upgraded; defaults to `false` |
| `ERGON_RUN_SUPERVISOR_ESCALATION_ENABLED` | Enables confirmed exhausted-run escalation and atomic unassigned follow-up after additive schema/backend deployment; defaults to `false` |
| `ERGON_RUN_SUPERVISOR_CANCELLATION_ENABLED` | Enables untouched-run cancellation only after every state reader/writer fence and dependent UI is upgraded; defaults to `false` |

The JWT variables are a pair. Protected human operations fail closed when the
trust mapping is absent. Enabling browser sessions additionally requires the
Spring OAuth client registration `ergon-workbench`, including a secret supplied
by deployment configuration. See [authentication](../docs/authentication.md)
for the exact registration contract. The server listens on port `8090`.

```powershell
$env:ERGON_DATABASE_URL = "jdbc:postgresql://localhost:5432/ergon"
$env:ERGON_DATABASE_USERNAME = "ergon"
$env:ERGON_DATABASE_PASSWORD = "ergon"
.\mvnw.cmd -pl control-plane -am spring-boot:run
```

The credentials above match the development-only root `docker-compose.yml`.
Never reuse them outside local development.

### Local database identity cutover

Compose uses the project name `ergon` and the explicit volume
`ergon-postgres-data`. It does not attach or delete a predecessor
`rag-help-center_postgres-data` volume. PostgreSQL initialization variables are
applied only to an empty data directory, so silently reusing the old volume
would not create the new database or role reliably.

The development database publishes port `5432` on IPv4 loopback only. The
control plane can therefore connect from the host, but a containerized client
needs an explicit network and must not rely on this host-only topology.

Before the first start after this change, stop a predecessor Compose project
without deleting its volumes. Replace the project name when it was overridden
locally:

```powershell
docker compose -p rag-help-center down
docker compose up -d postgres
```

Export and import any local Ergon data that must move to the new database;
there is no automatic cross-volume migration. To roll back, stop the new
project without `--volumes`, restore the previous Compose definition, and
restart the predecessor project so it reattaches its preserved volume. See
[ADR 0033](../docs/decisions/0033-separate-ergon-local-postgresql-identity.md).

## Interfaces and limitations

M05-PR21a establishes immutable requester-answer storage only. The repository
derives historical requester/case attribution and returns a whole winner under
concurrent identity/question collisions; it does not authenticate or authorize.
PR21b/c command and protected reads precede PR22 UI/guide. Deploy the additive
V20261026102606 first and retain audit/schema on rollback; see
[response foundation](../docs/requester-information-requests.md#response-foundation).

M05-PR19c adds bounded current-owner question history and requester-safe questions
over PR19a/b storage and commands. Resolver history requires active owned work,
fresh authority and an OPEN bound case; requester history requires explicit case
ownership and survives closure. UI/guide follow separately; neither recording nor
reading means delivery. See [requester information requests](../docs/requester-information-requests.md).

Administrative follow-up completion has a separate default-off browser command.
Deploy V20261023195734 and all compatible readers/writers before enabling it
after the dependent UI. Exact authorized replay acknowledges only original
intent; it restores no private access. Completion retires work, never
case/run/proof state. Retain guards and audit after first use;
see [completion foundation](../docs/human-follow-up.md#administrative-completion-foundation).

Direct browser follow-up handover separately defaults off. It atomically replaces
current ownership and records an exact replay receipt without releasing work to
the shared queue. Deploy V20261023180000 first; budget the supporting history-key
scan/exclusive lock and index space. Disable commands for rollback and retain
audit. Backend review/merge precedes the dependent UI and guide. See
[direct owner handover](../docs/human-follow-up.md#direct-owner-handover).

The owner-scoped BFF now appends and reads immutable internal follow-up notes.
Notes retain claim/author attribution, exact intent replay and current-owner gates;
they are not evidence or proof. Deploy the additive notes migration before the
dependent UI; retain notes on rollback. See
[internal notes](../docs/human-follow-up.md#internal-notes) and
[ADR 0063](../docs/decisions/0063-record-owner-attributed-internal-notes.md).

The protected `GET .../{runId}/pinned-readiness` re-evaluates the exact immutable
contract and case prefix with the existing readiness evaluator. Latest-in-stream
required account-access provenance is selected independently of the history cap.
This is neither historical planner reasoning nor current-case readiness,
execution eligibility or proof. No migration/flag or lease is needed; backend
review/merge precedes UI/guide. See [pinned readiness](../docs/run-pinned-readiness.md).

The protected `GET .../{runId}/pinned-contract` exposes only that run's exact
immutable normalized definition under current supervisor/resolver access.
Declared prerequisites, steps and safeguards are not evaluated readiness,
effective policy or performed work. No schema/flag change or control lease is
needed; backend deployment precedes dependent UI/guide. See
[pinned contract inspection](../docs/run-pinned-contract.md).

Capability contracts are documented under [`docs`](../docs), especially
[runs](../docs/runs.md), [contracts](../docs/contracts.md),
[policy](../docs/policy.md), and [authentication](../docs/authentication.md).
The current inventory is in [current state](../docs/development/current-state.md).

Browser endpoints under `/bff` use an optional confidential OIDC session and
never return provider tokens to browser code. They fail with `503` while the
feature is disabled. The BFF exposes the tenant actor session, shared resolver
inbox, resolver-owned active work, an owner-scoped case summary, assigned-only
active-run list and detail snapshots with sanitized receipt measurements and
sequence-aligned transition metadata, CSRF-protected idempotent first claiming,
revision-checked claim commands, and exact-current-claim release. Authorized
browser inbox and owned-work
reads return the current ownership revision needed for those commands.
The case summary includes ordered durable attempts, the failed connector
outcome, the retry-ceiling decision, and the pinned but unassessed outcome
condition while keeping provider-operation and authority attribution
server-side.
The current server-side session store is process-local and is not suitable for
a multi-instance deployment.

Most endpoints under `/internal` are development boundaries, not public or
production-ready authorization. The supervisor-assignment POST is an explicit
exception: it requires a configured-issuer JWT with dedicated audience and
scope plus an `ergon_tenant_id` claim equal to the path tenant. Provision that
credential only to a trusted dispatcher. Other internal routes still require
an ingress/security design before production exposure. Tenant IDs in paths
are scope selectors, not credentials.

## Supervision handover rollout

Handover preserves the initial machine assignment and appends revisioned audit
facts. All upgraded run reads use the current-supervisor projection even when
new transfers are disabled. Deploy the migration and every projection-aware
backend reader before enabling `ERGON_RUN_SUPERVISOR_HANDOVER_ENABLED`.
After any handover, do not downgrade to an original-assignee reader: disable
new transfers and recover forward. Recipient IDs and historical receipts do
not grant execution authority or a lease. See
[handover contracts](../docs/runs.md#hand-over-current-run-supervision).

## Browser approval rollout

The assigned Console also supplies sanitized current approval review. Confirmed
browser decisions require current supervision and the exact request authority;
they never grant, execute or advance a run. Deploy the additive approval-command
migration before enabling the separate approval flag. Disable commands for an
application rollback without deleting immutable decisions/receipts. Existing
bearer decisions remain compatible. See [approval contracts](../docs/approvals.md).

## Browser execution rollout

`GET .../{runId}/invocation-history` separately exposes bounded, current-supervisor
gateway preparation/completion metadata, including measured unresolved calls.
It is not a live trace or complete coverage of historical/older-writer calls.
Deploy additive V20261020120000 before upgraded invocation writers/readers;
budget the consumption index's write-conflicting scan, index/WAL space and
retained audit growth. Older writers remain compatible; preserve audit on
rollback. No new flag or authority is introduced. See
[gateway-attempt history](../docs/run-invocation-history.md).

The independent `GET .../{runId}/execution-history` read exposes at most 100
sanitized admissions to the current supervisor/resolver, including earlier
epochs and terminal runs. It neither needs a live lease nor grants recovery
rights. Deploy additive V20261019120000 for indexed lookup; its transactional
index build scans attempts and blocks writes while building, with index/WAL
storage costs. Retain the index on rollback. No audit rewrite or flag enablement
is required. See [admission semantics](../docs/run-capability-execution.md#inspect-recorded-admissions).

Supervisor-confirmed execution has a separate default-off flag. Deploy the
additive command/attempt audit schema before enabling it. Admission, grant and
consumption commit before connector I/O. Replaying intent is acknowledgement
only; recovery uses an explicit new attempt and the original provider key.
Successful execution reaches VERIFYING, never case closure. Disable the flag
for rollback, retaining reservations and immutable audit. See
[execution contracts and recovery limits](../docs/run-capability-execution.md).

## Browser failed-run retry rollout

The independent read-only `GET .../{runId}/retry-successor` recovers a direct
runtime retry link only when the same resolver currently supervises both runs.
Hidden and absent targets share null. It works for terminal runs and disabled
commands without control acquisition, and requires no schema/flag change.
Deploy backend before dependent UI navigation and guide; fresh target access
remains mandatory. See [successor recovery](../docs/run-supervisor-retry.md#recover-a-visible-successor).

Confirmed retry creates the existing policy-bounded fresh successor and assigns
only that successor to the confirming current supervisor, atomically. It inherits
no execution authorization and invokes no connector. Deploy V20261016200000 and
all nullable-provenance assignment readers before enabling the separate retry flag.
After first use, disabling commands is safe but downgrading to old non-null
machine-subject mappers is not; retain audit and recover forward. The dependent UI
and guide follow backend review/merge. See [retry contracts](../docs/run-supervisor-retry.md).

## Browser exhausted-run escalation rollout

Confirmed exhausted-run escalation separately defaults off. Deploy V20261017120000
before enabling its flag. Audit, terminal runtime transition and unassigned work
commit together; no case closure, claim or connector invocation occurs. Supporting
source-key validation scans events/work under write-conflicting locks: budget
sizes, lock windows, WAL and disk. Disable new commands for behavioral rollback,
preserve all history and recover forward. Existing internal writers remain
compatible. UI and guide wait for backend review/merge. See
[escalation contracts](../docs/run-supervisor-escalation.md).

## Untouched-run cancellation rollout

Untouched-run cancellation has a separate default-off rollout: deploy
all backend fences/readers first with `spring.flyway.target=20261017120000`, then
remove that target to install V20261018120000. Alternatively quiesce writes across
schema-and-code deployment. New run-locking triggers must not meet old child-first
writers, even while commands are off; writer downgrade is unsafe after DDL.
Deploy compatible UI readers before enabling commands. It rejects even unconsumed grants and does not stop provider
work. Retain audit and recover forward after first use; see
[cancellation and recovery](../docs/run-supervisor-cancellation.md).

## Browser control rollout

The exclusive browser control gate defaults off. Deploy V20261018210000, all
backend command writers and compatible UI before quiescing commands and enabling
`ergon.run-supervision.control.enabled` (`ERGON_RUN_CONTROL_ENABLED`). Control
does not replace human/capability authority or revoke admitted provider work.
Preserve immutable lease/admission history and original uncertain intent; see
[control API and rollout](../docs/operations/browser-run-control.md).

## Requester browser deployment

M05-PR16 exposes only explicitly owned case goal/status/timestamps at the progress
resource. Deploy the additive ownership migration first; existing cases remain
unowned. Keep bindings on rollback. Until authenticated intake, reviewed privileged
bootstrap is required; no browser ownership writer exists. See
[requester progress](../docs/requester-case-progress.md).

Requester browser hosting uses a separate process/origin, not the resolver BFF's
authority. Set `ERGON_BROWSER_SESSION_AUDIENCE=requester` with browser sessions
enabled and provision only its `ergon-requester` confidential registration.
Only login/session/CSRF are allowed; resolver/internal routes fail closed.
No migration is needed. See [requester deployment](../docs/authentication.md#requester-browser-deployment).

## Verify

Docker must be running so Testcontainers executes PostgreSQL tests.

```powershell
.\mvnw.cmd -B -ntp -pl control-plane -am verify
```

Run the root reactor verification before merge.
