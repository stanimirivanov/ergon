# ADR 0075: Disclose protected requester answers

- Status: Accepted
- Date: 2026-10-26
- Milestone: M05 - Adaptive Resolution Experience
- Deciders: Ergon maintainers
- Supersedes: none
- Superseded by: none

## Context

PR21a/b persist one final answer per question and authenticate requester recording
and exact recovery. The existing protected histories need narrow answer disclosure
before PR22's UI; possession of a question or response UUID grants no access.

## Decision

Add nullable response to each existing requester and resolver question-history
item. A non-null response contains only responseId, answer, respondedAt and
recordedAt, with exact persisted text and timestamps. Null means no answer in
that read snapshot, not delivery failure or permanent absence. Keep question POST
receipts separate and unchanged.

Retain ADR 0072's access: requester ownership and minimal columns share one SQL
snapshot, surviving case closure and resolver changes. Resolver history requires
current ownership, active work, an OPEN explicitly bound case and fresh authority
after work/case waits and selection, including predecessor questions and answers.
Join answers by tenant/question in the same query. The unique question/response
relationship cannot multiply rows. Keep newest question order, 100-item bound,
101-row truncation probe, empty pages, neutral errors, audience isolation and
no-store unchanged. Do not load private requester/provider attribution.

## Alternatives considered

Separate response endpoints would duplicate authorization and require client
joins and additional requests for the same bounded history. Extending write
receipts would conflate immutable acknowledgements with changing read snapshots.
Answer-first ordering would destabilize the established question boundary.

## Consequences

Answers become readable without inferring consent, delivery, facts, evidence
acceptance or proof. Reads change no case/work/run revisions. History remains
bounded; answers to omitted older questions remain omitted. UI and guide follow
backend review/merge; this does not add answer editing or notifications.

## Compatibility and migration

This is an additive GET-only member; existing POST receipts stay byte-shape
compatible. Old readers may ignore response; new readers must tolerate its absence
during mixed-version deployment. Deploy the existing V20261026102606 schema before
readers. Existing unique and history indexes support the joins/order; no migration,
dependency or flag is added. Roll back application code without deleting history.

## Security and operations

Treat answer text as confidential, untrusted plain text, never executable markup
or model authority. Keep it out of logs. Existing session isolation and disabled
browser behavior remain. Requester binding is immutable; any later transfer must
explicitly redesign historical visibility. Direct SQL privileges remain an
operator boundary; this is not row-level security.

## Validation

PostgreSQL/HTTP tests cover answered/unanswered rows, exact narrow fields, original
timestamps, deterministic bounds, identical question IDs across tenants, foreign
actors/cases, predecessor ownership, release/closure and authority-expiry withdrawal.
Existing requester session/disabled-browser checks remain required, as do repository
policy, formatting/static analysis and reactor verification.
