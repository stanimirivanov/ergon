# Security policy

## TL;DR

- Do not disclose suspected vulnerabilities in public issues, discussions, or
  pull requests.
- Prefer GitHub private vulnerability reporting.
- If no private channel exists, request one without sensitive details.
- Ergon is pre-release; only the current default branch receives security fixes.
- Test only systems and data you own or are explicitly authorized to assess.

## Supported versions

Ergon has no supported production release. Security fixes target the current
default branch. Before the first release, this section must become an explicit
supported-version and end-of-support table.

The predecessor RAG services retained during the rewrite are development
artifacts, not separately supported products.

## Reporting a vulnerability

Use the repository Security page's **Report a vulnerability** action when it is
available. If private vulnerability reporting is unavailable, do not publish
the report. Open only a minimal request for a private security contact, without
naming the component, exploit, affected data, or reproduction.

Include where applicable:

- affected revision, component, and configuration;
- impact and required conditions;
- minimal reproduction or proof of concept;
- redacted logs without tokens, credentials, personal, or customer data;
- known mitigations; and
- prior or planned disclosure.

Do not retain, alter, or disclose accessed data beyond what is necessary to
demonstrate an authorized issue safely.

## Triage and coordinated disclosure

Maintainers should acknowledge a private report, validate scope and severity,
and agree on communication before disclosure. Response targets remain
best-effort until a staffed security process exists.

Reporter and maintainers should coordinate disclosure after a fix or effective
mitigation is available. Maintainers may publish a GitHub security advisory and
credit the reporter unless anonymity is requested. Good-faith reports under
this policy should be handled respectfully.

## Security expectations for changes

Changes affecting authentication, authorization, tenant isolation, sensitive
data, model or retrieved input, capability execution, or external credentials
must document their threat and failure model and receive focused review.

Follow [the working agreement](AGENTS.md), [contributor workflow](CONTRIBUTING.md),
and [engineering standards](docs/development/engineering-standards.md). Never
commit credentials or treat tenant identifiers, correlation identifiers,
retrieved content, model output, or client-supplied tool descriptions as proof
of authority.
