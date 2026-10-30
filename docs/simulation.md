# Simulation fixtures

## TL;DR

M07-PR1 defines the pure-domain `ergon.simulation/v1` manifest and its semantic
SHA-256 identity. It does not register, load or execute artifacts, assess claims,
produce evaluation reports, or authorize promotion. See [ADR 0090](decisions/0090-pin-simulation-fixture-identity.md).

## Pinned meaning

`SimulationFixture` pins tenant, contract and policy artifacts, an exact case ID
and positive event stream prefix, evaluator artifact, evaluation instant,
documentary attachments, ordered connector/model scripts, optional model
configuration, assertion artifact, expected outcome and optional budget artifact.
The prefix material includes the evidence bindings at that version; later events
are excluded. Consumers must verify resolved bytes against each artifact digest
and validate their own supported material schema before replay. A matching digest
alone establishes neither trust nor authority.

Each artifact carries a key and revision of 1–128 ASCII letters, digits, dots,
underscores or hyphens, starting with an alphanumeric character, plus exactly 64
lowercase hexadecimal SHA-256 characters. These are identifiers, not URLs, code,
file paths or fetch instructions. Raw provider responses and assertion programs
are not embedded in this manifest. Artifact material formats belong to the later
consuming slices; unsupported material must not be interpreted as executable code.

Fixtures allow at most 20 documentary pins and 100 script entries. Documentary
keys and attachment stream versions are unique, positive and within the pinned
case prefix. Attachment instants cannot follow evaluation time. All instants must
be exactly representable at microsecond precision; no silent rounding occurs.
Claim validity remains evaluated at the server-recorded attachment instant, not
at evaluation time. Attachment metadata must subsequently be checked against the
resolved case prefix; domain construction does not certify external material.

Scripts have unique artifact keys in execution order. Repeated calls require
separately named script entries, even when they reference the same content hash.
A model configuration is required exactly when a MODEL script occurs. The manifest
contains no live-adapter fallback. Budget material must later define explicit
units and pricing; absent budget means unspecified, never zero cost.

Expected outcomes are NOT_READY, POLICY_DENIED, WAITING_FOR_APPROVAL, VERIFIED,
FAILED, ESCALATED and CANCELLED. They describe fixture expectations, not observed
results. They never grant runtime authorization or establish accepted proof.

## Canonical identity

The canonical bytes are a hash preimage, not an HTTP or persistence wire format.
Every integer is signed big-endian. Text is a four-byte UTF-8 byte length followed
by exactly those bytes. An artifact is key, revision and digest as three texts.
An instant is eight-byte epoch seconds followed by four-byte nanoseconds. Optional
artifacts start with one byte (0 absent, 1 present), followed by the artifact if present.

The v1 field order is:

1. Schema version and lowercase hyphenated tenant UUID, as text.
2. Contract artifact, then policy artifact.
3. Lowercase hyphenated case UUID, eight-byte stream version, prefix artifact.
4. Evaluator artifact, then evaluation instant.
5. Four-byte documentary pin count; pins sorted by ascending stream version,
   each containing bundle artifact, eight-byte stream version and attachment instant.
6. Four-byte script count; scripts in supplied order, each containing kind name
   as text followed by material artifact.
7. Optional model configuration, assertion artifact, expected outcome name as
   text, then optional budget artifact.

SHA-256 is rendered as lowercase hexadecimal. Canonical content is limited to
65,536 bytes. Collections are frozen on construction and byte access returns a
fresh array. Recording IDs, actors and recording timestamps are outside this
manifest. Tenant scope deliberately participates in identity. Artifact keys and
revisions participate even when their content hashes match. There is no Unicode
normalization, locale-dependent sorting or wall-clock input.

Changing the encoding, enum meanings, normalization or identity fields requires a
new schema version. Unknown fixture versions fail closed. No decoder is introduced
in this slice; future adapters must reject unknown fields/enum values before
constructing these domain values.

## Verification

Run `./mvnw -B -ntp -pl domain-kernel test`, then repository policy and the full
`./mvnw -B -ntp verify`. The domain suite includes an independently calculated
binary identity vector, input sensitivity, ordering, mutation resistance, bounds,
unsupported version, model configuration and historical timestamp tests.

## Internal fixture registration

