# ADR 0066: Complete owned follow-up with exact recovery

- Status: Accepted
- Date: 2026-10-24
- Milestone: M05 - Adaptive Resolution Experience
- Deciders: Ergon maintainers
- Supersedes: none
- Superseded by: none

## Context

ADR 0065 supplies immutable terminal facts and compatible active-work guards.
M05-PR11b needs a browser producer whose successful response can be lost after
commit. Active-owner gates cannot recover that success because completion ends
active access. Recovery must not become a completed-work directory or private
case/notes bypass.

## Decision

POST `/bff/v1/tenants/{tenantId}/human-follow-ups/{workItemId}/completions`
accepts command ID, claim ID and expected positive ownership revision. Resolve
the actor from the OIDC session; shared BFF filters enforce CSRF and no-store.

In one short READ COMMITTED transaction lock the immutable work row using the
existing claim boundary. Sample time and current tenant-wide resolver authority
after waiting. A new completion additionally requires active ownership by that
actor at the exact claim/revision. Append only the existing terminal fact using
the accepted authority. No case/run/proof or ownership-history update occurs.

For the original command identity, current resolver authority plus the original
actor and identical full intent permit historical acknowledgement even though
the active-state check is now false. A terminal opening row still takes the
same lock. First append returns 201; exact replay returns 200 with only command,
work and claim IDs, expected revision and original completion/recording instants.
Do not mint a replacement command after uncertainty. No automatic retry policy.

Hidden/absent work, another actor, lost authority and a new command on terminal
work share 404. Changed same-actor same-command intent or stale owned coordinates
return 409; nonpositive revision is 400. The default-off completion flag returns
503, including recovery. Disabling browser sessions retains the shared 503.

## Alternatives considered

Active-owner-only replay makes successful uncertain completion unrecoverable.
An unauthenticated receipt lookup leaks work history. Recovering another command
or actor conflates intent. Reusing case proof or release fabricates different
business meaning. A second audit table or migration duplicates ADR 0065.

## Consequences

Completion and claim/release/handover/notes serialize on the existing work row.
Exact acknowledgement never grants private access, changes authority evidence
or repeats an effect. The immutable receipt contains no free text or provider
identity. M05-PR12 supplies confirmation and uncertain recovery in the UI/guide.

## Compatibility and migration

No new schema. Deploy V20261023195734 and every ADR 0065 active-work guard before
this producer. Keep `ERGON_HUMAN_FOLLOW_UP_COMPLETION_ENABLED=false` until the
backend and compatible UI are deployed. Disable new commands for rollback;
retain terminal facts/schema/guards and recover forward after first use.
Existing handover/notes/claim recovery remains unavailable after completion.

## Security and operations

Tenant/work/command scope plus original actor prevents cross-actor receipt
disclosure. Current authority is required for first append and historical
recovery, independently of source attribution. Privileged SQL remains outside
the HTTP authentication boundary. No remote I/O occurs while holding locks.
Observe normal BFF failures without logging tokens, private context or evidence.

## Validation

Real PostgreSQL/BFF tests cover session/CSRF, sanitized replay, private-access
retirement, changed intent, stale state, tenant/actor isolation, expiry after
lock waiting, rollback and competing ownership commands. The existing terminal
foundation tests retain schema/upgrade, audit-integrity and unchanged
case/run/proof evidence. Run repository policy and full Maven verification.
