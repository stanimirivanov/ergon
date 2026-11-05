# Shared dependency audit

The backend workflow audits a backend commit and a UI commit together. Backend
PRs and main pushes use their GitHub workflow revision and UI main. The weekly
scan uses the then-current pair. Manual dispatch accepts full commit SHAs.
The workflow records the exact checked-out commits, the UI lockfile SHA-256,
and tool versions in `summary.json`.

## Run locally

Use Java 25, Python 3.12, PyYAML 6.0.3 and OSV Scanner 2.7.0. The workflow
installs the Linux x86-64 scanner and Python wheel with verified hashes.
From a checkout containing this harness, pass two clean Git checkouts and an
output directory that does not yet exist:

```sh
python3 -m unittest discover -s tools/dependency-audit -p 'test_*.py'
python3 tools/dependency-audit/check.py \
  --backend /path/to/ergon --backend-revision "$backend_sha" \
  --ui /path/to/ergon-ui --ui-revision "$ui_sha" \
  --output target/dependency-audit
```

Keep the output directory ignored or outside the checked repositories. Network
access is needed for Maven resolution and OSV Scanner's advisory and license
lookups. No UI package installation or execution is needed for the audit.

## Initial rollout

The checked-in policy reports all known advisory matches and license findings.
These findings do not fail CI during the initial baseline review. Scanner or
inventory failures still fail the job. Review the evidence and any proposed
exceptions before changing the policy to enforcement.

## Coverage and evidence

Maven's pinned dependency plugin resolves every reactor project's dependency
tree across compile, runtime, provided, system and test scopes. The plugin
resolver also reports build plugins and their dependencies. Reactor modules
are excluded by exact coordinate. The pnpm lockfile supplies all committed
registry coordinates, including dev and optional/platform entries and the
package-manager lock document. A new unsupported resolution fails the audit.
The gate checks the scanner's full package list against that inventory.

OSV Scanner queries OSV for advisories and deps.dev for license metadata.
The license allowlist is deliberately conservative: each reported value must
be allowed or reviewed. Missing or nonstandard license metadata is a finding.
For the high threshold, a package advisory blocks at CVSS 7.0 or above.
A missing or unparseable score also blocks. Severity and license findings are
recorded separately.

`inventory.json` records package sources, `scanner.json` contains scanner
evidence, `findings.json` records policy decisions and `summary.json` reports
coverage and status. Exit 2 means verification was unavailable or incomplete.
Exit 1 means an enforcing policy found unexcepted violations. In report mode
the job can succeed with a `violations` summary, so read the summary before
using the result as release evidence.

For an exception, record exact `ecosystem`, `name`, `version`, `kind`
(`advisory` or `license`), `value` (advisory ID or reported license),
`expires` (UTC date, exclusive), `reason`, and a `review` HTTPS link. Add it
to `exceptions.json` through review. Expiry and package upgrades invalidate
the exception automatically.

This gate covers declared dependencies. It does not establish container OS,
runner/toolchain, vendored code or deployment inventory coverage. The backend
workflow cannot automatically post a required check on UI PRs. Use manual
dispatch to check a UI commit before release.
