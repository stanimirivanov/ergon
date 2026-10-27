# ADR 0077: Authorize requester case intake

- Status: Accepted
- Date: 2026-10-27
- Milestone: M05 - Adaptive Resolution Experience
- Deciders: Ergon maintainers
- Supersedes: none
- Superseded by: none

## Context

PR23a supplies atomic intake and historical recovery without a browser entry point.
PR23b must establish creator identity without treating a client case UUID, supplied
actor hint or requester approval attestation as authority. Unexpected failures must
not become input rejection that invites replacing a possibly committed intent.

## Decision

Allow only POST `/bff/v1/tenants/{tenantId}/requester-cases` in the separately
enabled requester deployment. Require its fixed OIDC client, session CSRF and a
registered tenant actor resolved from the trusted issuer/subject. Workbench denies
the collection explicitly. Disabled browser configuration returns the existing
503 problem. No collection GET, delegation, bearer intake or implicit grant.

The body has `caseId`, `goal`, `initialObservation`. Unknown members are ignored;
actor/authority hints cannot select ownership. Invoke ADR 0076's use case once.
Return no-store 201 for creation and 200 for exact normalized recovery, with only
`caseId`, `goal`, `initialObservation`, `openedAt`, `recordedAt`. These are original
input/opening times, never current status, proof or continuing access. Recovery
after closure remains historical; reacquire current progress independently.

Conflicting intent/creator and legacy collisions share a 409 intake-conflict
problem without private values. Malformed JSON/UUID and validated input failures
share the diagnostic-free 400 invalid-intake problem. Introduce a typed subclass
of IllegalArgumentException only around input normalization, preserving its cause
internally and existing source catches. Never translate arbitrary clock, identity,
storage or codec IllegalArgumentException failures into an editable 400 rejection.
Authentication/tenant and CSRF failures retain their established BFF problems.

## Alternatives considered

Using the legacy public case writer leaves cases unowned and lacks session/CSRF
boundaries. Browser-supplied actors invite impersonation. Broad exception mapping
misclassifies stored corruption as input rejection. A new identity/idempotency
framework or duplicate receipt store adds no required guarantee.

## Consequences

Registered requesters can create their own cases, but gain no approval, resolver,
execution or proof authority. No run starts and no connector is invoked. Clients
retain the original UUID/text after uncertainty and must not automatically retry
or mint replacements. PR24 UI and its executable guide follow backend review/merge.

## Compatibility and migration

Additive browser contract; no schema, dependency or flag. Existing requester
deployment remains opt-in. Deploy PR23a and the existing ownership schema first.
Rollback the browser adapter or block this POST to stop new creation; preserve
immutable events/bindings and compatible reads. Existing legacy writers and answer
commands retain their contracts. The new typed input error remains an
IllegalArgumentException subtype; unexpected failures remain defects.

## Security and operations

Use the separate HTTPS requester origin/client/cookie and private database roles.
The session selects the actor; untrusted text is data, never markup or ownership.
No provider identifiers, credentials, SQL diagnostics or internal state enter the
receipt/problems. No remote calls run inside intake transactions. Existing session
and immutable retention limits remain; deployment request-size/rate controls and
live two-origin/IdP acceptance are not introduced or certified by this slice.

## Validation

Compile production and test sources, formatting, static/policy checks and focused
non-container tests locally. CI exercises PostgreSQL-backed HTTP creation/replay,
fresh progress, private conflicts, ignored actor hints, session/client/tenant/CSRF
denials and malformed input. Unit tests distinguish input rejection from codec
and identity defects; workbench/disabled regressions retain fail-closed behavior.
The full CI aggregate remains required; UI/guide tests belong to PR24.
