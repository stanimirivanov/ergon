# ADR 0093: Simulate capability and proof

- Status: Accepted
- Date: 2026-10-30
- Milestone: M07 - Simulation and Promotion
- Deciders: Ergon maintainers
- Supersedes: None
- Superseded by: None

## Context

Pinned planning replay cannot observe connector uncertainty, idempotent receipt
replay or independent outcome proof. Simulation must exercise production rules
without granting production authority or contacting a live connector.

## Decision

Add the explicit evaluator `ergon.simulation/capability-proof/v1` and one bounded
connector program with schema `ergon.simulation/capability-scenario/v1`. Preserve
the existing readiness evaluator unchanged. Reuse production approval, grant and
consumption domain rules, the invocation application service and proof evaluator.
Use a fresh isolated adapter with scripted outcomes, clock, ticker and stores for
each replay. Derive simulation identities deterministically from the fixture digest.

Explicit repeated invocations reuse the consumed operation key. Unknown outcomes
record an attempt without inventing a terminal receipt; terminal replay performs
no new connector call. Only successful receipts reach proof assessment. Accepted
proof never finalizes a case. Curated authority and evidence are simulation inputs,
not real attestations. Approval-free execution remains explicitly unsupported.

## Alternatives considered

### Duplicate the execution algorithm

- Benefits: A smaller standalone runner.
- Costs and risks: Simulation can diverge from runtime receipt and uncertainty rules.
- Reason not selected: Existing invocation ports already support isolation.

### Use live adapters and production admission

- Benefits: Broader runtime coverage.
- Costs and risks: Side effects, mutable state and real authorization requirements.
- Reason not selected: This evaluator is bounded deterministic replay.

## Consequences

### Positive

Production execution and proof rules receive deterministic offline coverage with
no new dependencies, persistence or live credentials.

### Negative

The new material schema is a compatibility commitment. Sequential in-memory
transactions do not validate database rollback, concurrency or external delivery.

### Neutral or follow-up

Model proposals, scoring, budgets, durable reports and promotion remain later
slices. This runner does not orchestrate successor runs or production finalization.

## Compatibility and migration

The new application service is additive. No database, event or production API
migration is needed. Changed evaluator semantics require a new identifier. Revert
the runner with its consumers; stored package registration remains unchanged.

## Security and operations

Verified packages supply all inputs. Strict material decoding, bounded counts and
explicit unsupported results prevent silent approximation. Live gateways and
production repositories are never supplied to the invocation service. Fixture
identities and authority values must never be used for production execution.

## Validation

Exercise blocked gates, success, failure, receipt-free uncertainty, explicit retry,
terminal replay and stale or incorrect proof. Repeat identical packages and retain
the readiness evaluator tests. Run repository policy, formatting, static analysis
and full Maven verification; report unavailable Docker tests explicitly.