M07-PR2 registers self-contained curated packages. It neither executes their
contents nor claims that historical material has been automatically sanitized.
The internal endpoint inherits the trusted-machine deployment boundary: do not
expose it to browsers or public ingress. `X-Ergon-Actor-Id` is claimed attribution,
not authentication. The actor must already be registered in the path tenant,
including for retries; no resolver or promotion authority is granted or checked.

POST JSON to `/internal/v1/tenants/{tenantId}/simulation-fixtures/{key}/revisions/{revision}`
with the actor UUID header. GET the same path to retrieve the exact registration.
Keys/revisions use the bounded artifact identifier grammar above. The package is:

| Field | Meaning |
|:--|:--|
| `manifest` | All v1 manifest fields, using the domain property names; timestamps are ISO-8601 instants, UUIDs strings, enum values uppercase names. |
| `digest` | Expected v1 manifest semantic digest, independently checked on registration. |
| `materials` | Array of `{ "reference": { "key": "...", "revision": "...", "sha256": "..." }, "content": "..." }`. |
| `provenance` | `{ "kind": "SYNTHETIC", "description": "Reviewed fixture origin" }`; kind also permits `CURATED_HISTORICAL`. |

The manifest's nullable `modelConfiguration` and `budget` fields must be present
as JSON null when absent; lists must be present even when empty. Nested artifacts
use `key`, `revision`, `sha256`; `casePrefix` uses `caseId`, `streamVersion`,
`material`; documentary pins use `bundle`, `streamVersion`, `attachedAt`; scripts
use `kind`, `material`. Unknown fields, duplicate JSON keys, scalar coercions,
unknown enum/version values, malformed UTF-8 and trailing JSON are rejected.
Provenance and content must be valid Unicode without NUL characters (PostgreSQL
JSON validation cannot represent NUL).

Each distinct manifest artifact requires exactly one matching material. Its digest
is SHA-256 of the decoded content string's exact UTF-8 bytes, without newline or
Unicode normalization. Different references may share bytes. Conflicting digests
for the same artifact key/revision, missing/extra material and duplicate references
are rejected. Material schemas are not evaluated until the consuming replay slice;
a registered `{}` contract artifact is not a validated production contract.

Ingress and normalized JSON are bounded to 1,048,576 UTF-8 bytes. The provenance
description is nonblank and at most 2,000 UTF-16 code units. Content is inert text:
no referenced URL is fetched, script executed, archive expanded or production case
read. Case IDs are simulation identities, not foreign keys into live cases.

A new revision returns 201 with Location; exact retry returns 200 and original
actor/time. Responses contain `identity`, `digest`, `definition` (the normalized
package JSON as a string), `submittedBy`, and `recordedAt`. All responses use
`Cache-Control: no-store`. Documentary and material set ordering is normalized;
script order, provenance and material bytes remain significant. Changed meaning
or provenance for an existing revision returns 409. Invalid packages/path grammar
return 422, oversized packages 413, and absent/foreign revisions or unregistered
tenant actors 404. Malformed UUID/header binding uses the standard 400 response.

## Storage and rollout

Apply the additive `simulation_fixture_revisions` migration before deploying the
registration API. It scans no existing tables and does no backfill. One row stores
normalized JSON text, manifest canonical bytes, digest and original attribution
atomically. Text storage deliberately preserves the bounded normalized UTF-8
representation rather than expanding JSONB whitespace. SQL checks the manifest
schema/tenant/digest, canonical digest and size, and the tenant-scoped actor FK;
read reconstruction verifies the complete package again. UPDATE and DELETE are
rejected. Concurrent registration waits on the immutable tenant/key/revision key,
then compares the winning row using a fresh READ COMMITTED snapshot.

Rollback application code without dropping registrations or editing the shared
migration. Packages may contain sensitive curated material: retain them in the
existing trusted database/backup boundary and review privacy before submission.
There is no export, automatic redaction or deletion API in this slice. Browser
entitlements, replay and promotion remain later M07 work. See [ADR 0091](decisions/0091-register-curated-simulation-fixtures.md).

## Pinned readiness replay

M07-PR3 supplies the in-process `PinnedScenarioReplay.replay(bytes)` application
service. Supply the complete registration package (or a retrieved definition's
UTF-8 bytes), not the registration response envelope. It rechecks package hashes
before interpreting material. There is no replay HTTP endpoint in this slice.

