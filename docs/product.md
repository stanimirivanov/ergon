# Ergon Product

> **TL;DR:** Ergon is not another help desk or RAG chatbot. It is an open
> resolution compiler: it represents a problem as evidence, runs a reviewed and
> policy-gated resolution contract, verifies the resulting state, and proposes
> improvements from what actually happened.

## Thesis

Support systems usually treat an answer, handoff, or closed ticket as success.
Ergon treats only an accepted **outcome proof** as a verified resolution.

The product is channel-independent and domain-general. Customer support,
internal IT, developer support, onboarding, and operations are experiences over
the same case and resolution runtime. Existing help desks can remain systems of
engagement while Ergon is the system of resolution.

## Core loop

```text
request
  -> case graph
  -> evidence bundle
  -> resolution contract
  -> authorized run
  -> outcome proof
  -> improvement proposal
```

## Primitives

### Case graph

The durable representation of a problem: desired outcome, affected subjects,
observations, facts, unknowns, hypotheses, applicable policies, attempted
actions, participants, and outcomes. Messages, forms, alerts, API events, and
human notes contribute observations; none of them is the case itself.

### Evidence graph

Knowledge compiled into typed, attributable objects:

- claims with source, scope, confidence, and validity window;
- procedures with prerequisites and expected effects;
- policies that constrain decisions or authority;
- tool capabilities with typed inputs, effects, and risks;
- precedents with the conditions and outcomes of earlier cases;
- contradictions that require scoping, review, or newer evidence.

Raw source spans remain available for citation. Retrieval returns a coherent
evidence bundle instead of an unqualified bag of similar chunks.

### Resolution contract

A versioned, Git-friendly program defining when it applies, required evidence,
allowed capabilities, deterministic steps, bounded AI decisions, approvals,
timeouts, retries, compensation, escalation, and success proof. The visual
editor is a projection of the textual contract, not an opaque source of truth.

### Resolution run

An append-only execution pinned to immutable contract, policy, model, prompt,
evidence, and tool-schema revisions. Each step records its input, proposal,
authorization, action receipt, observation, latency, and cost. Runs can pause,
wait for events, request approval, transfer to a specialist, and resume through
another channel.

### Outcome proof

Evidence accepted by the contract's success condition: a successful login
challenge, corrected order state, recovered metric over a monitoring window, or
explicit user confirmation when no machine-observable proof exists.

```text
OPEN -> DIAGNOSING -> ACTION_PENDING -> VERIFYING
     -> VERIFIED_RESOLVED | UNVERIFIED | ESCALATED | ABANDONED
```

### Improvement proposal

A pull-request-like suggestion backed by case evidence. It may add a missing
diagnostic, refine applicability, resolve a contradiction, strengthen a policy,
change a contract branch, patch documentation, or add a regression scenario.
Production behavior never changes through silent learning.

## Experiences

### Adaptive resolution canvas

The requester sees the next useful interaction: a question, evidence request,
diagnostic result, consent prompt, action approval, progress timeline, or
verification card. Chat is an input method, not the organizing metaphor.

### Resolver console

Humans share the same case graph as the runtime. They can inspect missing or
disputed facts, understand why a plan was selected, approve or limit actions,
correct observations, choose a branch, and hand off without reconstructing the
case from a transcript summary.

### Resolution studio

Domain experts create and review contracts from resolution-specific blocks:
observe, bind, diagnose, decide, act, approve, wait, verify, compensate, and
escalate.

### Simulation lab

Contract revisions run against sanitized historical cases, generated edge
cases, tool failures, stale evidence, policy combinations, adversarial inputs,
and model configurations. Promotion is gated on outcome success, policy
compliance, grounding, tool correctness, escalation quality, cost, and latency.

## Product rules

1. Models may propose; only the runtime may authorize and execute.
2. Retrieved content and tool descriptions are untrusted data.
3. Retrieval never grants authority.
4. Every external write has a scoped capability, risk class, idempotency key,
   and receipt.
5. A case closes as verified only when its pinned contract accepts the proof.
6. Human intervention is first-class evidence, not a failure hidden from
   metrics.
7. Tenant isolation applies to storage, retrieval, events, logs, and capability
   issuance—not only prompts or request headers.
8. Public explanations show sources and material decision factors, never hidden
   chain-of-thought.

## Positioning

Chatwoot owns the omnichannel help-desk problem. AnythingLLM and DocsGPT cover
private AI workspaces and document assistance. TGO combines channels, RAG,
agents, tools, workflows, and handoff. Rasa demonstrates explicit conversational
business control. Ergon takes their strongest lessons without competing on
feature count.

The proposed open-source gap is the unified lifecycle:

```text
evidence -> contract -> authority -> execution -> proof -> reviewed learning
```

Individual ingredients already exist. Ergon's novelty is making that lifecycle
the domain model rather than a collection of optional agent features.

## Success measures

The north-star metric is **verified resolution rate**: eligible cases with an
accepted outcome proof.

Supporting measures are time to proof, unverified closure rate, evidence
freshness, contradiction age, action denial and compensation rates, escalation
correctness, intervention rate by reason, contract coverage, regression escape
rate, and cost and latency per contract revision. Containment and deflection are
diagnostic metrics only; optimizing them directly can produce confidently
unresolved users.

## Non-goals for the first product cycle

- A complete inbox, CRM, or traditional help-center CMS.
- A generic no-code automation suite.
- Unrestricted autonomous tool use.
- Native mobile SDKs, a model marketplace, or fine-tuning platform.
- A microservice or datastore for every domain concept.
- Compatibility with the predecessor's article and Q&A APIs.
