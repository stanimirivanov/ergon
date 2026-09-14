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

The first adapter supports only `identity-stub` with
`identity.account.unlock`. It is deterministic and needs no network or
credentials. The command carries tenant, run, case, step, capability, and
connector scope; a general connector-input schema is not yet defined.

Receipts preserve the authorization scope, terminal `SUCCEEDED` or `FAILED`
outcome, opaque provider reference, completion time, and database recording
time. Completion may follow grant expiry because consumption already reserved
the grant while current. A receipt records the connector's claim only; it does
not close the run or prove that the requester can sign in.