The result is either `Evaluated(fixtureDigest, plan)` or `Unsupported(reason)`.
`plan` is the production `CaseResolutionPlan`: readiness at the fixed prefix,
policy revision, and the first ordered step's policy decision only when ready.
Invalid input throws `InvalidSimulationFixture`; oversized input retains the
registration size error. An evaluated plan is not a passing assertion, verified
resolution, approval, execution authorization, report or promotion decision.
Fixture expectations, assertion contents and optional budgets are not assessed.

The supported evaluator artifact contains exactly
`ergon.simulation/readiness-policy/v1` as UTF-8, with no newline. Its manifest
reference must hash those exact bytes. Unsupported evaluator content and nonempty
connector/model script lists return explicit unsupported results. Behavior changes
to this evaluator require a new evaluator identifier; previous identities must not
silently acquire different semantics. See [ADR 0092](decisions/0092-replay-pinned-planning-inputs.md).

### Material formats

- Contract: the existing strict v1alpha1/v1alpha2 YAML format. Its key and revision
  must match the artifact key and decimal revision. Existing reference validation
  rejects unsupported vocabulary; no new semantic rules are inferred.
- Policy: JSON with `schemaVersion: ergon.simulation/policy/v1`, a path-like
  `revision` naming the actual policy, and `rules` containing `capability`,
  `minimumRisk` and `minimumApproval`. At most 100 rules; production policy
  constructors reject empty/duplicate or unsafe rules. The material hash pins the
  policy revision and complete rules independently of the artifact's storage label.
- Case prefix: JSON with `schemaVersion: ergon.simulation/case-prefix/v1`, `tenantId`,
  `caseId`, and `events`. Each event has `version`, `type`, `schemaVersion`,
  `recordedAt` and `payload` (the existing case-event JSON object). Versions must
  be contiguous from 1 through the manifest's exact prefix, with at most 1,000
  events. IDs must match the fixture. No trailing events are silently truncated.
- Documentary material: JSON with
  `schemaVersion: ergon.simulation/documentary-validity/v1`, `bundleId`, `sha256`,
  `assessedAt`, and `claims` containing nullable `from` and `until` strings.
  At most 1,000 claim intervals. Both validity endpoints absent means unknown;
  otherwise the production interval constraints apply. This is a curated validity
  summary, not a reconstructed production evidence publication. The outer artifact
  digest hashes the summary bytes; its inner `sha256` identifies documentary
  meaning carried by the case attachment and contract requirement.

Material JSON rejects unknown fields, duplicate keys, missing constructor fields,
scalar coercions and trailing content. Recording/validity timestamps are ISO-8601
strings with exact microsecond precision. Every event recording instant must be
at or before fixture evaluation time; event order, not wall-clock sorting, defines
the prefix. Event `occurredAt` retains the existing case-event codec semantics.

Supported case event types use schema version 1: `CaseOpened`,
`ObservationRecorded`, `AccountAccessStateBound`, `ResolutionContractRevisionPinned`
and `DocumentaryBundlePinned`. Unsupported events, including verified closure,
return unsupported rather than approximating execution/proof behavior. The prefix
must contain one opening event first and must rehydrate through the production
case aggregate. A missing contract pin remains `WaitingForContract`; a different
contract pin is invalid.

Every documentary attachment event must have exactly one manifest pin at that
stream position. Its manifest `attachedAt` must equal the event envelope's
`recordedAt`, not payload occurrence time. Bundle ID/digest must match the summary.
Production documentary readiness assesses its selected validity at attachment,
including unknown validity, post-attachment assessment and expiry at the exclusive
upper bound. Changing evaluation time cannot requalify that attachment.

### Isolation and verification

Read-only in-memory implementations of the existing case/contract/documentary
ports feed `ResolutionReadinessService` and `ResolutionPlanningService`. Their
write methods throw; no production repositories, connectors, clock or identity
generator are supplied. The production services retain latest-bound account state,
missing-evidence precedence, exact applicability and policy-strengthening semantics.
There is no duplicated readiness or policy algorithm in the simulation adapter.

