# Architecture Decision Records

ADRs record decisions that affect compatibility, durability, security, data
meaning, deployment topology, or more than one application or module. Small,
local implementation choices belong in code and tests.

## Process

1. Copy `0000-template.md` to the next number with a short kebab-case name.
2. Open it as `Proposed` in the same pull request that introduces the decision.
3. Record alternatives and consequences, not a retrospective justification for
   a predetermined implementation.
4. Mark it `Accepted` when the pull request is accepted.
5. Never rewrite an accepted decision to hide history. Add a new ADR and mark
   the earlier one `Superseded by ADR NNNN`.

An ADR should be brief enough to review with the change. Link concrete schemas,
contracts, benchmarks, or threat models instead of copying them into the ADR.

## Rewrite boundary

ADRs 0001–0005 describe RAG Help Center and are marked superseded by the Ergon
rewrite. They remain because their context and trade-offs are useful history,
but they are not authoritative for new work. Ergon decisions continue at 0006;
reusing an old decision requires an explicit new ADR when it meets the criteria
above.
