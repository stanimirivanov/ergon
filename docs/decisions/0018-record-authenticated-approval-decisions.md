# 0018. Record authenticated approval decisions against current authority

- Status: proposed
- Date: 2026-09-14
- Owners: Ergon maintainers

## Context

An approval request names required authority but deliberately contains no
actor. Answering it must attribute a verified human without accepting identity,
authority, scope, or time from client-controlled fields. The durable record must
also remain meaningful after the request or authority evidence expires.

## Decision

Expose one bearer-authenticated command that accepts only `APPROVED` or
`REJECTED`. Resolve the actor from the verified JWT issuer and subject. In one
transaction, lock the tenant-scoped request, reject an existing decision, use a
single application-clock instant to verify request validity, and select current
authority evidence for that actor and the request's exact scope.

Store one immutable decision per request with copied request, run, case, actor,
evidence, authority, outcome, and decision-time attributes. Composite foreign
keys prove that copied attributes belong to the referenced immutable records.
Database uniqueness and a mutation trigger enforce one-response and append-only
history. An approved decision is evidence of a human prerequisite, not a
capability grant, run transition, or authorization to execute.

## Alternatives

Accepting actor or evidence IDs in the request body was rejected because those
identifiers do not prove identity or current authority. Embedding mutable
decision state in the approval request was rejected because it would erase the
separate meanings of request and response. Treating `APPROVED` as immediate
authorization was rejected because later policy checks need a narrow,
independently auditable grant boundary.

## Consequences

Retries deterministically identify the decision already recorded, even if its
authority evidence later expires. Requester evidence is case-scoped and resolver
evidence tenant-scoped; both approval and rejection require the requested
authority. Evidence revocation, multiple approvers, decision withdrawal,
derived grants, run advancement, and execution remain future decisions.

## Verification

Domain tests cover exclusive expiry boundaries, authority mismatch, and scope
mismatch. PostgreSQL-backed HTTP tests cover bearer authentication, missing and
expired evidence, wrong case scope, expired requests, one-response uniqueness,
immutable rows, response attribution, and OpenAPI publication. Migration tests
exercise both an empty database and the supported previous schema.
