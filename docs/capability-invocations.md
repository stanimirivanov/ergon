# Capability invocations

> **TL;DR:** Invoke an immutable authorization consumption once through its
> selected connector. Every retry uses the consumption ID as its provider
> idempotency key and returns the same durable terminal receipt. Connector
> success is not independent outcome proof.

Invoke reserved work with
`POST /internal/v1/tenants/{tenantId}/capability-authorization-consumptions/{consumptionId}/invocations`.
The command accepts no body. A first durable result returns `201`; replay of an
existing receipt returns `200` without another connector call. An absent or
cross-tenant consumption returns `404`, while an uninstalled configured
connector returns `503` without creating a receipt.

The application reads the consumption and any receipt in a short transaction,
closes that transaction, and only then calls the connector. It persists the
terminal result in a second transaction. Concurrent or crash retries can reach
the connector, but all carry the same idempotency key and therefore address one
provider operation. Database uniqueness retains one immutable receipt.

The first adapter supports `identity-stub` with
`identity.account.unlock`. It is deterministic and needs no network or
credentials. The local-only `identity-stub-failure` route deterministically
exercises terminal failure and explicit retry. The command carries tenant, run,
case, step, capability, and connector scope; a general connector-input schema is
not yet defined.

Receipts preserve the authorization scope, terminal `SUCCEEDED` or `FAILED`
outcome, opaque provider reference, completion time, and database recording
time. Completion may follow grant expiry because consumption already reserved
the grant while current. A receipt records the connector's claim only; it does
not close the run or prove that the requester can sign in. The separate
[run capability-result command](runs.md#record-a-capability-result) projects the
claim into `VERIFYING` or `ACTION_FAILED` while preserving that distinction.

## Measured execution

New receipts also retain the runtime's wall-clock start observation and
monotonic elapsed nanoseconds around the connector gateway call. Database reads,
waiting after consumption and receipt persistence are excluded. Wall-clock
timestamps are normalized to PostgreSQL microseconds; subtracting them is not
the measurement and clock correction may place start after completion.

The measurement describes the caller whose receipt became durable, not the
provider's internal latency or the sum of uncertain/concurrent calls. Replays
return that exact record without sampling again. A thrown connector exception
still leaves the result unknown and creates neither receipt nor timing. Retry
the original consumption only under its existing provider-idempotency contract.

Historical and older-writer receipts have absent timing; there is no backfill
or inferred zero. A genuinely measured zero remains valid because clock
resolution can be coarser than its nanosecond units. Cost is not measured or
reported. The assigned browser projection is documented in
[resolution runs](runs.md#interpret-measured-execution), under
[ADR 0046](decisions/0046-record-runtime-capability-invocation-timing.md).
