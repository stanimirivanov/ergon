# ADR 0099: Record authorized contract promotions

- Status: Accepted
- Date: 2026-10-30
- Milestone: M07 - Simulation and Promotion
- Deciders: Ergon maintainers
- Supersedes: None
- Superseded by: None

## Context

PR9 proves eligibility against exact immutable suites and reports, but does not
select the governing suite or authorize promotion. Allowing a caller to select
any passing suite would permit a weaker gate. Promotion must preserve the reviewed
inputs and actor while rejecting stale decisions and expired authority.

## Decision

Use the explicitly approved dedicated tenant-wide CONTRACT_PROMOTER entitlement.
A trusted internal machine attests a registered actor, source provider/reference
and explicit expiry. The database stamps attestation time; validity is half-open
and at most 24 hours. RESOLVER authority grants no promotion entitlement. Evidence
is immutable; this slice supplies expiry and renewal, not revocation.

A separate confirmed command binds each tenant/contract key to an exact registered
suite revision/digest. Commands include expected binding version and rationale.
The first expected version is zero; each new command appends the next version.
The highest version governs subsequent fresh promotions. A suite already binds an
exact contract revision and policy, so changing contract revision requires a new
binding. This binding is explicitly approved by the maintainer.

Promotion commands include the expected binding version, exact suite, contract,
policy, selected report IDs/digests and rationale. Within one transaction, lock
command identity then tenant/contract key, resolve current dedicated authority,
check the governing binding, re-assess PR9 eligibility and append an immutable
receipt plus report links. SQL repeats admission checks and stamps admission time
after lock acquisition. Tenant foreign keys and a deferred completeness constraint
protect the atomic receipt/evidence set. No replay or external calls occur in SQL.

Exact same-actor command retries require current authority and recover original
attribution and timestamps, even after a newer binding or renewed attestation.
They do not re-promote or assert current admission. Changed intent or actor
conflicts. Report ordering is normalized at the HTTP document boundary. Distinct
commands may record distinct promotions of the same revision.

## Alternatives considered

### Inherit RESOLVER authority

- Benefits: Reuses existing operational permissions.
- Costs and risks: Grants governance power implicitly to operational resolvers.
- Reason not selected: The maintainer explicitly selected a dedicated entitlement.

### Let each promotion choose its required suite

- Benefits: Avoids a governing binding ledger.
- Costs and risks: Permits selecting a weaker passing gate.
- Reason not selected: The maintainer selected a versioned required-suite binding.

## Consequences

### Positive

Promotion is attributable, tenant scoped, atomic and tied to complete exact
qualification evidence. Concurrent binding changes cannot silently authorize
stale reviewed commands. Existing evaluation code is reused without dependencies.

### Negative

Trusted infrastructure must protect attestation routes separately from human
command callers. Expiry is the only withdrawal mechanism in this slice. A new
contract revision needs a separately confirmed suite binding before promotion.

### Neutral or follow-up

Publication and case pinning remain distinct. PR11 owns runtime promotion
admission; PR12 and later own browser workflows. A historical receipt is not a
current execution permit. No automatic grant, binding, promotion or backfill occurs.

## Compatibility and migration

Apply additive V20261030180229 before enabling these writers. It adds authority,
binding, promotion and evidence-link tables and enforcement triggers, without
rewriting existing rows. Deploy compatible readers/writers together. On rollback,
disable new commands and revert application code while retaining immutable records
and shared migration history; never delete audit rows to undo a promotion.

## Security and operations

The API retains the trusted internal machine boundary. Actor headers identify
intent, not authentication. Never expose attestation endpoints to browsers or
untrusted callers. Source provenance is recorded rather than independently verified
against an identity provider. Requests are strict versioned JSON bounded to 128 KiB;
unknown fields, invalid Unicode and unsupported schemas are refused. Authority
expiry requires microsecond precision. Rationale is bounded to 2,000 characters.
Database failures remain failures, not eligibility success. Responses use no-store.

## Validation

Domain tests cover validity and provenance bounds. Application/HTTP tests cover
confirmed binding, exact retry, denial/expiry, stale versions, changed intent,
missing evidence, forged results and tenant isolation. PostgreSQL tests cover
upgrade and empty migrations, retained attribution, concurrent retry, lock-wait
gate/expiry races, immutable storage and direct admission refusal. Docker-dependent
checks must run in CI; skipped local tests are not passing evidence.
