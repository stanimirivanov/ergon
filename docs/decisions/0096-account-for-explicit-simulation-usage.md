# ADR 0096: Account for explicit simulation usage

- Status: Accepted
- Date: 2026-10-30
- Milestone: M07 - Simulation and Promotion
- Deciders: Ergon maintainers
- Supersedes: None
- Superseded by: None

## Context

Simulation budgets must include unsuccessful attempts and retries without counting
terminal receipt replay as another effect. Existing model responses do not contain
trusted token or timing measurements. Unknown data cannot satisfy a required budget
by silently becoming zero, and live pricing would prevent reproducibility.

## Decision

Use explicit bounded usage, exact pricing artifact references and nonnegative
integer currency nanos per call/input token/output token. Each budget names one
currency; no conversion or implicit rounding is available. Sum exact products with
BigInteger intermediates and report an error when totals exceed signed 64-bit range.

Track cost and simulated attempt-duration limits independently with inclusive
comparison. Missing quantities, rates or durations remain unknown. Unconfigured
limits retain their measurements but do not count as passed checks. At least one
configured limit and PASS for every configured limit are required for the summary.
Known currency mismatch and overflow cannot be hidden by missing usage.

Project capability gateway attempts without deduplicating their operation keys;
failed and uncertain calls still consume a call and measured duration. Do not charge
a receipt itself. Bind model measurements to the fixture, configuration, script and
response digest. Account for rejected responses too; never estimate token counts.

## Alternatives considered

### Floating-point cost or rounded decimal rates

- Benefits: Familiar display units.
- Costs and risks: Boundary ambiguity and silently lost precision.
- Reason not selected: Integer currency nanos provide explicit exact limits.

### Live provider usage and latest pricing lookup

- Benefits: Current billing information.
- Costs and risks: External dependencies and unreproducible historical totals.
- Reason not selected: This milestone evaluates pinned scripted measurements.

## Consequences

### Positive

Retry charging, unknown data and exact boundary tests are deterministic with no new
dependency. Simulated duration is clearly separate from evaluator wall-clock time.

### Negative

Sub-nano per-unit prices are unsupported. Summed attempt duration is not end-to-end
latency, and supplied measurements/pricing still require verified material provenance.

### Neutral or follow-up

PR8 owns verified budget-material decoding and execution/report orchestration.
Later gates decide which limits must be configured. This API does not certify
production billing or provider performance.

## Compatibility and migration

Additive domain/accounting APIs; existing fixture and replay evaluator meanings are
unchanged. Future persisted budget material must explicitly version these units
and semantics. No database, endpoint or runtime migration is needed. Revert with
eventual consumers; stored fixture bytes remain immutable.

## Security and operations

No provider calls, credentials, clocks or production writes are introduced.
Collections and numeric inputs are bounded; exact identities prevent implicit
pricing revision selection. Passing configured budgets is not execution or
promotion authority, and missing limits cannot yield a vacuous passing summary.

## Validation

Test equality/exceedance, unknown versus zero, exact token rates, currency and
pricing identity mismatches, product/sum overflow, retries and terminal replay.
Test model measurement binding and missing usage. Retain repository policy, static
checks and full Maven verification; report Docker-backed gaps explicitly.
