# M07 bounded acceptance record

## TL;DR

Snapshot date 2026-11-04. Milestone M07 - Simulation and Promotion.

M07-PR15 adds a composed PostgreSQL test from registered fixtures to durable run
admission under current promotion. The corpus uses synthetic case histories,
scripted connector responses and independently authored expectations. The merged
UI covers inspection and confirmation against a simulated browser backend.

## Acceptance traceability

| Criterion | Executable evidence | Scope |
|:--|:--|:--|
| Immutable evidence and repeatable evaluation | [Composed acceptance](../../control-plane/src/test/kotlin/org/ergon/controlplane/evaluation/SimulationPromotionAcceptanceIntegrationTest.kt) | A fresh command reproduces canonical bytes and digest. An exact retry retains original actor and time. |
| Safety and proof | Composed acceptance checks effective high risk, resolver approval and absent accepted proof after failed invocation. [Report replay tests](../../control-plane/src/test/kotlin/org/ergon/controlplane/evaluation/EvaluationReplayReportTest.kt) exercise positive proof assessment, untrusted model output and unsupported closure. | Scripted approval and proof assessment are bounded offline observations. |
| Documentary cutoff | Composed acceptance compares valid and expired documentary summaries at the recorded attachment instant and evaluates after the validity window. | Synthetic pinned summaries exercise cutoff semantics. [M06 acceptance](m06-acceptance.md) covers source and bundle construction separately. |
| Uncertain and failed calls | Composed acceptance persists failed and uncertain connector traces, including measured cost. [Capability replay tests](../../control-plane/src/test/kotlin/org/ergon/controlplane/evaluation/CapabilityScenarioReplayTest.kt) cover scripted recovery. | Terminal replay charges one completed call. Uncertainty does not establish verified closure. |
| Budget and exact gate | Composed acceptance binds exact over-budget and missing-proof evidence, reviews refusal and checks the rejected command leaves no receipt. [Suite tests](../../control-plane/src/test/kotlin/org/ergon/controlplane/evaluation/EvaluationSuiteIntegrationTest.kt) cover suite registration and evidence identity. | Prices and duration are simulation inputs. |
| Attributed promotion | Composed acceptance checks original acknowledgement, exact intent, actor and authority ID, and refuses a stale review after binding changes. | The production services enforce current viewer and promoter grants. |
| Enforced run admission | Composed acceptance calls run start with the production admission configuration, transaction runner and PostgreSQL run repository. New starts fail before promotion and after rebinding, then succeed after fresh promotion. | The production planner reads an independently seeded ready case from PostgreSQL. Existing retry coverage is in the [retry integration suite](../../control-plane/src/test/kotlin/org/ergon/controlplane/resolution/RunRetryIntegrationTest.kt). |
| Browser inspection and confirmed promotion | [PR13 report inspection](https://github.com/stanimirivanov/ergon-ui/pull/243), [PR14 promotion and guide](https://github.com/stanimirivanov/ergon-ui/pull/245) | Explicit acknowledgement, cancellation, stale review, denial and exact command recovery under simulated BFF responses. |

## Verification and rollout

PR14 merged at `05279f24`. Its [Verify run](https://github.com/stanimirivanov/ergon-ui/actions/runs/41173704729)
passed workspace and pinned visual verification. Its [guide run](https://github.com/stanimirivanov/ergon-ui/actions/runs/41173704784)
passed simulated guide generation. The evidence is scoped to the PR check runs.
The focused Maven run executed both composed tests against PostgreSQL 18 via
Testcontainers, with two passes and zero skips. The delivery PR records full
backend verification for [issue #285](https://github.com/stanimirivanov/ergon/issues/285).

Live browser-to-backend deployment with OIDC, real connector or model execution,
provider charges and production admission remain unverified. Apply the backend
browser migrations before deploying the UI. Use the explicit tenant allowlist
for enforcement and follow the [promotion operations guide](../operations/contract-promotion.md).
Improvement generation remains M08 work.
