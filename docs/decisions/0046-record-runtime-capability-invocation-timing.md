# ADR 0046: Record runtime capability-invocation timing

- Status: Proposed
- Date: 2026-10-12
- Milestone: M04 - Guarded Execution and Verification
- Deciders: Ergon maintainers
- Supersedes:
- Superseded by:

## Context

The current runtime executes one pinned capability per run. An immutable receipt
binds its result to the exact tenant, run, case, policy, step, capability and
connector. The Console has no genuine latency measurement. Consumption-to-
completion time includes waiting and is not a connector-call duration. Cost and
multi-step execution are not implemented.

The approved slice measures existing execution before introducing a multi-step
executor or the next browser presentation slice. Provider idempotency, exact
receipt replay, separate outcome proof and assigned-only disclosure must survive.

## Decision

- Add an optional `CapabilityInvocationTiming` value to the receipt: an
  application wall-clock start observation and non-negative elapsed nanoseconds.
  Sample an injected monotonic ticker immediately around the connector gateway
  call, outside both database transactions. Do not derive elapsed time from UTC
  instants. Normalize runtime wall-clock timestamps to PostgreSQL microseconds.
- The production ticker uses `System.nanoTime`; origins may be negative and
  arithmetic may wrap. Difference semantics follow the
  [JDK contract](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/System.html#nanoTime()).
  Nanosecond units do not promise nanosecond clock resolution. Calls must be
  shorter than 2^63 nanoseconds; invalid injected timing is a defect, not zero.
- Store both timing fields on the same immutable receipt row. Its existing
  consumption foreign key supplies attribution, and receipt/timing commit
  together. Concurrent writers return one winner's whole record. This measures
  that caller's gateway call, which may itself retrieve an idempotent provider
  result after an earlier uncertain call; it is not total provider-operation
  latency or an aggregation of retries.
- A thrown connector exception still supplies no terminal outcome: write no
  receipt or fabricated measurement. Persisted replay calls neither the connector
  nor timing sources. Old or older-writer receipts remain unmeasured permanently;
  NULL is unavailable, not zero. Preserve valid zero-duration samples.
- Add nullable `execution` to the assigned Console response. Load it only after
  the existing exact-assignee/current-resolver gate and validate its pinned
  scope. No receipt means no recorded terminal result, not proof of no external
  effect. Receipt recording and run-state projection remain separate operations.
- Expose only step, capability, connector, terminal outcome, completion/recording
  timestamps and optional runtime timing. Timing carries source identifier
  `ergon.dev/measurement/connector-call/v1` and canonical decimal-string
  `elapsedNanoseconds`, avoiding JavaScript integer precision loss. Do not expose
  authorization coordinates, provider-operation reference, payload or cost.

## Alternatives considered

### Subtract persisted completion and consumption timestamps

- Benefits: no recording change.
- Costs and risks: includes waiting and wall-clock corrections, with false
  precision and no attribution to the actual measured call.
- Reason not selected: it cannot establish connector gateway latency.

### Add a separate timing table or telemetry framework

- Benefits: an independently extensible measurement stream.
- Costs and risks: another write/consistency boundary for a single terminal
  sample; an unused framework before spans and scheduling have contracts.
- Reason not selected: the receipt already owns the exact measured result.

### Implement multi-step execution and synthetic cost together

- Benefits: could resemble the target waterfall sooner.
- Costs and risks: changes authority, retries, proof and schema meaning, while
  invented costs or stages would misrepresent current execution.
- Reason not selected: separate approved runtime capabilities are required.

## Consequences

### Positive

Assigned readers receive genuine, exactly attributed gateway timing without
weakening authority or confusing connector completion with verified resolution.
Replay and concurrent-winner behavior remain deterministic.

### Negative

Only terminal calls whose receipts become durable are represented. A crash
before persistence loses that call's measurement; an uncertain provider result
has no terminal receipt. Historical calls cannot gain trustworthy timings.
Wall-clock correction may put the start observation after completion; readers
must use the monotonic duration, not reject or reorder facts by wall time alone.

### Neutral or follow-up

No multi-step executor, live spans, model/provider billing, aggregation,
compensation, approval command or lease is added. The next UI slice consumes
this additive projection and updates its executable walkthrough; the present
browser rendering still shows only its previously supported fields.

## Compatibility and migration

Apply the additive migration before new writers/readers. Both nullable columns
are absent or present together, with a named non-negative CHECK. Existing rows
and older writers satisfy the NULL branch without backfill; existing immutability
triggers protect the new fields. Old binaries can continue using their explicit
old column lists. Rollback leaves the expanded schema and measurements intact.

The ALTER takes ACCESS EXCLUSIVE and scans receipts to validate the CHECK;
there is no payload rewrite. Assess table size and schedule the lock for a real
deployment. The BFF addition leaves existing fields and routes unchanged. Old
readers ignore the additional member; new readers must accept missing execution
from an older server until rollout completes. Missing means the projection is
unsupported/unknown, not that no receipt exists. Internal invocation DTOs and
owned-follow-up projections are unchanged.

## Security and operations

Keep the configured browser session, no-store responses and non-disclosing
assignment/authority absence. The tenant path grants nothing. No new public or
write endpoint is introduced; internal invocation remains a development boundary.
Measure only sanitized metadata, never credentials, content or provider payloads.
The implementation adds no network, billing service or high-cardinality metric
labels. Clock/invariant or persistence failure can leave the provider result
uncertain; recovery uses the original consumption idempotency key, never a new
grant or fabricated receipt.

## Validation

Domain/application tests cover negative/zero elapsed values, wall-clock correction,
monotonic wrap, microsecond normalization, success/failure, unknown exceptions and
replay without resampling. PostgreSQL tests exercise empty-schema migration,
supported upgrade with an unchanged historical receipt, older-writer inserts,
nullable mapping, partial/negative rejection, tenant scope, immutable timing and
concurrent winner replay. Existing full authorization/invocation/proof API tests
must also verify timing persistence without changing their transitions.

Assigned-read tests cover the real persistence/application gate and withheld
execution after authority loss; browser contract tests cover precision, nullable
timing, no-store and private-field omission. Mock-session tests do not prove live
OIDC provisioning or a remote provider. Run repository policy and full Maven
verification; frontend consumption and its browser regression remain next.
