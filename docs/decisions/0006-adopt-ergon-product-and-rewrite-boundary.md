# ADR 0006: Adopt the Ergon product and rewrite boundary

- Status: Proposed
- Date: 2026-09-11
- Deciders: Maintainers

## Context

RAG Help Center is an article-to-answer infrastructure reference. Extending its
article aggregate and five-service topology into an action-capable support
product would preserve the wrong domain boundary and duplicate established
open-source help desks, RAG workspaces, and agent workflow builders.

## Decision

Redefine the project as **Ergon**, an evidence-driven resolution platform. Its
primary lifecycle is case graph, evidence bundle, resolution contract,
policy-authorized run, outcome proof, and reviewed improvement proposal.

Implement the first vertical slice as a Kotlin modular monolith with separate
runtime and compiler workers, a React application, and PostgreSQL as the system
of record. New work need not preserve predecessor article or Q&A APIs. Reuse old
implementation patterns only when a slice demonstrates their value.

Models interpret and propose. Deterministic policy, capability, execution, and
verification components retain authority.

## Alternatives

- Incrementally add tickets, tools, and channels to the existing services:
  rejected because article retrieval would remain the architectural center.
- Build a complete AI help desk: rejected because strong open-source products
  already serve that category and channels can be adapters.
- Build a generic agent or DAG platform: rejected because it removes the
  resolution-specific evidence, authority, and outcome semantics.

## Consequences

- Most existing modules and APIs will be replaced rather than evolved.
- Existing ADRs are preserved but superseded; they do not govern Ergon unless a
  new decision adopts their reasoning.
- Initial deployment has fewer process and datastore boundaries than the
  predecessor.
- Outcome verification and policy denial are product behavior, not optional
  guardrails.
- The first useful release is intentionally narrow: one verified
  access-restoration scenario with fake connectors.

## Verification

Each rewrite step must leave the repository buildable and demonstrate one
vertical capability. The first slice must show that an action alone cannot close
a case, a model cannot authorize a capability, duplicate delivery cannot repeat
an external effect, and accepted proof is required for `VERIFIED_RESOLVED`.
