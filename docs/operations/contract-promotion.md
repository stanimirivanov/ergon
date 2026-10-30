# Contract promotion enforcement

## TL;DR

- Promotion enforcement is opt-in through `ERGON_CONTRACT_PROMOTION_ENFORCED_TENANTS`.
- Enforced tenants need an exact pinned revision promotion under the current
  required-suite binding version for every fresh initial run or retry successor.
- Deploy PR10 first, prepare promotions, then configure every serving instance
  consistently. Existing runs and exact retry recovery retain their context.
- A binding change pauses new admission until a fresh promotion exists. Removing
  enforcement reopens legacy admission and requires an explicit operator decision.

## Configure the tenant scope

Set `ergon.contract-promotion.enforced-tenants`, exposed by
`ERGON_CONTRACT_PROMOTION_ENFORCED_TENANTS`, to a comma-separated list of canonical
UUIDs. Whitespace around entries is accepted; blank configuration means no tenants.
Invalid UUIDs, shortened UUID spellings and empty entries in a nonblank list fail
startup. The setting is trusted deployment configuration, not an API parameter.

Use the same value on every instance serving a tenant. Configuration is loaded at
startup; there is no hot reload or tenant-management endpoint in this slice. Drain
new-start and retry-confirmation traffic for affected tenants during a mixed rollout.
An old or unconfigured instance can still admit a legacy run. Database operators
also retain direct write access; the allowlist is an application admission boundary.

## Enable enforcement

1. Deploy PR10's additive migration `V20261030180229` and compatible promotion API.
2. Register the intended immutable suite/policy and complete passing reports for
   each exact contract revision that will be used. Publication or case pinning
   alone does not establish promotion.
3. Use trusted internal attestation to grant the dedicated CONTRACT_PROMOTER
   entitlement. Confirm the required-suite binding and record a promotion with its
   exact current version, contract, policy and selected report coordinates.
4. Configure the tenant allowlist consistently, then resume admission traffic.
5. Verify a promoted exact pin can start and an unpromoted pin is refused. Exercise
   both initial admission and fresh internal/supervised retries in the target
   environment. Shipping with the allowlist empty is not enabled-gate acceptance.

Only one governing binding version exists per tenant/contract key. Binding another
suite for a different revision of the same key does not silently upgrade old case
pins; those pins fail fresh admission until they qualify under the governing suite.
See [the internal promotion API](../contracts.md#explicit-contract-promotion).

## Change a gate and recover refusal

A new binding version invalidates old promotions for fresh admission, even when it
reconfirms the same suite. Register changed immutable policy/evaluator requirements,
confirm their suite binding and record a fresh complete promotion. The same
contract lock serializes binding changes, promotions and run admission through
transaction commit. A binding change may wait for an already admitted transaction;
a later start observes the newly committed binding.

A refused initial start or retry confirmation returns HTTP 409 with problem type
`urn:ergon:problem:resolution-run-promotion-required` and `Cache-Control: no-store`.
Review the exact case pin and current governing binding, then supply qualifying
promotion evidence. Do not automatically repin a case, choose a different revision,
or disable enforcement to make the command succeed. Storage errors remain failures.
Retry review is planning information; confirmation applies this final admission gate.

## Existing runs and rollback

Admitted runs continue under their original pinned context and normal execution,
approval, control and proof rules. A promoter's later expiry does not revoke a
historical promotion. New gate requirements act through a new binding version.
Exact retry recovery acknowledges an existing successor and does not re-admit it;
its existing authority and command checks still apply.

No new migration, run field or admission audit ledger is introduced. PR10's
immutable receipts and suite bindings remain the qualification source. Reverting
this code or removing a tenant from the allowlist reopens legacy admission. Obtain
an explicit operational decision before that rollback; preserve all run and
promotion records and prefer correcting evidence or configuration and rolling
forward. Never delete history as a rollback mechanism.

## Verification

Run the existing repository gates:

```bash
./mvnw -B -ntp -pl :repository-policy verify
./mvnw -B -ntp verify
./mvnw -B -ntp ktlint:check detekt:check
```

`RunPromotionAdmissionTest`, `ResolutionRunServiceTest` and
`ResolutionRunRetryServiceTest` cover scope, refusal and historical recovery.
`CurrentContractPromotionIntegrationTest` covers exact qualification and lock races;
`RunRetryIntegrationTest` covers refused internal/supervised successor transactions.
PostgreSQL suites require Docker and must execute before being reported passed.
See [ADR 0100](../decisions/0100-enforce-current-promotion-on-run-admission.md).
