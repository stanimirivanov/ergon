# 0023. Require post-action evidence for outcome proof

- Status: proposed
- Date: 2026-09-15
- Owners: Ergon maintainers

## Context

A successful connector receipt reports that an action completed; it does not
prove the requested outcome. A matching fact may already exist in the case
snapshot that caused the action, and accepting that old value would turn a
precondition into supposed proof. Evidence can also change again after an
initial match.

## Decision

Assess the exact outcome condition from the run's pinned contract against the
latest eligible semantically bound case fact. A fact is eligible only when its
source observation and binding both follow the run's case-stream boundary and
the observation occurrence time is at or after connector completion.

Return `ACCEPTED` for an exact value match. Return `PENDING` with
`ELIGIBLE_EVIDENCE_MISSING` when no post-action fact qualifies, or
`VALUE_MISMATCH` with the latest eligible fact when its value differs. A later
eligible fact supersedes an earlier one in stream order.

Keep assessment read-only. It identifies the exact case stream version and
source-bound fact but does not freeze that evidence, append a run event, or
change case status. Those durability decisions belong to a later command.

## Alternatives

Trusting the connector receipt was rejected because action completion and user
outcome are different claims. Reusing any matching historical fact was rejected
because pre-action state cannot demonstrate causality. Immediately closing the
case from a query was rejected because reads must not hide a durable workflow
transition and concurrent evidence must be handled explicitly.

## Consequences

Verification requires a new attributable observation plus semantic binding.
Assessment can change when later evidence arrives, which is visible rather than
silently frozen. Only runs in `VERIFYING` can be assessed. The first evaluator
supports the registered `account.access.state` fact; generalized fact projection,
durable acceptance, run completion, and case closure remain later work.

## Verification

Domain tests cover post-action matching, rejection of pre-run and pre-completion
evidence, and latest-value mismatch. The end-to-end test covers state gating,
tenant isolation, missing evidence, independent observation and binding,
accepted proof, later contradiction, and unchanged run and case state.
