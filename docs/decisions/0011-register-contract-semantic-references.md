# 0011. Register contract semantic references

- Status: proposed
- Date: 2026-09-12
- Owners: Ergon maintainers

## Context

The contract grammar accepts qualified fact and capability names so the DSL can
remain general. Syntax alone cannot catch a misspelled fact, an undefined
operation, or a name whose meaning differs between implementations. Persisting
such a revision would defer an authoring error until selection or execution.

Tenant connector availability and authorization are different questions. A
fact can have stable system meaning while a tenant currently has no source for
it, and a known capability must never imply permission to invoke it.

## Decision

Require publication to resolve every applicability, evidence, outcome-proof,
and step-capability name through a system reference registry. Report all unknown
occurrences with their document paths and do not persist the revision.

Begin with an in-process built-in registry containing the two definitions used
by the access-restoration slice: `account.access.state` and
`identity.account.unlock`. The registry is behind an application port so a
later plugin or durable catalog can replace its adapter when actual behavior
requires it.

Treat registered names as compatibility boundaries. Do not remove or repurpose
one while published revisions depend on its meaning; deprecation and removal
require an ADR and a compatibility plan.

Keep standalone document validation structural and context-free. Registration
does not establish tenant availability, grant authority, or execute anything.

## Alternatives

Accepting arbitrary qualified names was rejected because typos would become
durable configuration. Treating publication as proof of tenant availability
was rejected because connector installation and health change independently.
A database-backed registry was deferred because the first slice has two static
definitions and no administrative lifecycle that would justify mutable state.

## Consequences

Newly published revisions use known semantic vocabulary and authors receive one
complete correction response. Adding a definition is an application change
until a plugin lifecycle requires durable registration.

Revisions published before this gate are not retroactively certified. Future
selection and execution must resolve references again and fail closed, which is
required independently because runtime plugins and tenant availability can
change.

## Verification

Integration tests prove that structural validation still accepts qualified
unknown names, publication rejects all unknown fact and capability occurrences,
the failed publication stores nothing, and the built-in access-restoration
contract remains publishable.