`PinnedScenarioReplayTest` covers repeated values, both contract versions,
latest-bound evidence, waiting/missing/nonapplicable states, policy denial,
documentary attachment cutoffs, invalid prefixes and explicit unsupported results.
Run it through the existing Maven test harness and retain full repository verification.

## Capability and proof replay

M07-PR4 adds the separate evaluator identifier
`ergon.simulation/capability-proof/v1` (exact UTF-8 bytes, no newline).
The readiness evaluator retains its original script rejection semantics.
The new evaluator accepts the same pinned planning materials plus exactly one
`CONNECTOR` script with schema `ergon.simulation/capability-scenario/v1`.
See [ADR 0093](decisions/0093-simulate-capability-and-proof.md).

The script contains these required fields (nullable values remain explicit):

- `authority`: nullable object with `actor` UUID, `authority` enum, nullable
  `caseId`, and `fromSeconds`/`untilSeconds` relative to evaluation time.
- `decision`: nullable `APPROVED` or `REJECTED`; `requestLifetimeSeconds` (1–86,400)
  and `consumeAfterSeconds` (0–86,400). Authority offsets are within ±86,400 seconds.
- `route`: nullable object with `capability` and `connector` names.
- `calls`: 1–100 ordered objects with `outcome` (`SUCCEEDED`, `FAILED`, `UNKNOWN`)
  and `elapsedNanoseconds` (0–60 billion, exact microsecond multiples).
- `invocationCount`: 1–100 explicit invocations. An unknown call may be followed
  by another call using the same operation key. Terminal replay consumes no
  additional script entry; missing or unused outcomes fail validation once invoked.
- `proofCaseVersion` and `proof`: up to 100 curated entries containing `factId`,
  `observationId`, `fact`, `value`, `observationVersion`, `factVersion`, `observedAt`
  and `boundAt`. IDs and stream positions are unique; timestamps use exact
  microseconds. These inputs are not authentic production attestations.

Production approval decision, grant and consumption rules gate the first planned
step. Missing/rejected/invalid approval or route cannot invoke the gateway.
Approval-free execution is explicitly unsupported because the existing grant
path requires an approved decision. This is not production admission, supervision
or authority discovery. It does not orchestrate successor runs.

The production invocation service runs with fresh in-memory stores and a scripted
gateway, clock and ticker. Preparation precedes gateway I/O outside a transaction;
unknown outcomes retain an attempt but no terminal receipt. Explicit retry reuses
the consumption key; terminal receipt replay makes no additional gateway call.
Only successful receipts reach the production outcome-proof evaluator. Missing,
stale or incorrect evidence remains pending, and failed receipts cannot prove an
outcome. `PROOF_ACCEPTED` is an assessment, not finalization or case closure.

Traces retain the fixture digest, bounded status, operation keys, attempt timing,
terminal receipt and proof assessment. Replaying the same package yields the same
trace. There is no live connector, production write, automatic retry policy,
HTTP execution endpoint, assertion scoring, budget enforcement or promotion.
`CapabilityScenarioReplayTest` covers these gates alongside the existing planning
suite; full Maven verification remains required.

## Scripted model proposals

M07-PR5 adds `ModelProposalScenario.replay(bytes)` using the separate evaluator
`ergon.simulation/model-proposal/v1` (exact UTF-8, no newline). Earlier evaluators
keep their existing meaning. This evaluator accepts 1–100 `MODEL` scripts only,
with one exact `modelConfiguration` artifact. Mixed connector/model programs are
unsupported. See [ADR 0094](decisions/0094-replay-untrusted-model-proposals.md).

Configuration material has schema `ergon.simulation/model-configuration/v1` and
required fields `modelId` (nonblank, at most 128 characters), `prompt` (nonblank,
at most 16,384 UTF-8 bytes) and `maxResponseBytes` (1–16,384). Model identity and
prompt are descriptive, inert fixture data. Nothing loads a provider, interprets
prompt instructions or makes a network request.

Each script material has schema `ergon.simulation/model-response/v1`, an exact
`configuration` artifact reference and a `response` string. Every script must
reference the manifest's configuration, including its digest. The manifest's
ordered script list selects the complete response sequence; material storage order
has no effect. All responses are checked independently against the same prefix,
without a shared cursor, prior-response state or automatic retry.

The supported response is strict JSON with schema
`ergon.simulation/next-step-proposal/v1` and these required fields:

