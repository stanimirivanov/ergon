# ADR 0097: Record reproducible evaluation reports

- Status: Accepted
- Date: 2026-10-30
- Milestone: M07 - Simulation and Promotion
- Deciders: Ergon maintainers
- Supersedes: None
- Superseded by: None

## Context

Registered fixtures, replay adapters, assertions and budgets need one execution
boundary that records reproducible results without trusting a caller-supplied pass
flag. Retries and concurrent submissions must preserve immutable attribution.

## Decision

Execute a complete registered package synchronously through the existing bounded,
offline planning, capability or model evaluator. Decode versioned assertion and
budget materials only after package verification. Nested pricing has exact content
hashes; model measurements bind exact configuration, script and response identities.
Expectations remain independent of observations. Always add a required outcome
check against the manifest. Unsupported closure or escalation remains unsupported;
accepted proof does not manufacture a verified closure.

Record canonical report/v1 JSON with fixture digest, exact evaluator/assertion/budget
references, observed fields, ordered check results, budget measurements and a derived
pass flag. Sort object keys recursively, retain array order, and hash compact UTF-8
bytes. Command identity, submitting actor and database recording time belong to the
recording envelope and cannot change the semantic report digest.

Evaluate outside transactions; atomically insert only complete reports in a short
transaction with tenant-owned fixture and actor checks. Tenant and command UUID
identify one immutable record. Exact report retries retain original attribution;
altered fixture or semantic report conflicts. Malformed materials and execution
errors create no report. Completed failed checks are durable failed reports.

## Alternatives considered

### Accept reports supplied by clients

- Benefits: Minimal server orchestration.
- Costs and risks: A caller could substitute observations or a passing result.
- Reason not selected: Reports must derive from verified registered inputs.

### Background execution queue and mutable run status

- Benefits: Supports long-running jobs.
- Costs and risks: Adds scheduling, recovery and partial-result semantics.
- Reason not selected: Current offline evaluators already have bounded work.

## Consequences

### Positive

One execution path composes existing rules without new dependencies or live calls.
Content-addressed results remain reproducible across fresh command identities.

### Negative

Retries repeat bounded evaluation before comparing the stored result. Unsupported
runtime stages cannot pass required checks. A report is limited to one MiB.

### Neutral or follow-up

Promotion is a later slice. A passing report grants no runtime authority and does
not certify live-provider performance, privacy or production readiness.

## Compatibility and migration

Additive internal routes, report table and versioned material schemas. Previously
registered opaque materials remain retrievable but need a new immutable fixture
revision using supported schemas before execution. Existing replay APIs retain
their meanings. Future changes to report semantics require explicit versioning.

Apply V20261030153650 before API deployment. The fixture composite unique index
scans existing rows and requires a deployment lock window; existing definitions
are not rewritten. Reverting application code retains the additive schema and
immutable audit records. Never reverse an applied migration.

## Security and operations

The endpoints retain the trusted internal machine boundary, tenant predicates and
registered actor checks; they are not browser-authorized endpoints. Fixtures and
reports are private curated material. No evaluator calls production connectors,
models or writes runtime facts. Database constraints verify envelope hashes and
references; immutable triggers prevent update/delete. Reads verify canonical
content and derived pass consistency. Infrastructure errors remain errors.

## Validation

Focused tests cover exact report reproduction, failed/unsupported/error checks,
mandatory outcome, model measurement binding, terminal replay charging, strict
material parsing and HTTP behavior. PostgreSQL tests cover upgrade and empty
migration chains, concurrent retries, tenant isolation, foreign keys, digest checks
and immutable storage. Run repository policy, static checks and full Maven
verification; report Docker-dependent tests as unverified when Docker is unavailable.
