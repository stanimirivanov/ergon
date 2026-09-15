# 0026. Require resolver authority for explicit retry

- Status: proposed
- Date: 2026-09-15
- Owners: Ergon maintainers

## Context

Starting a successor changes durable run state and can lead to another external
action. Tenant and run identifiers are routing data, not authorization. Leaving
the internal command unauthenticated would let any caller who reaches it create
new recovery work and obscure who initiated that decision.

## Decision

Require a verified bearer identity registered in the route tenant and a current
tenant-wide `RESOLVER` attestation before starting or replaying a retry. Resolve
the actor from trusted token claims; accept no actor or evidence identifier from
client input. Record both identities on every newly created `RETRY_STARTED`
event and bind them to the same resolver attestation with a database foreign
key.

Keep the route internal. Authentication authorizes the recovery request, not
the successor's capability: that run still starts with fresh policy-derived
approval and authorization requirements.

## Alternatives

Requester authority was rejected because recovery can affect tenant operations
beyond the requester's approval scope. Bearer authentication without current
authority evidence was rejected because identity alone conveys no permission.
A new recovery-role subsystem was deferred because the existing immutable
resolver attestation has the required tenant scope and validity semantics.

## Consequences

Unauthenticated, unregistered, requester-only, expired, and cross-tenant callers
cannot start a successor. New retry events are attributable to durable evidence.
Legacy retry events remain readable without synthetic attribution. Retry limits,
failure classification, backoff, compensation, and escalation remain separate
policy decisions.

## Verification

Domain tests enforce resolver-only attribution. PostgreSQL-backed HTTP tests
cover authentication, current authority, tenant isolation, durable attribution,
first-write and replay behavior, and immutable history. Migration tests exercise
both empty and supported upgrade schemas.
