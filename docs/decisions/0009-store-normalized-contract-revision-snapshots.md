# 0009. Store normalized contract revision snapshots

- Status: proposed
- Date: 2026-09-12
- Owners: Ergon maintainers

## Context

Resolution runs must eventually pin contract meaning that survives authoring
format changes and parser upgrades. Keeping only YAML would make runtime state
depend on reparsing author syntax. Fully relational step and condition tables
would optimize queries that the first vertical slice does not need.

## Decision

Store each validated revision once in `resolution_contract_revisions`, scoped
by tenant, contract key, and positive revision. Keep the schema identifier and
database recording time relational. Store the normalized, schema-versioned
definition as JSONB and reconstruct it through the matching codec and domain
invariants.

Reject duplicate identities rather than replacing them. Prevent UPDATE and
DELETE in PostgreSQL so immutability does not depend on application behavior.
Do not retain authoring YAML as runtime state.

## Alternatives

Raw YAML alone was rejected because later reads could change meaning after a
parser upgrade. Fully normalized condition, evidence, and step tables were
deferred until a demonstrated query needs them. Event sourcing was rejected
because contract publication has no state transition beyond adding immutable
revisions.

## Consequences

Pinned revisions can later refer to stable normalized meaning, while new schema
versions can add codecs without rewriting old rows. Definition fields are not
directly queryable without JSON expressions; add relational projections only
for proven selection or reporting queries.

Administrative retention and tenant-decommission behavior need an explicit
privileged design because ordinary deletion is deliberately blocked.

## Verification

Integration tests publish and read the executable example, reject replacement,
prove cross-tenant isolation, reject direct mutation, and migrate both an empty
database and the previously released schema.
