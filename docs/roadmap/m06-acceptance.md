# M06 bounded acceptance record

## TL;DR

Snapshot: 2026-10-30. Milestone: M06 - Evidence Compiler.

The approved bounded pipeline is implemented through M06-PR13. PR14 adds
redistributable real-source fixtures, a composed source-to-contract acceptance
test, an operations guide and a coordinated executable UI guide. This record
tracks implementation and verification separately: PostgreSQL acceptance remains
unverified locally because Docker socket access is denied. No live two-app/OIDC
deployment, production readiness or M07 promotion is claimed.

Delivery is coordinated through [backend issue #259](https://github.com/stanimirivanov/ergon/issues/259)
and [UI issue #240](https://github.com/stanimirivanov/ergon-ui/issues/240).
They have separate PRs by explicit user approval and cannot merge atomically.
No external milestone state is changed by this record.

## Acceptance traceability

| Criterion | Implementation and executable evidence | Retained boundary |
|:--|:--|:--|
| Immutable real document and API sources | [Licensed pinned fixtures](../../control-plane/src/test/resources/knowledge/acceptance/PROVENANCE.md), [digest/citation checks](../../control-plane/src/test/kotlin/org/ergon/controlplane/knowledge/CompilerFixtureIntegrityTest.kt); source service/integration tests in the same knowledge test directory | Exact bytes and SHA-256, no crawling, URL fetch or upstream API call |
| One Markdown and one API-description format | CommonMark and OpenAPI parser tests plus the real Petstore README and JSON example in fixture checks | OpenAPI 3.0 JSON, bounded local references; no PDF/Office/YAML input or arbitrary-prose inference |
| Attributable typed claims and ordered procedures | [Composed acceptance](../../control-plane/src/test/kotlin/org/ergon/controlplane/knowledge/SourceToContractIntegrationTest.kt), claim/procedure tests under `control-plane/src/test/kotlin/org/ergon/controlplane/evidence/` | Manual binding remains an untrusted proposal; documentation is not live account state |
| Explicit review and bounded contradiction adjudication | Composed acceptance creates and reviews opposed training claims, rejects unresolved publication and adjudicates an exact candidate set; existing review/contradiction integration tests cover stale epochs and current authority | Opposed training assertions are human inputs, not a claim that upstream Petstore prose contradicts itself |
| Immutable qualified bundles | Composed acceptance publishes and recovers an exact bundle with selected claim/procedure, context alternative and all three source revisions; existing bundle integration tests cover concurrent publication and immutability | Later discoveries do not rewrite manifests or historical cutoffs |
| Contract consumes exact documentary references | Composed acceptance publishes v1alpha2, pins contract/bundle and progresses from missing documentary material to missing operational evidence to ordinary readiness; PR13 case attachment tests cover exact prefixes and database admission | Validity is at database attachment time; ordinary observations, approval and proof stay independent |
| No implicit authority from evidence | Composed acceptance rejects unknown/foreign access and stale extraction, requires operational binding, then creates only WAITING_FOR_APPROVAL | No connector executes, approval is not granted and no case is proved resolved |
| Human-facing inspection and recovery | UI `evidence-review.spec.ts`, `evidence-knowledge.spec.ts` and executable guide `evidence-review-and-bundles` in the coordinated UI repository | Synthetic marked BFF; keyboard, narrow layout, inert text, exact retry and access retirement are browser evidence only |

## What the composed test proves

The Spring test uses the actual dependency composition, source/proposal/review,
contradiction/bundle and case/contract services and PostgreSQL repositories.
It does not seed accepted claims or manifests directly. Only actor and fresh
resolver authority setup use SQL. Sources pass through registration and exact
compilation. A separate offline suite verifies every emitted citation against
original bytes, altered quotation rejection, stable parsing and fixture hashes.

The real README contributes citation provenance. A separately authored training
runbook contributes an ordered procedure linked to the real OpenAPI `GET /pets`
operation. Opposed SUPPORT values are deliberately manual fictional bindings in
`petstore-training` / `compiler-acceptance`; their explicit adjudication proves
the mechanical trust boundary, not a meaningful Petstore account-recovery policy.
The built-in access-restoration contract is a test consumer, not an upstream
endorsement. All external URIs and embedded commands remain inert.

## Verification snapshot

- Passed locally: offline fixture tests (3), compilation, Ktlint and Detekt;
  repository policy before changes. Final full-check outcomes are recorded in the
  delivery PRs.
- Not executed locally: the new Spring/PostgreSQL composed acceptance test (1)
  because Testcontainers could not access Docker. Existing database coverage is
  executable evidence, not a claimed pass in this environment.
- UI: focused Chromium evidence scenarios pass. Guide recording and full UI gates
  are recorded separately on the coordinated UI PR; generated media is ignored
  build output, not committed verification evidence.
- Unverified: live source submission through deployed OIDC, browser-to-server
  coordination, production retention/security operations and live external APIs.

No passing CI result is inferred from a merge or a source file. Use the delivery
PR check results for the exact tested commit and environment; this snapshot does
not proactively poll or certify later heads.

## Exclusions and remaining work

This bounded release uses synchronous in-process compilation and PostgreSQL
source storage. Separately deployed workers/object storage, live model extraction,
semantic contradiction inference over arbitrary prose, embeddings, automated
crawling, general connectors and extra document formats remain undelivered.
Simulation/promotion is M07, not an implicit consequence of this milestone.

The browser has no source-upload editor, contract authoring, case bundle
attachment or documentary-readiness display. Those are separate product work,
not fabricated steps in the guide. See the
[compiler operations guide](../operations/evidence-compiler.md) for supported
backend sequence, security and recovery behavior.
