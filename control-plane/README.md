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

Internal [simulation fixture registration](../docs/simulation.md#internal-fixture-registration)
stores complete immutable curated packages with original tenant actor attribution.
It does not execute fixtures or expose a browser authorization boundary.

The in-process pinned scenario replay service now evaluates those packages through
production readiness/planning services using read-only simulation ports. A separate
capability/proof evaluator exercises approval, invocation and proof rules with
isolated scripted adapters. Model proposal replay checks scripted suggestions
against the production plan and pinned references without granting authority.
Domain assertion evaluation and explicit simulated-budget accounting are available
as composable APIs. Evaluation/report orchestration and promotion remain separate.

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

POST `/bff/logout` supports explicit local sign-out for the selected browser
client, with session-bound CSRF and no tenant/actor hints. It returns empty
no-store 204, invalidates session-local security/CSRF/provider material and expires
only that deployment's cookie. No redirect, IdP logout, command cancellation or
claim/lease release occurs. GET and legacy logout are denied; disabled BFF returns
503. Deploy before M05-PR28 UI controls. See
[authentication](../docs/authentication.md) and [ADR 0081](../docs/decisions/0081-retire-local-browser-sessions.md).

M05-PR25c exposes GET `/bff/v1/tenants/{tenantId}/notifications` for this deployment's
fixed OIDC client and registered actor. Returns at most 50 recent safe metadata
items plus `truncated`, never unread counts or source text. Requester feeds target
owned cases; workbench feeds require fresh resolver authority and target follow-up
work. An alert is historical; open its target through independently fresh protected
reads. Apply the recipient-parent indexes after PR25a/b schema and assess their
scan/write-lock window. Rollback removes the read route and retains indexes/audit.
See [ADR 0080](../docs/decisions/0080-protect-bounded-notification-feeds.md).

M05-PR25b extends prospective delivery to requester questions and answers.
Question recipients derive from immutable case bindings; answers capture the
active ownership epoch visible at selection, without new work locks or a promise
of current-at-commit ownership. Released/completed work yields no resolver alert.
Replays never backfill or retarget; rollback retains additive schema/triggers.
The protected feeds above consume these deliveries; see
[ADR 0079](../docs/decisions/0079-deliver-requester-information-notifications.md).

M05-PR25a adds prospective, immutable follow-up handover notifications through
a transaction-local source trigger. Every new committed handover produces one
recipient-pinned delivery, including from compatible older writers; old receipt
replay produces none. The protected feed now consumes these records. Apply the
additive migration first and assess the source index's lock/scan window. An
application rollback leaves delivery active; use the existing handover flag to
stop new transfers and recover forward. See [ADR 0078](../docs/decisions/0078-deliver-atomic-handover-notifications.md).

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

```bash
export ERGON_DATABASE_URL="jdbc:postgresql://localhost:5432/ergon"
export ERGON_DATABASE_USERNAME="ergon"
export ERGON_DATABASE_PASSWORD="ergon"
./mvnw -pl control-plane -am spring-boot:run
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

```bash
docker compose -p rag-help-center down
docker compose up -d postgres
```

Export and import any local Ergon data that must move to the new database;
there is no automatic cross-volume migration. To roll back, stop the new
project without `--volumes`, restore the previous Compose definition, and
restart the predecessor project so it reattaches its preserved volume. See
[ADR 0033](../docs/decisions/0033-separate-ergon-local-postgresql-identity.md).

## Interfaces and limitations

M05-PR23b exposes the requester collection POST for authenticated intake/recovery,
using the fixed requester client, tenant actor and session CSRF. A 201/200 receipt
contains only original normalized input/opening times; no authority or current
state. No schema/dependency/flag. PR24 UI/guide follows backend review/merge;
see [intake contract](../docs/requester-case-intake.md#browser-contract).

M05-PR23a adds the application/storage intake foundation: opening, projection and
verified creator binding commit together; exact normalized recovery returns the
original opening. That foundation introduced no browser endpoint, migration,
dependency or flag; PR23b supplies its requester authentication/CSRF. See
[intake foundation](../docs/requester-case-intake.md).

M05-PR21b adds authenticated requester answers and exact recovery, holding the
OPEN owned case lock through persistence. Deploy V20261026102606 first; no new
schema or flag. Block the POST or roll back application code to disable writes,
retaining audit. PR21c reads precede PR22 UI/guide; see
[answer command](../docs/requester-information-requests.md#answer-command-and-recovery).

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

Protected question histories now include nullable recorded answers under the
same audience-specific access rules. Requester history survives case closure;
resolver history remains current-owner/active-work/OPEN-case scoped. No new
schema or flag is introduced; deploy the existing response schema before readers
and the dependent PR22 UI/guide. See
[protected history](../docs/requester-information-requests.md#protected-history).

M05-PR16 exposes only explicitly owned case goal/status/timestamps at the progress
resource. Deploy the additive ownership migration first; existing cases remain
unowned. Keep bindings on rollback. Authenticated intake binds new cases; legacy
cases require reviewed privileged bootstrap and cannot be adopted by intake. See
[requester progress](../docs/requester-case-progress.md).

Requester browser hosting uses a separate process/origin, not the resolver BFF's
authority. Set `ERGON_BROWSER_SESSION_AUDIENCE=requester` with browser sessions
enabled and provision only its `ergon-requester` confidential registration.
Only the requester login/session/CSRF, owned progress/question reads, intake and
answer commands are allowlisted; resolver/internal routes fail closed.
No migration is needed. See [requester deployment](../docs/authentication.md#requester-browser-deployment).

## Verify

Docker must be running so Testcontainers executes PostgreSQL tests.

```bash
./mvnw -B -ntp -pl control-plane -am verify
```

Run the root reactor verification before merge.

## Documentary source revisions

With browser sessions enabled, current tenant-wide resolvers may POST raw UTF-8
bytes to `/bff/v1/tenants/{tenantId}/sources/{sourceId}/revisions/{digest}` using
`text/markdown` or `application/vnd.oai.openapi+json`. The existing workbench
session and CSRF token are required. The digest is lowercase SHA-256 of the exact
body, limited to 65,536 bytes, with no newline or Unicode normalization.
This endpoint stores untrusted material; it does not validate OpenAPI or compile it.

Registration returns 201 for a new identity or 200 for an exact retry, preserving
original submitter and database recording time. Changed content/digest or format
conflicts return 409, malformed UTF-8/identity 422 and oversize content 413.
GET at the same path returns the exact source text, media type and attribution.
Missing revisions and absent current resolver authority both return 404. Every
source response uses `Cache-Control: no-store`; references are never fetched.

The additive `knowledge_source_revisions` migration enforces immutable rows and
same-tenant submitter references. Deploy migrations before application code.
Application rollback retains stored source revisions; do not reverse the migration
or delete audit records. Confidential sources require deployment-level retention
and access controls; this slice adds no deletion API or rendering UI.

### Markdown source compilation

GET the same source revision path with `/compilations/commonmark-v1` appended to
inspect its CommonMark syntax. The existing workbench session and current resolver
authority are required, and responses use `no-store`. The read computes a projection;
it does not persist or accept evidence. Identical source identity and compiler
revision produce the same extraction UUID and ordered observations.

Compiler `commonmark-0.24.0-v1` emits HEADING, PARAGRAPH and ORDERED_LIST blocks in
document preorder. Each observation quotes original syntax with half-open Unicode
code-point offsets. Multi-line quotes preserve CRLF and indentation between the
first and last block spans; parent list quotes overlap child paragraph quotes.
Code blocks and HTML blocks are not observations. Inline HTML, links and image
references remain quoted text; nothing is fetched or rendered.

Limits are 64 KiB of source bytes, tree depth 32, 4,096 visited syntax nodes, 256
observations and 131,072 total quoted code points. Unsupported formats or exceeded
limits return 422 without partial results. Empty supported sources return an empty
list. Bounded input and traversal constrain synchronous work; this is not a hard
wall-clock timeout. No worker, cache, schema change, or semantic binding is added.
The BSD-2-Clause CommonMark runtime dependency uses the existing managed version;
its parser types remain inside the document adapter, separate from domain values.

### OpenAPI JSON compilation

GET the exact source path with `/compilations/openapi-3.0-v1` appended for the
OpenAPI 3.0 JSON projection. Current resolver authorization and `no-store` responses
match Markdown compilation. Compiler `openapi-3.0-swagger-2.1.48-v1` emits
API_OPERATION, API_PARAMETER, API_RESPONSE and API_DESCRIPTION observations in
original source order. JSON observations add `jsonPointer` (RFC 6901); Markdown
observations retain their meaning with `jsonPointer=null`. Quotes include the raw
JSON value syntax, including string quotes/escapes, never normalized model text.
Reference observations cite the reference declaration; target definitions keep
separate original pointers. Neither implies that an operation was invoked.

Strict preflight rejects duplicate keys, trailing JSON values, non-3.0 versions,
external/file/relative references, missing targets and cyclic local references
before Swagger parsing. Supported references use literal `#/...` pointers with
`~0`/`~1` escapes; percent-encoded fragments and root-only `#` are not supported.
Even references inside extensions/examples are checked. Local schema recursion is
rejected in this bounded first release. Swagger resolution and flattening remain
disabled; no client, credentials or URL resolver is exposed.

Limits are 64 KiB input, JSON depth 32, 4,096 indexed values/reference visits,
128 references, reference-chain depth 16, 256 observations and 131,072 quoted code
points. Invalid descriptions or exceeded limits return 422 without partial output.
Swagger warnings are rejected; this is a deliberately conservative supported subset,
not a promise to accept every legal OpenAPI document. No new persistence or hard
wall-clock deadline is introduced. The Apache-2.0 Swagger Parser dependency is
isolated in the document adapter. Parser and Springdoc share Jakarta Swagger
core/models/annotations 2.2.53; non-Jakarta duplicates are excluded. Source
coordinates come from strict Jackson token
locations. Existing Markdown parser selection stays pinned to its versioned route.

### Documentary claim proposals

POST `/bff/v1/tenants/{tenantId}/sources/{sourceId}/revisions/{digest}/claim-proposals/{id}`
with an `extractionId`, zero-based `observationIndex`, registered `property`, `subject`,
`scope` and exactly one of `booleanValue` or `textValue`. The existing workbench
session/CSRF and current tenant-wide resolver authority apply. Commands are bounded
to 8 KiB; unknown fields and scalar type coercion are rejected. The source must be Markdown: selection is checked against its exact current compiler projection,
and the quotation is obtained and verified server-side, never supplied by callers.

Properties are `documentation.access.restoration.supported` (boolean) and
`documentation.access.restoration.requirement` (nonblank single-line text, at most
1,000 UTF-16 units). Subject and scope are exact trimmed labels of at most 256
UTF-16 units, without control characters. They confer no tenant or capability rights.
Optional `validFrom`/`validUntil` must both be absent (unknown validity), or form a
half-open increasing interval at microsecond precision, within years 0001–9999. No validity is inferred.

201 creates a proposal; 200 recovers the same command by the same proposer with
original recording time. Reusing its tenant-wide UUID with changed meaning or actor
returns 409. Unknown properties, wrong types, intervals and stale selections return
422. GET at the same path reads only that source's exact proposal after fresh access
checks; missing/foreign identities return 404. Responses use `no-store` and include
PROPOSED status, selected citation, compiler revision and `manual-claim-v1` binding rule.
No acceptance, account state, capability grant or outcome proof is created.

Apply the additive claim-proposal migration before enabling these endpoints.
Application rollback retains immutable proposals and their source/proposer foreign
keys; do not reverse shared migrations. OpenAPI binding and review are later slices.

### Ordered procedure proposals

POST `/bff/v1/tenants/{tenantId}/sources/{sourceId}/revisions/{digest}/procedure-proposals/{id}`
with a Markdown `extractionId`, ORDERED_LIST `observationIndex` and `title`. Optional
`operations` entries contain one-based `position`, OpenAPI `sourceId`, `digest`,
`extractionId` and API_OPERATION `observationIndex`, all within the URI's tenant.
Omitted/null operations means no links. Bindings are explicit; nothing is matched
or invoked automatically. Existing workbench sessions, CSRF and current resolver
authority apply before private source reads. Strict JSON is limited to 16 KiB.

Only flat ordered lists with one paragraph per item are supported. Nested, mixed,
empty, code-block and multi-paragraph items return 422 without partial results.
Step positions follow source order; quotes retain original markers, Unicode,
indentation and CRLF. Inline markup/links remain inert source text. Titles are
trimmed, nonblank, valid Unicode without control characters, up to 256 UTF-16 units.
Limits are 20 steps/bindings and 256 KiB aggregate UTF-8 quotations; NUL quotations
are rejected by this version's JSONB storage boundary. Existing compiler limits
still apply. Referenced API observations retain exact pointers and source citations.

201 creates a PROPOSED record; 200 recovers an exact same-actor retry. Changed
same-ID commands return 409; stale selections/unsupported inputs 422, oversized
commands 413, and missing/foreign sources or records 404. GET at the same URI
reauthorizes and verifies stored list, step and operation quotations against their
original sources. Responses use no-store and include compiler/binding provenance.
An operation link is documentation, not a capability grant or executable step.

Apply V20261029190000 before the API. Definitions and operation-source foreign keys
commit atomically; deferred checks prevent incomplete or later-added links and
mutation triggers reject UPDATE/DELETE. Application rollback retains source-linked
audit rows. No new dependency, worker, review transition or contract change is added.
See [ADR 0084](../docs/decisions/0084-propose-cited-ordered-procedures.md).

### Human documentary review

Apply additive V20261029200000 before enabling the review API. All routes below
use the existing workbench OIDC session, CSRF on POST, path-tenant actor lookup,
current tenant-wide RESOLVER authority, and no-store responses. Requester sessions
cannot access them. Self-review is permitted. Acceptance records a human decision;
it does not promote operational facts, register capabilities or authorize execution.

- GET `/bff/v1/tenants/{tenantId}/evidence-proposals/pending` lists pending
  claim/procedure metadata. Optional `limit` defaults to 20 and must be 1–50.
  `next` supplies `beforeRecordedAt`, `beforeKind`, `beforeId`; pass all
  three verbatim to continue. Ordering is recording time, kind (PROCEDURE before
  CLAIM), UUID, descending. Timestamps must retain exact microseconds. There is
  no snapshot or total-count guarantee; refresh the first page for new proposals.
- GET `/bff/v1/tenants/{tenantId}/evidence-proposals/{kind}/{id}/review`
  returns the exact verified cited `claim` or `procedure`, current `status`,
  `version`, and optional `review` receipt. Kinds are CLAIM and PROCEDURE.
- POST `/bff/v1/tenants/{tenantId}/evidence-proposals/{kind}/{id}/reviews/{commandId}`
  accepts exactly `extractionId`, `expectedVersion`, `decision`, `rationale`.
  Select the inspection's extraction ID and version 0. Decision is ACCEPTED or
  REJECTED; rationale is trimmed, nonblank, valid Unicode, at most 2000 UTF-16
  units (tab/line breaks permitted; other controls rejected). Strict JSON is
  bounded to 16 KiB. Clients cannot supply identity, proof or decision time.

201 records the single immutable terminal decision at version 1. 200 recovers an
exact same-actor command retry, preserving original proof/time even after renewed
authorization. Command IDs are tenant-wide across both kinds. Altered intent,
stale extraction/version or competing commands return 409; invalid input 422,
oversized input 413, missing/foreign/unauthorized target 404. Infrastructure
failures remain errors. Authority is rechecked with database time after lock waits,
including retries; expiry does not rewrite stored history.

Existing claim/procedure creation and GET envelopes retain PROPOSED as their
original creation state. Use review inspection for authoritative current status.
No edit, undo, repeated review or replacement linking is supplied. Reverting API
code retains immutable audit rows; never reverse a shared migration. No new
dependency or worker is required. See [ADR 0085](../docs/decisions/0085-record-terminal-documentary-review.md).

### Bounded documentary contradictions

Apply V20261029210000 before these workbench session endpoints. Current tenant-wide
resolver authority is required on every call, CSRF on POST, and responses use
no-store. Requester sessions cannot access them.

GET `/bff/v1/tenants/{tenantId}/documentary-contradictions/claims/{anchorId}`
assesses all accepted claims with the accepted anchor's exact property, subject and
scope. It returns algorithm, revision, accepted candidate/review identities, original
verified citations, and pair findings. Candidate UUID order is canonical; preserve
it verbatim for adjudication. The bound is 20 candidates; larger sets return 422
without partial results. Pending/rejected proposals and procedures are excluded.

Known overlapping validity and opposing SUPPORT booleans produce CONFLICT.
Touching half-open intervals are DISJOINT_VALIDITY. Equal known-overlap values are
COMPATIBLE. Unknown validity and differing textual requirements are UNDETERMINED;
text inequality cannot prove incompatibility. Different labels are NOT_COMPARABLE,
not proof of disjoint real-world scope. There is no inferred or wildcard scope.
The response state prioritizes CONFLICT, then UNDETERMINED, then
INSUFFICIENT_CANDIDATES or NO_DETECTED_CONFLICT; hasUndetermined stays visible even
when another pair conflicts. A result never establishes general consistency.

POST `/bff/v1/tenants/{tenantId}/documentary-contradictions/adjudications/{id}`
accepts strict JSON (16 KiB maximum): anchorId, expectedRevision, candidateIds,
choice, rationale and optional selectedClaimId. Use the complete ordered assessment
IDs and revision. Choice is PREFER_CLAIM (selectedClaimId must be a candidate) or
DEFER (selection absent/null). Rationale is trimmed, nonblank valid Unicode, at
most 2000 UTF-16 units; line breaks/tab are permitted, other controls rejected.
At least one conflicting or undetermined pair is required. Preference records
human judgment without changing accepted claims or resolving unknown validity.

201 records one terminal adjudication per exact group/revision; 200 recovers an
exact same-actor retry. Changed intent, another decision for that snapshot or new
accepted candidates return 409. Reassess and use a new command ID after stale
inputs. Invalid/oversized candidate sets return 422, body over 16 KiB returns 413,
and missing/foreign/unauthorized identities return 404. Infrastructure errors are
not translated into missing evidence.

GET `/bff/v1/tenants/{tenantId}/documentary-contradictions/adjudications/{id}`
retrieves original candidates, verified quotations, findings, decision, reviewer,
proof/time and a snapshot current flag. Later acceptance marks old decisions
historical; exact retries retain original attribution. Unsaved assessments are read
snapshots; saved decisions are immutable. Acceptance and adjudication serialize
on the group revision, with database-time authority rechecks after lock waits.
Deploy the additive migration before the API; rollback retains audit records.
No UI, bundles, new dependency or execution authority is introduced.
See [ADR 0086](../docs/decisions/0086-adjudicate-bounded-documentary-contradictions.md).

### Review-qualified evidence bundles

Deploy V20261029220000 before these workbench OIDC session endpoints. Current
tenant-wide resolver authority gates every call, including retries/history; POST
requires CSRF. Responses use no-store. Requester sessions are denied.

POST `/bff/v1/tenants/{tenantId}/evidence-bundles/{id}` takes strict JSON
(maximum 32 KiB) with required claims, procedures and claimSets arrays. Select at
most 20 proposals total. Claims/procedures are canonical ascending UUID lists.
Each claimSets entry contains anchorId, revision, candidateIds and optional
adjudicationId; order entries by anchor UUID. Use the exact complete candidate
array/revision from contradiction assessment, including a singleton. Each selected
claim must belong to exactly one supplied assessment. Procedure-only publication
uses empty claims and claimSets arrays.

All selected proposals must be accepted and their original citations verifiable.
All assessed claims must have known validity. Compatible or disjoint known sets
need no adjudication. Unknown validity or undetermined findings block publication,
even for a singleton or with human preference. A known conflicting set requires
its exact current PREFER_CLAIM adjudication, with only that preferred claim selected.
DEFER, stale inputs, omitted alternatives and conflicting selections cannot qualify.
Unknown accepted legacy alternatives can therefore block a key until future
correction/supersession semantics exist; preference cannot erase them.

201 creates the immutable publication; 200 recovers exact same-actor intent with
original proof/time/cutoff even after later acceptance. Changed/stale intent returns
409, invalid or unqualified input 422, oversized input 413 and unavailable private
selections 404. Reassess explicitly and choose a new bundle identity after changed
evidence; never change an uncertain original command. Infrastructure errors remain
errors, not missing evidence.

GET at the same URI returns publisher/proof, database recording time, SHA-256 and
parsed manifest. GET with `/canonical` appended returns exact canonical UTF-8 JSON
with its digest as ETag. The digest covers the canonical manifest, not the envelope.
Both reads reauthorize current access without requalifying historical content.

Schema ergon.dev/evidence-bundle/v1 records the assessment cutoff, selected
proposal flags, complete assessed alternatives, exact accepted review and
source/extraction/compiler references, full candidate/finding arrays and conflict
decision IDs. Source references include procedure-operation sources; immutable
proposal definitions retain their nested operation attribution. No implicit latest
lookup, contract mutation, promotion or execution authority follows publication.
Canonical payloads are bounded to one MiB, with at most 420 proposal/source refs.

Publication identity and deterministically ordered claim-key locks serialize
first publication/replay and acceptance. Authority is rechecked after lock waits;
SQL repeats qualification and membership checks. Tenant-safe relational ledgers
commit atomically and cannot be amended later. Application rollback preserves
immutable manifests and audit links. No worker or new dependency is introduced.
See [ADR 0087](../docs/decisions/0087-publish-review-qualified-evidence-bundles.md).


## Documentary contract requirements

M06-PR12 adds strict `ergon.dev/resolution-contract/v1alpha2` publication/validation
and exact revision reads on the existing internal contract endpoints. Its
`documentaryEvidence` entries pin tenant-owned historical bundle UUIDs and canonical
manifest SHA-256 values independently of operational facts. Standalone validation
is structural; publication verifies exact historical references and writes complete
immutable links atomically. No browser authoring or new authority policy is added.

Apply V20261030100000 before new writers and deploy compatible readers before
publishing this version. Existing v1alpha1 shapes/meaning remain unchanged.
M06-PR13 adds version-fenced internal case documentary attachment and deterministic
readiness at the event's database recording time. Missing/unsuitable documentary
inputs prevent planning and runs; satisfaction still needs operational facts,
policy, approvals, capability authorization and outcome proof. The database
independently checks exact case-prefix attachments and suitability at run admission. Rollback retains immutable history and
requires readers that understand new-schema records; use forward migrations.
See [contracts](../docs/contracts.md#v1alpha2-documentary-requirements) and
[ADR 0088](../docs/decisions/0088-declare-exact-documentary-contract-requirements.md).


## Case documentary attachments

Apply V20261030120000 before enabling
`POST /internal/v1/tenants/{tenantId}/cases/{caseId}/documentary-bundles` with quoted
`If-Match` and exact `bundleId`/`sha256`. Each open case accepts at most 20 immutable
distinct attachments; these are not connector observations or source timeline entries.
The authoritative attachment time is the case event database recording time.

Current and protected run readiness expose missing/unsuitable/satisfied documentary
requirements and exact attachment coordinates. Run reads retain their fixed case
prefix; later pins and evidence cannot rewrite it. Historical validity is deliberately
frozen at attachment, not present freshness. PR12's temporary refusals are replaced
by documentary-aware application and database admission. No browser authoring,
authority policy, contract promotion or proof shortcut is introduced. See
[ADR 0089](../docs/decisions/0089-consume-case-pinned-documentary-evidence.md).

## Complete compiler acceptance

M06-PR14 adds licensed real-source fixtures, offline digest/citation checks and
`SourceToContractIntegrationTest`, which uses actual Spring use cases and
PostgreSQL from source registration through case-pinned documentary readiness.
It requires Docker; compilation or a skip does not establish a database pass.
See [operations](../docs/operations/evidence-compiler.md) and
[M06 acceptance](../docs/roadmap/m06-acceptance.md) for the supported sequence and
separate UI/deployment verification boundaries. No product behavior is added.
