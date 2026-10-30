# ADR 0098: Gate exact evaluation suites

- Status: Accepted
- Date: 2026-10-30
- Milestone: M07 - Simulation and Promotion
- Deciders: Ergon maintainers
- Supersedes: None
- Superseded by: None

## Context

An individually passing report does not prove that every required scenario was
run against the intended contract, evaluator, assertions and budgets. Promotion
needs explicit reviewed requirements and complete evidence, without selecting a
convenient latest passing report or trusting caller-supplied results.

## Decision

Register immutable tenant-scoped gate policies and suites with content digests.
Policies list exact compatible evaluator artifacts and explicitly require or do
not require cost and simulated-latency budgets. Suites pin one exact contract
artifact, a policy revision/digest and a nonempty set of exact registered fixture
revisions/digests. Each scenario names a nonempty set of gate-required assertion
IDs. Fixture digests bind runtime policy, scripts, adapter semantics, model
configuration, assertions and pricing/budget materials already verified by PR8.

At suite registration, verify each fixture's contract artifact and compare its
decoded contract meaning with the exact published tenant revision. Raw YAML hashes
remain artifact identities; normalized published JSON meaning is compared separately
because PostgreSQL JSONB does not preserve source spelling. Reject unknown assertion
IDs. Registration grants no authority and does not require already passing reports.

Assess caller-selected report IDs and semantic digests against every suite member.
Reject missing, extra, mismatched and incompatible evidence. Require the report's
own derived pass flag, PASS on every gate-required assertion, and configured PASS
budget dimensions required by policy. Optional report assertions can become required
by a suite. No score averaging, unsupported-result bypass, latest lookup or fallback
is supplied. Return exact assessment coordinates and deterministic refusal reasons.

## Alternatives considered

### Use the latest passing report for a contract

- Benefits: Smaller caller request.
- Costs and risks: Cherry-picks scenarios and hides incompatible inputs.
- Reason not selected: Explicit complete evidence is independently reviewable.

### Activate policy and record promotion in this slice

- Benefits: Immediately changes production admission.
- Costs and risks: Mixes eligibility with authority and rollout policy.
- Reason not selected: PR10 and PR11 own atomic promotion and runtime enforcement.

## Consequences

### Positive

Pure domain gates are independently testable. Existing reports, fixtures and
contract codecs provide provenance without new dependencies or replay rules.

### Negative

Suites are limited to 50 scenarios; policies to 20 evaluator artifacts. Every
scenario must name 1–100 checks. Fixture contract material must share one exact
artifact, even when alternative YAML spellings decode to equivalent meaning.

### Neutral or follow-up

Eligibility is a read-only assessment, never a durable approval. There is no active
policy pointer or automatic invalidation here. Later promotion commands must select
and atomically revalidate the governing exact suite/policy/evidence under authority.

## Compatibility and migration

Apply additive V20261030171119 before deploying the new internal API. New tables
contain gate definitions and tenant-safe fixture links; no historical rows are
scanned or rewritten. Concurrent exact inserts arbitrate all unique constraints,
then retain original attribution; altered definitions conflict. Revert application
code while retaining immutable records and shared migration history.

Existing fixture, report, contract publication and runtime admission semantics are
unchanged. New suite/policy schemas are explicit v1 contracts; changed requirements
need new immutable revisions. No existing suite silently acquires new requirements.

## Security and operations

These routes retain the trusted internal machine boundary and registered tenant
actor attribution, not browser entitlement or promotion authority. Tenant predicates
and database foreign keys protect reference ownership. Input is bounded to 128 KiB;
no external material is fetched. Missing private reports produce missing evidence,
while storage corruption/infrastructure failures are not eligibility successes.
Definitions and links remain append-only. There is no new worker or runtime write.

## Validation

Test empty/duplicate suites, complete versus partial evidence, wrong contract and
policy, report digest mismatch, evaluator changes, unsupported checks and required
budget refusal. Exercise strict transport, published-meaning comparison, immutable
retry and tenant isolation. PostgreSQL tests cover empty/upgrade migrations and
concurrent registration; report skipped Docker tests as unverified.