- `kind`: `NEXT_STEP`.
- `stepId` and `capability`: existing runtime identifiers.
- `factIds`: at most 20 distinct UUIDs. For the supported account-state runtime,
  this must cite exactly the latest bound account-state fact in the pinned prefix.
- `documentary`: at most 20 distinct bundle references, each with `bundleId` and
  lowercase `sha256`. These must equal the pinned contract's requirements exactly.

JSON rejects extra/missing fields, duplicate keys, coercion, trailing content and
null collection entries. Approval, authority, new evidence, instructions and proof
are not proposal fields. Malformed or oversized response text yields
`MALFORMED_OUTPUT`; unsupported response schemas or well-formed unknown kinds yield
`UNSUPPORTED_OUTPUT`. Invalid configuration, script bindings or package integrity
fail fixture decoding instead. Unsupported envelope/material versions remain explicit.

The production planner supplies readiness, the next step and policy safeguards.
A valid-shaped proposal cannot overcome missing/stale documentary evidence or policy
denial. Wrong step/capability and fabricated, stale or incomplete references are
separate observations. `CONSISTENT_PROPOSAL` means only that the suggestion matches
that plan and its required references. It neither authorizes execution nor counts
as a passing evaluation, and it does not create or append evidence.

Results retain fixture/configuration/script identities, SHA-256 of each exact
UTF-8 response, normalized supported suggestions and the production plan. They
omit raw prompts and response text. Assertion scoring, token/cost accounting,
provider quality certification, execution and promotion remain separate work.
`ModelProposalScenarioTest` covers deterministic selection and digest provenance,
malformed/adversarial output, invented capabilities/references, policy safeguards
and documentary attachment validity alongside both earlier replay suites.

## Typed scenario assertions

M07-PR6 adds the pure-domain `ScenarioAssertionEvaluator.evaluate(assertions, trace)`
API. It compares independently authored expectations against typed observations;
it never calls the planner, proof evaluator, connector or model being tested.
This is the assertion foundation consumed by PR8 report orchestration and later
required-suite gates. It does not yet decode the manifest's opaque assertion
material, execute fixtures or persist reports. See
[ADR 0095](decisions/0095-assess-explicit-scenario-observations.md).

An assertion catalog has 1–100 ordered entries, unique IDs matching
`[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}`, and at least one required entry. Every entry
produces exactly one ordered result with its ID, required flag, status and stable
explanation code. Empty and all-optional catalogs are invalid. Results retain
optional failures/errors as well as required ones. `requiredChecksPassed` is true
only when every required result is `PASS`; it is not a promotion decision.

Available checks are:

| Check | Comparison |
|:--|:--|
| Outcome | Exact observed outcome versus authored expectation. |
| AllowedInvocations | Every observed gateway capability belongs to the authored allowlist; an empty allowlist forbids all calls. |
| ExactInvocations | Ordered capability sequence, preserving repeated calls and retries. |
| Policy | Exact policy decision, including required approval/risk or denial. |
| Grounding | Exact sets of existing fact IDs and documentary bundle ID/digest pairs; reference order is immaterial. |
| AcceptedProof | Accepted assessment matching the authored condition, successful action receipt, post-run source position, assessed prefix and post-action observation time. |
| NoUnprovedClosure | A reported VERIFIED outcome requires the same proof checks; an observed non-verified outcome satisfies this conditional safety property. |
| Escalation | Exact expected reason or observed absence, consistent with whether the observed outcome is ESCALATED. |
| Unsupported | Explicit unknown catalog entry; always returns UNSUPPORTED. |

Trace fields distinguish `Available(value)`, `Missing`, `Unsupported` and `Error`.
For example, observed zero calls and observed no escalation are available values;
missing fields must never be filled with empty lists or false. Required missing
observations produce `ERROR`, while unsupported observations produce `UNSUPPORTED`.
An observation error takes precedence over unsupported data when the escalation
check requires both fields. Conditional closure checks require proof only when
closure was observed as VERIFIED.

`FAIL` reports an expectation mismatch, non-accepted proof or invalid proof
provenance. `PASS` means only that this particular property matched its inputs.
The evaluator does not certify trace authenticity, tenant attribution, full source
history, latest-evidence selection or escalation authorization. It also does not
infer policy eligibility from an allowlist or turn a valid citation into evidence.
Trace production and later report orchestration own those boundaries.

