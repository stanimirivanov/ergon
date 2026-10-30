# ADR 0089: Consume case-pinned documentary evidence

- Status: Accepted
- Date: 2026-10-30
- Milestone: M06 - Evidence Compiler
- Deciders: Ergon maintainers
- Supersedes: none
- Superseded by: none

## Context

M06-PR13 consumes the exact documentary requirements declared under ADR 0088.
PR12 deliberately refused documentary readiness/run starts until a case-owned
attachment and deterministic consumption boundary existed. Documents must remain
separate from connector facts, human authority and post-action outcome proof.

The user chose attachment-time validity: historical readiness must retain the
same qualification rather than change with the wall clock. Application business
time is not the authoritative database attachment instant and cannot choose which
claims qualify after a lock wait.

## Decision

Append DocumentaryBundlePinned schema-version-one events containing exact bundle
UUID, canonical-manifest SHA-256 and server-authored business occurrence time.
Expose an internal case command with mandatory quoted If-Match at
/internal/v1/tenants/{tenantId}/cases/{caseId}/documentary-bundles. It validates the
exact tenant-owned historical bundle through the evidence application port, then
uses the existing optimistic aggregate/event/projection commit. Missing/foreign
or digest-mismatched references share an unavailable response. No browser endpoint,
new human role, latest selection or execution permission is introduced.

An open case accepts at most 20 distinct immutable attachments, once per bundle
identity. Replacement/retraction is outside this slice. An attachment is metadata,
never a connector observation or account-access binding; it adds no source timeline
entry. Record unsuitable material rather than hide the failed qualification.
Readiness explains that failure. Closing a case still requires existing accepted
post-action proof; attachment never changes that policy.

Project exact tenant/case/event/bundle/digest identities, stream version, business
time and database event recording time atomically. Composite foreign keys prevent
cross-tenant/dangling references. Admission checks event payload and both timestamps,
serializes the per-case bound, and forbids closed-case writes. A deferred event
constraint requires its projection; UPDATE/DELETE and later replacement fail.
The database recording instant is the immutable attachment validity cutoff.

Match every contract requirement by exact bundle ID and digest in the selected
case prefix. Return MISSING, UNSUITABLE or SATISFIED with pin version/cutoff and
an explicit unsuitability reason. A mismatched digest, manifest assessed after
attachment, unknown selected-claim validity, or any selected claim outside its
half-open [validFrom, validUntil) interval makes the requirement unsuitable.
A procedure-only bundle imposes no claim interval. Context alternatives are retained
by the historical manifest but do not become selected temporal requirements.

All selected claims must apply at the same attachment instant. A publication that
selects disjoint temporal alternatives can therefore be review-qualified history
yet unsuitable as a whole for a case. Qualification is documentary suitability,
not a new mapping from subject/scope labels to live assets or operational facts.
No global consistency or present freshness is inferred.

Missing documentary input precedes unsuitable documentary input; both precede
operational missing evidence/applicability. After documentary satisfaction, the
existing operational evaluator and planning policy still apply independently.
Expose WAITING_FOR_DOCUMENTARY_EVIDENCE and UNSUITABLE_DOCUMENTARY_EVIDENCE in
readiness; include per-requirement results only for documentary contracts so
operational-only response shapes remain unchanged.

Current-case readiness uses one immutable case prefix. Protected run readiness
first rechecks supervisor/resolver access and then reads only attachments at or
before the run's fixed case version. The evidence adapter joins case pin, bundle
and selected claim application ports, never an implicit latest assessment or a
cross-module table API. Bundle/claim corruption remains an operator error rather
than missing evidence. No remote work occurs in transactions.

Remove PR12's temporary unsupported states/refusals. The run-plan boundary repeats
exact documentary evaluation and excludes later pins even when an injected plan
claims READY. Replace the database unconditional refusal with case-locked admission
that verifies current version, pinned contract, complete prefix attachments and
attachment-time suitability independently of the application. Stable explicit
SQLSTATEs map only missing/unsuitable documentary input; other infrastructure
failures propagate. This does not bypass capability availability, strengthened
risk, human approval, authorization consumption, connector invocation or proof.

Protected pinned-readiness responses use evaluator v2 for documentary contracts,
v1 for unchanged operational-only evaluations. This is current evaluator replay
of fixed inputs, not stored historical planner reasoning. Later acceptance,
adjudication, current time and post-prefix attachments do not rewrite the original
manifest or attachment instant.

## Alternatives considered

### Evaluate claim validity at every readiness/run check

- Benefits: present-time expiration.
- Costs and risks: time-sensitive readiness needs a durable evaluation cutoff and
  a separately designed replay/expiry policy.
- Reason not selected: the user explicitly chose attachment-time historical validity.

### Turn documentary acceptance into an account fact or approval

- Benefits: reuses existing fact names and readiness paths.
- Costs and risks: documents could bypass live evidence, authority or outcome proof.
- Reason not selected: keep documentary selection, operational observations and
  execution/proof prerequisites independent.

### Requalify against later accepted claims or select latest bundles

- Benefits: apparent automatic freshness.
- Costs and risks: rewrites historical meaning and leaks post-prefix material.
- Reason not selected: exact immutable manifest and event-position references.

## Consequences

### Positive

A contract consumes attributable case-owned documentary input reproducibly. Missing
and unsuitable prerequisites are explicit without granting action authority.

### Negative

Historical suitability remains satisfied after wall-clock expiry if the claims
were valid when attached. This is intentional and is not a continuing-freshness
claim. Invalid/expired attachments cannot be replaced under the same bundle identity;
a new published bundle and a new contract revision/case selection model may be
needed. Twenty unrelated attachments can exhaust this bounded case capability.

## Compatibility and migration

Apply V20261030120000 before new event writers. It adds event discrimination and
immutable projections, then replaces PR12's run trigger with documentary-aware
admission. No old events, definitions, bundles or runs are rewritten/backfilled.
Existing v1alpha1 runs retain their prefix, evaluator v1 and policy meaning.

Deploy event/schema readers before enabling attachment writers and documentary
run consumers. Older readers reject new case events and cannot safely replay
those streams; an application rollback needs compatible readers or forward
recovery. Retain the expanded database and immutable history. Never down-migrate
shared migrations. The retired PR12 guards are fully replaced in this slice.

## Security and operations

The endpoint retains the existing internal case command trust/development boundary;
it is not a public/production-safe browser API. No request timestamp can choose
validity. Current protected run access is checked before private attachments.
Case stream locks and version fences serialize writes; prefix filters and immutable
source identities bound reads. No dependency, external OSS code, queue, worker,
cache or new authorization mechanism is added; reuse existing event, immutable
reference and fixed-prefix patterns.

## Validation

Cover exact identity/digest, temporal edges and unknown bounds, procedure-only
history, unchanged operational requirements, duplicate/bounded/closed-case replay,
old/new event codec round trips, If-Match, safe failures, current/protected prefix
selection and retired callbacks/access checks. PostgreSQL coverage verifies atomic
projection, immutable cutoff, expired material, concurrency, independent run
admission, historical stability and fresh/supported-upgrade migrations. Run focused
checks, repository policy and full verification; Docker skips remain unverified.
