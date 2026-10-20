# ADR 0060: Record observed gateway-attempt history

- Status: Accepted
- Date: 2026-10-20
- Milestone: M04 - Guarded Execution and Verification
- Deciders: Ergon maintainers; standing Resolver Console authorization/disclosure approval
- Supersedes:
- Superseded by:

## Context

Admissions in ADR 0058 prove permission committed, not dispatch. ADR 0046's
winning receipt measures one terminal caller only. Neither can truthfully
describe uncertain calls, concurrent callers or preparation without completion.
The Console needs actual recorded facts before displaying further execution detail.

## Decision

Append a server-identified preparation in the invocation service's existing
pre-I/O transaction after resolving consumption and finding no terminal receipt.
It binds to that tenant's immutable consumption. Commit before calling the
gateway. A crash can occur between commit and I/O: preparation is **not proof
of dispatch**. Replay returns the receipt without preparation, IDs or sampling.

Sample the injected monotonic ticker immediately around the actual gateway
call, excluding database work. Append a separate immutable completion with UTC
start/end observations and exact non-negative elapsed nanoseconds. Wall time
can move backwards. SUCCEEDED/FAILED mean gateway-reported results; UNKNOWN
means the call threw and provider effects remain unresolved. No cause, payload
or provider reference enters audit. A missing completion means no completion
recorded, never live work, no effect or zero latency.

For terminal returns, completion and existing receipt recording share one
transaction. Concurrent callers retain their own audit measurements and return
the unchanged winning receipt. For exceptions, append measured uncertainty and
rethrow the original exception; audit failure propagates separately, preserving
the connector cause as suppressed diagnostic context rather than allowing the
browser uncertainty handler to swallow a storage failure. Measurement defects
propagate without fabricated completion. No transaction spans connector I/O.

Expose GET invocation-history only after current exact-run supervision and
current tenant-wide RESOLVER evidence at one observation instant. Terminal,
cross-epoch and internal execution are eligible; original actor, control lease
and execution-command flag are irrelevant to the read. Return up to 100
preparations with completion metadata, operation labels and explicit truncation.
Nanoseconds use the existing source identifier and canonical decimal strings.
Read 101 preparations/completions in one statement, ordered by preparation
recording time then private UUID. Order is deterministic, not causal; neither a
stable cross-request prefix nor complete coverage is promised. Exclude actor,
authority, command/attempt replay coordinates, grant, consumption, fence/session
and provider identifiers. No facts grant command or recovery rights.

## Alternatives considered

### Attach a receipt/result to each browser admission

- Benefits: no new writes.
- Costs and risks: an admission may never call, or only repair projection.
- Reason not selected: invents dispatch/result attribution.

### Keep receipt timing only, or add distributed tracing first

- Benefits: less audit storage, or a broader telemetry framework.
- Costs and risks: loses uncertain/concurrent calls, or expands the deployment.
- Reason not selected: current behavior needs durable per-call metadata, not a
  speculative tracing service, cost model or multi-step executor.

## Consequences

### Positive

The Console can distinguish preparations, measured gateway outcomes and unknown
effects without replacing receipt authority or independent proof.

### Negative

Extra writes can fail before dispatch or after an external effect. Audit growth
requires capacity planning; bounded reads do not establish retention policy.
Old calls and mixed-version writers remain unrepresented permanently.

### Neutral or follow-up

Backend first; dependent UI and executable guide wait for review/merge. No live
spans, nested workflow, automatic retry, cost, new authority or general recovery.

## Compatibility and migration

V20261020120000 adds preparation/completion tables, immutable triggers and lookup
indexes. No backfill. Schema precedes new writers/readers; older explicit-column
writers remain compatible. The consumption index scans existing rows and blocks
writes while building; budget table size, index/WAL space and a maintenance
window. New audit tables are empty at installation. Application rollback retains
expanded schema and audit. Missing old-server routes are unsupported, not empty.

## Security and operations

Reuse confidential sessions, no-store success/problems and neutral private 404
before history access. Persistence/invariant failure is cause-free 503, never
successful absence. Read grants no replay/control/dispatch authority. This does
not promote internal invocation to a production API or add database roles/RLS.
Raw connector exceptions remain private; retention/export/deletion remain
separate reviewed capabilities. An incomplete audit never authorizes retry.

## Validation

Application tests cover ordering, no I/O transaction, replay, uncertainty,
independent timing and storage-failure propagation. PostgreSQL/HTTP tests cover
committed preparation, rollback, concurrent winner/sample separation, private
authorization, bounds/ties, no-store, precision, constraints and immutability.
Verify empty migration chain and populated released-schema upgrade, including
unchanged legacy receipt replay. Run focused policy and full Maven verification;
mock sessions/local gateways do not certify real IdP or provider guarantees.
