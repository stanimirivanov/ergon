# ADR 0100: Enforce current promotion on run admission

- Status: Accepted
- Date: 2026-10-30
- Milestone: M07 - Simulation and Promotion
- Deciders: Ergon maintainers
- Supersedes: None
- Superseded by: None

## Context

PR10 records authorized promotion under a versioned governing suite. Publication,
pinning and passing evaluation alone still allow run creation. PR11 must connect
promotion to every fresh start without rewriting existing execution context or
requiring a new promotion just to recover an already recorded retry.

## Decision

Use the maintainer-approved opt-in tenant allowlist. The empty default preserves
legacy admission. A configured tenant must have a promotion of its exact pinned
contract revision under the current required-suite binding version. Any binding
change, including reconfirmation of the same suite, requires a fresh promotion
before another run can start. This invalidation rule was explicitly approved.

Initial run creation and the shared runtime retry service call one required
application admission port before identity allocation and persistence. Internal
and supervised browser retries use that same runtime service. Historical retry
recovery returns before admission, and existing run queries and execution do not
recheck promotion. Existing authorization, control, evidence and proof gates remain.

The contracts capability owns a transaction-bound query that acquires PR10's
per-tenant/contract advisory lock and looks for an exact revision receipt under
the maximum binding version. The caller retains that lock through run commit.
Binding changes and promotions share it, so a waiting check observes committed
current state and a successful check cannot be invalidated before run persistence.
Use the existing READ COMMITTED transaction boundary. Infrastructure errors fail
closed; never convert them to legacy admission.

PR10 already verified the complete immutable suite, policy, evaluator and report
coordinates, enforced their tenant foreign keys and recorded atomic evidence
links. Their meaning cannot change in place. Admission therefore consumes that
qualification rather than replaying simulations or rechecking the original
promoter's now possibly expired entitlement. Changing evaluator compatibility
requires a new policy/suite and governing binding, invalidating fresh admission.

## Alternatives considered

### Enforce globally by default

- Benefits: No deployment can accidentally omit a tenant from enforcement.
- Costs and risks: Immediately rejects existing tenants without prepared promotion evidence.
- Reason not selected: The maintainer selected an explicit tenant allowlist.

### Accept any historical promotion

- Benefits: Policy changes cannot interrupt new starts.
- Costs and risks: Old receipts bypass the current required suite and evaluator policy.
- Reason not selected: The maintainer requires the current binding version.

## Consequences

### Positive

All fresh start paths consume the same rule. Tenant rollout is explicit, old case
pins are never silently upgraded, and existing runs continue with their pinned
context. Concurrency uses the existing governance lock without a new dependency.

### Negative

Configuration must be consistent across every serving instance. A mixed rollout
can preserve legacy admission on an unconfigured instance. Changing a binding
creates an intentional admission pause until a new promotion qualifies. A lock is
held for the short run transaction; this adds contention per tenant/contract key.

### Neutral or follow-up

Retry review still reports planning eligibility; confirmation performs final
promotion admission. No new browser UI, admission audit ledger, promotion endpoint,
contract selection or repinning is added. Direct database writes bypass the
application allowlist and remain restricted to trusted database operators.

## Compatibility and migration

This is behavior-adjacent: configured tenants can now receive a 409 promotion
required response on previously admissible starts. Existing run payloads and
persisted schemas are unchanged. Deploy PR10's migration and writers first, bind
and promote the intended revisions, then enable the same allowlist everywhere.
Malformed allowlist entries fail startup. No new migration or backfill is needed.

Removing a tenant or reverting enforcement code reopens legacy admission. That
requires an explicit operator decision, not an automatic failure fallback. Prefer
rolling forward with fresh promotion evidence; preserve all historical records.

## Security and operations

The allowlist is trusted deployment configuration, never a request parameter or
actor header. The shared 409 problem identifies current promotion as the missing
prerequisite without disclosing actors or report identities, and uses no-store.
Other runtime authorization and identity requirements remain mandatory. Database
credentials must not be exposed to callers; enforcement is at the application
boundary. Drain affected admission traffic during an inconsistent configuration
rollout. See the contract-promotion operations guide.

## Validation

Test empty/other-tenant scope, malformed configuration, exact contract identity,
absence, stale versions, failure propagation, initial-start refusal and historical
retry recovery. PostgreSQL tests exercise tenant/revision isolation, re-promotion,
both gate/admission lock orders, concurrent promotion and admission, and rollback
of refused internal/supervised retry successors. Run Docker-backed checks in CI;
a local skip is not passing evidence.
