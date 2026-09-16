# Current implementation state

## TL;DR

- `domain-kernel` and `control-plane` contain the active Ergon implementation.
- The first access-restoration vertical slice now reaches verified resolution,
  bounded retry, explicit escalation, and durable human follow-up using
  deterministic local adapters.
- The other executable services implement the predecessor RAG Help Center and
  remain buildable while capabilities are replaced incrementally.
- Internal endpoints are development boundaries and must not be treated as a
  production authorization model.
- The next human-work outcome is explicit queue, priority, ownership, and
  assignment semantics.

## Active Ergon slice

The current slice can:

1. open an append-only case with optimistic stream concurrency;
2. record attributable connector observations;
3. bind typed account-access facts to source observations;
4. validate, publish, and pin immutable resolution-contract revisions;
5. evaluate readiness and deny-by-default policy requirements;
6. start an immutable resolution run pinned to case, contract, policy, step,
   capability, risk, and approval meaning;
7. record scoped, expiring human authority evidence and resolve authenticated
   JWT subjects to tenant actors;
8. request and record one approval decision;
9. derive, consume, and invoke a narrowly scoped capability grant through a
   deterministic identity connector;
10. store/replay a terminal provider receipt and project it into run state;
11. assess and atomically accept post-action outcome proof, closing the case
    only as `VERIFIED_RESOLVED`;
12. start one attributed successor for a failed run under a versioned retry
    ceiling; and
13. record explicit resolver escalation when that retry budget is exhausted,
    leaving the case open; and
14. atomically open and retrieve one immutable-source human follow-up work item
    for that escalation.

Detailed HTTP semantics live with their capabilities:

- [contracts](../contracts.md)
- [policy](../policy.md)
- [runs and outcome proof](../runs.md)
- [approvals](../approvals.md)
- [human authority](../human-authority.md)
- [authentication](../authentication.md)
- [authorization grants](../authorization-grants.md)
- [capability invocation](../capability-invocations.md)
- [human follow-up](../human-follow-up.md)

## Runtime and data

`control-plane` is a Spring Boot MVC application backed by PostgreSQL and
Flyway. Its active configuration requires:

- `ERGON_DATABASE_URL`
- `ERGON_DATABASE_USERNAME`
- `ERGON_DATABASE_PASSWORD`

Human-authenticated endpoints also use:

- `ERGON_HUMAN_JWT_ISSUER_URI`
- `ERGON_HUMAN_IDENTITY_PROVIDER`

When the JWT pair is absent, protected operations fail closed while the
application can still start for unprotected development behavior. See the
[control-plane README](../../control-plane/README.md).

## Rewrite boundary

`ingestion-service`, `embedding-worker`, `retrieval-service`, `qa-service`, and
`gateway` retain the predecessor article/RAG behavior. They are not the target
Ergon topology and receive no new product capability unless a migration slice
needs a verified extraction.

Reusable behavior includes append concurrency, separate occurrence/recording
time, transactional outbox, idempotent consumers, deterministic chunking,
hybrid retrieval, tenant-isolation tests, and citation validation. The old
article aggregate, Q&A contract, MongoDB conversation model, five-service
topology, and provisional tenant header are not compatibility requirements.

## Deliberate limitations

- No follow-up assignment, queue, priority, due-time, lifecycle transition, or
  notification.
- No automatic resolution-contract selection or multi-step interpreter.
- No AI-assisted semantic binding or Ergon evidence compiler.
- Only one built-in access-restoration vocabulary and deterministic connector.
- No public production authorization model for internal endpoints.
- No compensation engine, failure classification/backoff, autonomous retries,
  simulation lab, or improvement proposal generator.
- No resolver console, adaptive canvas, or public SDK.

The intended sequence is maintained in
[implementation milestones](../roadmap/milestones.md). Update this document
when a merged capability materially changes what the repository can do.
