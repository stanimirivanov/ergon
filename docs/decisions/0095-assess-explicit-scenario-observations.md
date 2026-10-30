# ADR 0095: Assess explicit scenario observations

- Status: Accepted
- Date: 2026-10-30
- Milestone: M07 - Simulation and Promotion
- Deciders: Ergon maintainers
- Supersedes: None
- Superseded by: None

## Context

Replay observations alone cannot establish that a scenario met independently
specified expectations. Missing execution, closure or escalation data must not
silently become passing safety checks. Existing replay services intentionally
observe only bounded portions of runtime behavior.

## Decision

Introduce a framework-free typed assertion catalog and evaluator. Each assertion
has a stable ID, an explicit required flag and a closed typed check. Catalogs are
bounded, IDs are unique, and at least one required assertion is mandatory.

Keep observed outcome, calls, policy, grounding, proof and escalation distinct
from authored expectations. Available absence, missing data, unsupported behavior
and observation errors are separate states. Return one ordered PASS, FAIL, ERROR
or UNSUPPORTED result with a stable explanation for every assertion. A required
non-pass prevents the required-check summary from passing; optional results remain
visible. This summary is not promotion authority.

Compare observations directly without invoking the runtime algorithm under test.
Check proof condition and available provenance independently: successful receipt,
post-run source, bounded assessed stream and post-action observation time. Check
reported verified closure conditionally against that proof. Escalation assertions
compare the expected reason and terminal observation, not runtime authorization.

## Alternatives considered

### Derive expectations by replaying the same runtime algorithm

- Benefits: Less fixture authoring.
- Costs and risks: The same defect can produce both expected and actual values.
- Reason not selected: Expectations must be independently authored.

### Treat missing observations as empty or skip unsupported checks

- Benefits: Existing partial traces appear easier to evaluate.
- Costs and risks: False passes for forbidden invocation, proof and closure.
- Reason not selected: Required unknown behavior must fail closed.

## Consequences

### Positive

Assertions are deterministic, bounded and testable with deliberately corrupted
traces. No dependency, arbitrary script engine or subjective model judge is added.

### Negative

The evaluator cannot establish authenticity or completeness of its supplied trace.
A comparison does not certify production authorization or documentary validity.
Full-history latest-evidence selection remains a trace producer responsibility.

### Neutral or follow-up

PR8 owns execution/report orchestration and material decoding. Later required-suite
gates choose mandatory assertions. Existing runners do not yet establish verified
closure or escalation; later projections must preserve those unsupported fields.
No temporary runtime behavior or model extension point is introduced here.

## Compatibility and migration

The domain API is additive. Existing fixture manifests, opaque assertion material,
replay evaluator identities, storage and production contracts are unchanged. A
future persisted assertion schema must explicitly version the catalog semantics.
Revert this API with its eventual consumers; no database migration is required.

## Security and operations

Expectations cannot execute code, invoke a capability or create evidence. Lists
are bounded and frozen. An empty observed allowlist does not represent missing
observation, and passing a property never grants authority. Keep fixture inputs,
observed trace and expected results separate when wiring later orchestration.

## Validation

Use independent domain matrices for forbidden invocation, wrong capabilities,
policy and grounding, false verified closure, stale/wrong proof and inconsistent
escalation. Verify missing/error/unsupported propagation, required/optional summary,
identity ordering, bounds and mutation resistance. Run repository policy, static
checks and full Maven verification; report Docker-backed gaps explicitly.
