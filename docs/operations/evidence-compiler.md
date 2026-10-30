# Evidence compiler operations

## TL;DR

M06 - Evidence Compiler supports bounded synchronous CommonMark and OpenAPI 3.0
JSON compilation in the control plane. An authorized human binds and reviews
attributable documentary proposals; exact accepted/adjudicated references form
an immutable bundle. Contracts consume that bundle through a case attachment,
with selected claim validity evaluated at the database attachment time.

This is historical documentary qualification. Operational observations,
capability policy, approvals, execution admission and outcome proof remain
independent. See the [acceptance record](../roadmap/m06-acceptance.md) for evidence
and the explicit Docker and live-deployment gaps.

## Prerequisites and trust boundaries

Use the supported Java/Maven environment and a PostgreSQL schema migrated through
`V20261030120000__consume_case_documentary_bundles.sql`. Docker is required for
Testcontainers verification. A browser source/evidence workflow needs a configured
workbench session, fresh tenant-wide RESOLVER authority and CSRF for writes.
Tenant IDs are context, never authority; source, review and bundle reads recheck
access. The existing internal contract/case endpoints require their separate
trusted machine boundary. Do not expose them as browser APIs.

The compiler stores exact UTF-8 bytes in PostgreSQL, with a SHA-256 revision and
original attribution. Source input is capped at 65,536 bytes. Markdown is parsed
as inert syntax; links and HTML are never fetched or rendered as trusted HTML.
OpenAPI accepts bounded 3.0 JSON and local references only, rejecting duplicate
keys, unsupported/external references and resource limits. It never invokes the
described API. Unsupported formats fail explicitly; no background job or live
model provider is implied.

## Operator sequence

1. Register exact source bytes through
   `POST /bff/v1/tenants/{tenantId}/sources/{sourceId}/revisions/{digest}` with
   `text/markdown` or `application/vnd.oai.openapi+json`. Compute the digest from
   the exact submitted bytes. Keep source IDs and revision digests, not an
   ambiguous latest-document name. Exact retries retain original attribution;
   changed same-revision content conflicts.
2. Inspect the exact revision, then its `GET .../compilations/commonmark-v1` or
   `GET .../compilations/openapi-3.0-v1` resource. Review extraction identity,
   compiler revision, observation index, code-point span, quote and JSON pointer
   where applicable. Citations refer to immutable original text, not normalized
   parser models. A successful parse does not establish semantic truth.
3. Propose claims under the source revision's `/claim-proposals/{id}` or ordered
   procedures under `/procedure-proposals/{id}`. Explicit binding supplies typed
   property/value, subject, scope and validity. The server chooses quotations and
   validates exact extraction/selection; the request cannot submit an uncited
   claim as accepted evidence. Procedures may reference exact documented API
   operations; those references grant no permission to execute.
4. Use Evidence review to inspect pending proposals and explicitly accept/reject
   with a rationale. Review stores exact proposal/extraction, reviewer and current
   authority proof. Unknown validity remains unknown; acceptance does not invent
   intervals or promote a documentary statement to an operational fact.
5. Inspect an accepted anchor through Review contradictions. The complete typed
   candidate set and revision distinguish conflict, undetermined comparison and
   insufficient candidates. Explicit adjudication records a preference or other
   supported disposition without rewriting either claim. Reacquire changed
   candidate sets; do not apply old intent to newly accepted alternatives.
6. Publish an exact bundle through the existing evidence workflow or
   `POST /bff/v1/tenants/{tenantId}/evidence-bundles/{id}`. Preserve command ID,
   selected identities, complete candidate-set inputs and adjudication identity.
   The short database admission transaction repeats authority, epoch and review
   checks. The immutable manifest retains alternatives as context, exact source
   revisions, selected proposals, schema, digest and assessment cutoff.
7. A trusted contract author publishes `ergon.dev/resolution-contract/v1alpha2`
   with the exact bundle ID/digest through the existing internal contract revision
   endpoint. See [contract syntax](../contracts.md) and
   [ADR 0088](../decisions/0088-declare-exact-documentary-contract-requirements.md).
   The browser evidence route does not author contracts.
8. Pin the contract on an OPEN case, then attach the exact bundle with
   `POST /internal/v1/tenants/{tenantId}/cases/{caseId}/documentary-bundles`,
   quoted `If-Match` and `{ "bundleId": "...", "sha256": "..." }`.
   A case supports 20 distinct immutable attachments and no replacement command.
   Client timestamps cannot choose the attachment validity instant.
9. Inspect internal case resolution readiness or protected run-pinned readiness.
   Missing documentary requirements precede unsuitable ones, then ordinary
   operational requirements/applicability. Each selected claim must cover the
   same database attachment instant on `[validFrom, validUntil)`. Unknown bounds,
   a later manifest cutoff, or out-of-interval claims are unsuitable. A
   procedure-only bundle has no selected claim interval. Satisfied documentary
   requirements do not supply `account.access.state` or approve a run.

## Recovery and operational limits

After an uncertain write, preserve exact command identity and intent for explicit
receipt recovery; do not create replacement intent merely because a response was
lost. A stale extraction, candidate set or case `If-Match` requires a fresh read
and an explicit new decision. A generic unavailable response must not be treated
as empty evidence or proof that a foreign identity exists.

Attachment accepts temporally unsuitable bundles so history retains the attempted
binding and readiness explains the failure. It does not select an alternative,
refresh a claim, or replace a pin. Attachment-time qualification remains stable
as wall time advances; this is not continuing freshness. An operator needing a
different documentary requirement must use separately reviewed new immutable
contract/bundle identities and the existing case lifecycle, not mutate storage.

Database/integrity failures propagate as failures, rather than becoming missing
requirements. Retain immutable records and investigate source/citation/digest,
projection identity and exact stream boundaries. Do not repair a failure by
editing old events, manifests, claim intervals or Flyway checksums. Readers for
new events must precede writers; once attachments exist, application rollback
must keep compatible readers and recover forward. Details are in
[ADR 0089](../decisions/0089-consume-case-pinned-documentary-evidence.md).

## Verification and evidence

Run from the backend root:

```bash
./mvnw -B -ntp -pl :repository-policy verify
./mvnw -B -ntp -pl control-plane -am test -Dtest='CompilerFixtureIntegrityTest,SourceToContractIntegrationTest' -Dsurefire.failIfNoSpecifiedTests=false
./mvnw -B -ntp verify
```

The offline fixture checks verify source digests, deterministic parsing, exact
citations and forged quotation rejection. The Spring acceptance test exercises
actual use cases, configured parsers and PostgreSQL from source registration to
case consumption; Docker skips are not passes. Existing boundary tests add
migration, concurrency, tenant, authority and immutable-prefix coverage.

In `ergon-ui`, `pnpm guide:check` validates chapter metadata and
`pnpm guide:generate` records the asserted simulated guides and assembles the
ignored `dist/user-guide` book. `pnpm verify` is the full UI gate. The evidence
chapter is `evidence-review-and-bundles`; it demonstrates existing workbench
behavior, not backend compilation, contract authoring, documentary readiness UI
or a live two-app/OIDC deployment. Never use a simulated guide as server
acceptance or production-readiness evidence.
