## Issue and milestone

- Closes #
- Milestone: MNN - Outcome

## Problem and resulting behavior

## Scope and exclusions

## Design and compatibility decisions

- ADRs:
- Assumptions:
- Known limitations:

## Verification

| Command or check | Result | Evidence or reason not run |
|:--|:--|:--|
| `./mvnw -B -ntp -pl :repository-policy verify` | | |
| `./mvnw -B -ntp verify` | | |

Checks not run, blocking conditions, and residual risk:

## Migration, rollout, and rollback

## Security and operations

## Review checklist

- [ ] One coherent capability; unrelated changes are excluded.
- [ ] Public and persisted contracts have a compatibility plan.
- [ ] Success and relevant negative paths are verified.
- [ ] Tenant, authorization, concurrency, and idempotency risks are covered.
- [ ] Documentation, ADRs, and module READMEs are current.
- [ ] Migrations pass empty and supported-upgrade paths where applicable.
- [ ] Repository policy passes.
- [ ] No secrets, production data, generated noise, or hidden skipped checks.