In particular, PR4's `PROOF_ACCEPTED` is not VERIFIED closure, and PR5's consistent
proposal is not executed capability/proof. These runners do not observe escalation.
Their unavailable closure/escalation observations must remain unsupported in later
projections. Never copy the fixture's expected outcome into its observed outcome.
Future assertion-material encoding must version these semantics explicitly.

`ScenarioAssertionsTest` uses independently constructed and deliberately corrupted
traces. It covers forbidden invocation, retry multiplicity, wrong policy/capability,
missing grounding, false closure, stale or wrong proof, contradictory escalation,
required/optional behavior, missing/error/unsupported states and collection mutation.

## Deterministic budgets

M07-PR7 adds `EvaluationBudgetEvaluator` and `ScenarioBudgetAccounting` for explicit
usage and pinned pricing. These APIs neither execute fixtures nor decode the
manifest's opaque budget material; PR8 owns verified material decoding and report
orchestration. See [ADR 0096](decisions/0096-account-for-explicit-simulation-usage.md).

`EvaluationBudget` names one uppercase three-letter currency identifier and optional
inclusive `maxCostNanos` and `maxLatencyNanoseconds` limits. Currency nanos are exact
billionths of that currency, not floating-point values or minor currency units.
Pricing supplies nonnegative integer currency nanos per `CALL`, `INPUT_TOKEN` or
`OUTPUT_TOKEN`, keyed by an exact `SimulationArtifact` including revision and digest.
There is no latest-pricing lookup, implicit free rate, exchange conversion or rounding.
A price finer than one currency nano per unit cannot be represented by this API.

Each `BudgetAttempt` has a unique stable ID, exact pricing reference, explicit
nonnegative unit counts and simulated duration. A nullable count or duration is
unknown. Collections are immutable and bounded to 200 attempts/pricing entries,
allowing the existing 100 connector and 100 model limits. Combine projections by
concatenating their attempt lists into one `ScenarioBudgetUsage`; duplicate IDs
are rejected. An empty collection means observed zero attempts; absent usage is
represented by null and never becomes zero.

Connector accounting uses the trace's completed gateway attempts, including FAILED
and UNKNOWN outcomes. Each consumes one CALL, even when retries reuse an idempotency
key. Attempt/key count mismatch is rejected. A terminal receipt is not another call;
the existing three-invocation terminal-replay test proves only one charge remains.
Model accounting binds supplied token/duration measurements to the fixture digest,
configuration artifact, script artifact and exact response digest. Every scripted
response counts, including malformed or rejected output. Missing measurements retain
unknown token counts and duration; token counts are never estimated from response
text. Extra, duplicate or misbound measurement entries are rejected.

Cost is the exact sum of count × per-unit rate. Simulated latency is the sum of
attempt durations; it excludes evaluator execution time, database work, preparation,
backoff and other unmeasured overhead. It is neither wall-clock elapsed time nor a
live-provider performance claim. Failed calls and retries retain their duration.

Each dimension exposes its limit, measurement and status:

- `PASS` / `FAIL`: a known total is at/below or above the configured limit.
- `UNKNOWN`: configured dimension lacks usage, duration or exact pricing/rate.
- `ERROR`: configured cost has a currency mismatch, or the total exceeds signed
  64-bit capacity. Intermediate arithmetic is exact; it cannot wrap or round.
- `NOT_CONFIGURED`: no limit, with the measurement still visible (including errors).

No partial total is reported as complete when any needed input is unknown.
A known currency mismatch takes precedence over other cost failures; a known
subtotal overflow takes precedence over missing inputs. Otherwise the first missing
cost component in attempt order and fixed CALL/INPUT_TOKEN/OUTPUT_TOKEN order
supplies the explanation. Cost and duration
remain independent, so known duration cannot hide unknown cost.
`configuredBudgetsPassed` requires at least one configured dimension and PASS for
all configured dimensions. It does not assess assertions or authorize promotion.

The domain and application tests cover inclusive equality, independent limits,
unknown versus zero, currency/revision/digest mismatches, exact token rates,
product/sum overflow, unsuccessful attempts, retry accounting, receipt replay,
missing model measurements, exact identity binding and caller mutation.
