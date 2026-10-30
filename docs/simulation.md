# Simulation fixtures

## TL;DR

M07-PR1 defines the pure-domain `ergon.simulation/v1` manifest and its semantic
SHA-256 identity. It does not register, load or execute artifacts, assess claims,
produce evaluation reports, or authorize promotion. See [ADR 0090](decisions/0090-pin-simulation-fixture-identity.md).

## Pinned meaning

`SimulationFixture` pins tenant, contract and policy artifacts, an exact case ID
and positive event stream prefix, evaluator artifact, evaluation instant,
documentary attachments, ordered connector/model scripts, optional model
configuration, assertion artifact, expected outcome and optional budget artifact.
The prefix material includes the evidence bindings at that version; later events
are excluded. Consumers must verify resolved bytes against each artifact digest
and validate their own supported material schema before replay. A matching digest
alone establishes neither trust nor authority.

Each artifact carries a key and revision of 1–128 ASCII letters, digits, dots,
underscores or hyphens, starting with an alphanumeric character, plus exactly 64
lowercase hexadecimal SHA-256 characters. These are identifiers, not URLs, code,
file paths or fetch instructions. Raw provider responses and assertion programs
are not embedded in this manifest. Artifact material formats belong to the later
consuming slices; unsupported material must not be interpreted as executable code.

Fixtures allow at most 20 documentary pins and 100 script entries. Documentary
keys and attachment stream versions are unique, positive and within the pinned
case prefix. Attachment instants cannot follow evaluation time. All instants must
be exactly representable at microsecond precision; no silent rounding occurs.
Claim validity remains evaluated at the server-recorded attachment instant, not
at evaluation time. Attachment metadata must subsequently be checked against the
resolved case prefix; domain construction does not certify external material.

Scripts have unique artifact keys in execution order. Repeated calls require
separately named script entries, even when they reference the same content hash.
A model configuration is required exactly when a MODEL script occurs. The manifest
contains no live-adapter fallback. Budget material must later define explicit
units and pricing; absent budget means unspecified, never zero cost.

Expected outcomes are NOT_READY, POLICY_DENIED, WAITING_FOR_APPROVAL, VERIFIED,
FAILED, ESCALATED and CANCELLED. They describe fixture expectations, not observed
results. They never grant runtime authorization or establish accepted proof.

## Canonical identity

The canonical bytes are a hash preimage, not an HTTP or persistence wire format.
Every integer is signed big-endian. Text is a four-byte UTF-8 byte length followed
by exactly those bytes. An artifact is key, revision and digest as three texts.
An instant is eight-byte epoch seconds followed by four-byte nanoseconds. Optional
artifacts start with one byte (0 absent, 1 present), followed by the artifact if present.

The v1 field order is:

1. Schema version and lowercase hyphenated tenant UUID, as text.
2. Contract artifact, then policy artifact.
3. Lowercase hyphenated case UUID, eight-byte stream version, prefix artifact.
4. Evaluator artifact, then evaluation instant.
5. Four-byte documentary pin count; pins sorted by ascending stream version,
   each containing bundle artifact, eight-byte stream version and attachment instant.
6. Four-byte script count; scripts in supplied order, each containing kind name
   as text followed by material artifact.
7. Optional model configuration, assertion artifact, expected outcome name as
   text, then optional budget artifact.

SHA-256 is rendered as lowercase hexadecimal. Canonical content is limited to
65,536 bytes. Collections are frozen on construction and byte access returns a
fresh array. Recording IDs, actors and recording timestamps are outside this
manifest. Tenant scope deliberately participates in identity. Artifact keys and
revisions participate even when their content hashes match. There is no Unicode
normalization, locale-dependent sorting or wall-clock input.

Changing the encoding, enum meanings, normalization or identity fields requires a
new schema version. Unknown fixture versions fail closed. No decoder is introduced
in this slice; future adapters must reject unknown fields/enum values before
constructing these domain values.

## Verification

Run `./mvnw -B -ntp -pl domain-kernel test`, then repository policy and the full
`./mvnw -B -ntp verify`. The domain suite includes an independently calculated
binary identity vector, input sensitivity, ordering, mutation resistance, bounds,
unsupported version, model configuration and historical timestamp tests.
