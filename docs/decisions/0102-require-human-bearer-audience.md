# ADR 0102: Require a human bearer API audience

- Status: Accepted
- Date: 2026-11-04
- Milestone: M03 - Policy and Human Authority
- Deciders: Ergon maintainers
- Supersedes: None
- Superseded by: None

## Context

[ADR 0017](0017-authenticate-human-actors-from-jwt-subjects.md) established
issuer and subject validation. One issuer can sign tokens for several services.
Without checking the audience, a token intended for another service can reach
human endpoints if the subject maps to an Ergon actor.

## Decision

Require the configured human API audience on protected human bearer routes
after signature, issuer, and time validation. Missing or mismatched audience
denies access. A blank setting or the reserved machine audience also denies
human bearer access. Keep machine assignment and browser sessions on their
existing policies. Follow [RFC 8725, section 3.9](https://www.rfc-editor.org/rfc/rfc8725.html#section-3.9).

## Alternatives considered

### Validate the human audience in the shared decoder

- Benefits: Centralizes token validation.
- Costs and risks: Rejects machine assignment tokens before their own policy can run.
- Reason not selected: The routes share an issuer and decoder but serve different audiences.

## Consequences

### Positive

Tokens for unrelated services no longer authorize human bearer requests.

### Negative

Human API deployments must coordinate a dedicated audience with their issuer.

### Neutral or follow-up

Actor binding and application authority checks still apply after bearer authorization.

## Compatibility and migration

Configure the issuer to emit the dedicated audience and set
`ERGON_HUMAN_JWT_AUDIENCE` before deploying. Previously issued tokens without
the new audience need replacement. There is no database migration. Rolling
back the application restores the previous issuer-only bearer policy.

## Security and operations

Use a distinct audience for the human resource server and issue it only to
appropriate callers. The machine assignment policy still checks its own
audience, scope and tenant. Browser OIDC uses the deployment's client policy.

## Validation

Exercise real signature validation and discovery using a loopback issuer.
Check accepted human and machine requests, missing or unrelated token audiences,
and missing audience configuration. Run existing tenant and browser tests.
