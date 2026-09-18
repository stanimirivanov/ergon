# 0032. Adopt Ergon build coordinates

- Status: proposed
- Date: 2026-09-18
- Milestone: M08 - Ecosystem and production readiness
- Deciders: Ergon maintainers
- Supersedes: none

## TL;DR

Build Ergon modules as `org.ergon` artifacts under an `ergon` parent and publish
future container images beneath the `ergon` namespace. Keep the existing GitHub
SCM URL and local PostgreSQL identity unchanged until their separate external
and persisted-state migrations are explicitly approved.

## Context

Every predecessor executable has been retired, but the Maven reactor still
identifies the active Ergon modules through `org.raghc:rag-help-center`, and Jib
still targets a `rag-help-center` image namespace. Those identifiers imply a
compatibility relationship that the rewrite deliberately rejected and make
new build output look like predecessor output.

The repository has no release tags or documented artifact consumers, so the
build identity can change before a public compatibility promise exists. The
GitHub repository URL remains factually named `rag-help-center`; changing it is
an external operation. PostgreSQL defaults may address existing local data and
therefore require a separate migration and rollback decision.

## Decision

Use `org.ergon:ergon` for the reactor parent. Let `domain-kernel` and
`control-plane` inherit `org.ergon` and reference each other through that group.
Configure future Jib output beneath
`${docker.registry}/${env.OCIR_NAMESPACE}/ergon`.

Do not rename Kotlin packages because active code already uses `org.ergon`. Do
not change the SCM URL, repository directory, PostgreSQL database, user,
credentials, or named volume in this decision.

## Alternatives considered

### Retain predecessor build identifiers

- Benefits: no Maven or image-coordinate change.
- Costs and risks: new Ergon artifacts remain indistinguishable from
  predecessor project output.
- Reason not selected: there are no published releases to protect, and the
  identifiers no longer describe the product.

### Rename repository and database identities in the same change

- Benefits: removes every remaining predecessor name at once.
- Costs and risks: repository renaming is an external operation, while database
  and volume changes can detach or strand local persisted state.
- Reason not selected: those migrations need independent authority, rollout,
  rollback, and data-preservation decisions.

## Consequences

### Positive

Maven dependency graphs and future image references consistently identify
Ergon. New modules can inherit one product-aligned coordinate namespace.

### Negative

Local Maven caches rebuild artifacts under new paths. Any undocumented consumer
of the old coordinates must update explicitly. Future images publish to a new
path rather than replacing an old predecessor image.

### Neutral or follow-up

The checked-in SCM URL and local PostgreSQL examples still contain the actual
repository and database names. Their presence is deliberate, not an alias for
new artifact coordinates.

## Compatibility and migration

This is a pre-release coordinate break with no tagged release or repository-
documented downstream consumer. The next build produces `org.ergon` artifacts;
no artifact relocation POM is provided. Previously built local artifacts and
remote images are neither overwritten nor deleted.

Rollback restores the previous POM coordinates and image namespace. Database
state is unaffected.

## Security and operations

Registry ownership, authentication, and secret injection do not change. Image
publication still requires the existing OCI credentials. Operators must use
the new image namespace after the first image publication from this decision.
Publishing `org.ergon` artifacts to a public Maven repository requires verified
namespace ownership or a superseding coordinate decision; this change
establishes only the repository's pre-release build identity.

## Validation

The complete Maven reactor must resolve the renamed parent and inter-module
dependency, pass all tests and static checks, and report only `org.ergon`
project coordinates. A tracked-file scan must leave predecessor naming only in
the factual SCM URL, explicitly deferred database defaults, and historical
documentation.
