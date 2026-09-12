# 0007. Bind account state to source observations

- Status: proposed
- Date: 2026-09-12
- Owners: Ergon maintainers

## Context

The case stream records attributable observations but cannot yet represent a
typed fact derived from them. The access-restoration slice needs to distinguish
raw connector content such as `status=LOCKED` from the semantic account state
used by contracts and policy. A generic fact graph would introduce abstractions
before a second fact shape proves what they must share.

## Decision

Add the concrete `AccountAccessStateBound` case event with `ACTIVE` and `LOCKED`
states. A binding must reference an earlier connector observation in the same
case. Its account reference is inherited from that observation, never supplied
independently by the binder, and one observation can establish this property
only once. A changed state requires a new observation and binding.

Persist the event in the authoritative case stream and update a tenant-scoped
account-access fact projection in the same transaction. Preserve the source
observation ID, binding time, database recording time, and stream version in the
read model.

## Consequences

Raw source material and semantic meaning remain separate and traceable. A
binder cannot reattribute evidence to another account, duplicate delivery
cannot create a second state fact for one observation, and fact reads do not
need to decode event payloads.

This first fact is deliberately concrete. It does not define a universal value
schema, confidence score, contradiction model, or AI binder. The internal API
accepts a typed proposal without yet identifying the binding actor; identity
and authorization remain required before external exposure.
