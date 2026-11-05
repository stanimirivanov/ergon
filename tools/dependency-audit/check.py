"""Audit an exact pair of Maven and pnpm dependency inventories."""
import argparse
import datetime as dt
import hashlib
import json
import posixpath
import re
import subprocess
import tempfile
import xml.etree.ElementTree as et
from decimal import Decimal, InvalidOperation
from pathlib import Path

HERE = Path(__file__).resolve().parent
MAVEN_PLUGIN = "org.apache.maven.plugins:maven-dependency-plugin:3.7.0"
SCANNER_VERSION = "2.7.0"
MAVEN_NS = {"m": "http://maven.apache.org/POM/4.0.0"}
NPM_COORDINATE = re.compile(
    r"((?:@[a-zA-Z0-9._~-]+/)?[a-zA-Z0-9._~-]+)@"
    r"(\d+\.\d+\.\d+(?:-[a-zA-Z0-9.-]+)?(?:\+[a-zA-Z0-9.-]+)?)"
)


class AuditError(ValueError):
    """An input or external check could not establish complete coverage."""


def run(args, cwd=None, accepted=(0,)):
    result = subprocess.run(args, cwd=cwd, text=True, capture_output=True, timeout=300)
    if result.returncode not in accepted:
        print(result.stderr or result.stdout)
        raise AuditError(f"{Path(args[0]).name} exited {result.returncode}")
    return result


def read_json(path):
    return json.loads(path.read_text(encoding="utf-8"))


def write_json(path, value):
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def snapshot(path, expected):
    if not re.fullmatch(r"[0-9a-f]{40}", expected):
        raise AuditError("Expected a full Git commit ID")
    actual = run(["git", "rev-parse", "HEAD"], path).stdout.strip()
    if actual != expected or run(["git", "status", "--porcelain"], path).stdout:
        raise AuditError(f"{path.name} must be clean at {expected}")


def coordinate(item):
    if not isinstance(item, dict):
        raise AuditError("Malformed package coordinate")
    key = tuple(item.get(field) for field in ("ecosystem", "name", "version"))
    if any(not isinstance(value, str) or not value.strip() for value in key):
        raise AuditError("Incomplete package coordinate")
    if key[0] not in {"Maven", "npm"}:
        raise AuditError(f"Unexpected ecosystem: {key[0]}")
    return key


def package(key):
    return dict(zip(("ecosystem", "name", "version"), key, strict=True))


def verify_pnpm_edges(doc):
    importers, snapshots = doc["importers"], doc["snapshots"]

    def verify(name, version, importer=None):
        if importer is not None and version.startswith("link:"):
            if posixpath.normpath(posixpath.join(importer, version[5:])) in importers:
                return
        elif f"{name}@{version}" in snapshots:
            return
        raise AuditError(f"Missing pnpm resolution for {name}@{version}")

    groups = {"dependencies", "devDependencies", "optionalDependencies",
              "packageManagerDependencies", "configDependencies"}
    for importer, entry in importers.items():
        if set(entry) - groups:
            raise AuditError(f"Unsupported pnpm importer fields: {importer}")
        for group in entry.values():
            for name, reference in group.items():
                verify(name, reference["version"], importer)
    for entry in snapshots.values():
        for group in ("dependencies", "optionalDependencies"):
            for name, version in (entry or {}).get(group, {}).items():
                verify(name, version)


def pnpm_inventory(lockfile):
    import yaml  # CI pins the audit-only YAML parser.

    class UniqueLoader(yaml.SafeLoader):
        def construct_mapping(self, node, deep=False):
            result = super().construct_mapping(node, deep)
            if len(result) != len(node.value):
                raise AuditError("Duplicate pnpm lockfile mapping key")
            return result

    inventory = {}
    documents = list(yaml.load_all(lockfile.read_text(encoding="utf-8"), Loader=UniqueLoader))
    if not documents:
        raise AuditError("Empty pnpm lockfile")
    for index, doc in enumerate(documents, 1):
        if not isinstance(doc, dict) or doc.get("lockfileVersion") != "9.0":
            raise AuditError("Unsupported pnpm lockfile format")
        if not all(isinstance(doc.get(key), dict) and doc[key] for key in ("importers", "packages", "snapshots")):
            raise AuditError("Incomplete pnpm lockfile document")
        if {key.split("(", 1)[0] for key in doc["snapshots"]} != set(doc["packages"]):
            raise AuditError("pnpm snapshot and package tables disagree")
        verify_pnpm_edges(doc)
        for identity, metadata in doc["packages"].items():
            match = NPM_COORDINATE.fullmatch(identity)
            resolution = metadata.get("resolution", {})
            if not match or not resolution.get("integrity") or set(resolution) != {"integrity"}:
                raise AuditError(f"Unsupported pnpm package resolution: {identity}")
            inventory.setdefault(("npm", *match.groups()), set()).add(f"pnpm:document:{index}")
    return inventory


def maven_inventory(root, workspace):
    paths = [root]

    def modules(path):
        pom = et.parse(path / "pom.xml")
        for entry in pom.findall("m:modules/m:module", MAVEN_NS):
            module = (path / entry.text).resolve()
            if not module.is_relative_to(root) or module in paths:
                raise AuditError("Invalid or duplicate Maven reactor module")
            paths.append(module)
            modules(module)

    modules(root)
    if len(paths) < 2:
        raise AuditError("Missing Maven reactor modules")
    # Use a new filename on every invocation, so a stale tree cannot satisfy coverage.
    output = f"target/{workspace.name}-tree.json"
    plugins = f"target/{workspace.name}-plugins.txt"
    trees = []
    plugin_reports = []
    try:
        run(["./mvnw", "-B", "-ntp", f"{MAVEN_PLUGIN}:tree",
             "-DoutputType=json", f"-DoutputFile={output}"], root)
        resolution = run(["./mvnw", "-B", "-ntp", f"{MAVEN_PLUGIN}:resolve-plugins",
                          f"-DoutputFile={plugins}"], root)
        if "[WARNING]" in resolution.stdout or "[WARNING]" in resolution.stderr:
            raise AuditError("Maven plugin resolution returned warnings")
        for path in paths:
            plugin_reports.append((str(path.relative_to(root)), (path / plugins).read_text(encoding="utf-8")))
            trees.append((str(path.relative_to(root)), read_json(path / output)))
    finally:
        for path in paths:
            (path / output).unlink(missing_ok=True)
            (path / plugins).unlink(missing_ok=True)

    def key(node):
        return coordinate({
            "ecosystem": "Maven", "name": f"{node['groupId']}:{node['artifactId']}",
            "version": node["version"],
        })

    reactor = {key(tree) for _, tree in trees}
    inventory = {}

    def visit(node, module):
        current = key(node)
        scope = node["scope"]
        if scope not in {"compile", "provided", "runtime", "test", "system"}:
            raise AuditError(f"Unexpected Maven dependency scope: {scope}")
        if current not in reactor:
            inventory.setdefault(current, set()).add(f"maven:{module}:{scope}")
        for child in node.get("children", []):
            visit(child, module)

    for module, tree in trees:
        for child in tree.get("children", []):
            visit(child, module)
    if not inventory:
        raise AuditError("No Maven dependencies were resolved")
    for module, report in plugin_reports:
        for key in plugin_inventory(report):
            inventory.setdefault(key, set()).add(f"maven:{module}:plugin")
    return inventory


def plugin_inventory(report):
    lines = [line for line in report.splitlines() if line.strip()]
    if not lines or lines[0] != "The following plugins have been resolved:":
        raise AuditError("Missing Maven plugin inventory")
    result = set()
    plugins = 0
    children = 0
    for line in lines[1:]:
        indent = len(line) - len(line.lstrip())
        fields = line.strip().split(":")
        if indent == 3 and len(fields) == 5 and fields[-1] == "runtime":
            if plugins and not children:
                raise AuditError("Incomplete Maven plugin dependencies")
            plugins += 1
            children = 0
        elif indent == 6 and plugins and len(fields) in {4, 5}:
            children += 1
        else:
            raise AuditError("Malformed Maven plugin dependency report")
        result.add(coordinate({
            "ecosystem": "Maven", "name": ":".join(fields[:2]),
            "version": fields[-2] if indent == 3 else fields[-1],
        }))
    if not plugins or not children:
        raise AuditError("Incomplete Maven plugin inventory")
    return result


def scan_packages(report):
    rows = {}
    for result in report["results"] if isinstance(report.get("results"), list) else []:
        for row in result["packages"]:
            key = coordinate(row["package"])
            if key in rows:
                raise AuditError(f"Duplicate scanner result for {key}")
            rows[key] = row
    if not rows:
        raise AuditError("Missing scanner package report")
    return rows


def verify_coverage(expected, actual):
    missing, extra = set(expected) - set(actual), set(actual) - set(expected)
    if not expected or missing or extra:
        raise AuditError(f"Scanner inventory mismatch: missing={sorted(missing)}, extra={sorted(extra)}")


def validate_policy(policy):
    licenses = policy["allowedLicenses"]
    if (policy["mode"] not in {"enforce", "report"} or policy["advisories"] not in {"all", "high"}
            or not isinstance(licenses, list) or not licenses
            or any(not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9.+-]+", value)
                   or value in {"UNKNOWN", "non-standard"} for value in licenses)):
        raise AuditError("Invalid audit policy")


def advisory_blocks(row, identifier, threshold):
    if threshold == "all":
        return True
    scores = []
    for group in row.get("groups", []):
        if identifier in group["ids"]:
            try:
                score = Decimal(group.get("max_severity", "NaN"))
            except InvalidOperation:
                return True
            if not score.is_finite() or not 0 <= score <= 10:
                return True
            scores.append(score)
    return not scores or max(scores) >= 7


def findings(rows, policy, exceptions, today):
    allowed = set(policy["allowedLicenses"])
    waivers = {}
    seen = set()
    for item in exceptions:
        key = (coordinate(item), item["kind"], item["value"])
        if (key in seen or item["kind"] not in {"advisory", "license"}
                or not item["reason"].strip() or not item["review"].startswith("https://")):
            raise AuditError(f"Invalid or duplicate exception for {key}")
        seen.add(key)
        if dt.date.fromisoformat(item["expires"]) > today:
            waivers[key] = item

    result = []
    for key, row in sorted(rows.items()):
        issues = []
        licenses = row.get("licenses", [])
        if not isinstance(licenses, list) or any(not isinstance(value, str) or not value for value in licenses):
            raise AuditError(f"Malformed license report for {key}")
        issues.extend(("license", value, True) for value in sorted(set(licenses or ["UNKNOWN"]) - allowed))
        for advisory in row.get("vulnerabilities", []):
            value = advisory["id"]
            if not isinstance(value, str) or not value.strip():
                raise AuditError(f"Malformed advisory for {key}")
            blocking = advisory_blocks(row, value, policy["advisories"])
            issues.append(("advisory", value, blocking))
        for kind, value, blocking in issues:
            waiver = waivers.get((key, kind, value))
            result.append({**package(key), "kind": kind, "value": value,
                           "status": "excepted" if waiver else "blocked" if blocking else "reported",
                           **({"exception": waiver} if waiver else {})})
    return result


def exit_for_findings(policy, decisions):
    return int(policy["mode"] == "enforce" and any(row["status"] == "blocked" for row in decisions))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for side in ("backend", "ui"):
        parser.add_argument(f"--{side}", type=Path, required=True)
        parser.add_argument(f"--{side}-revision", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    summary = {"status": "unavailable", "backend": args.backend_revision, "ui": args.ui_revision}
    try:
        backend, ui = args.backend.resolve(), args.ui.resolve()
        snapshot(backend, args.backend_revision)
        snapshot(ui, args.ui_revision)
        lockfile = ui / "pnpm-lock.yaml"
        summary["pnpmLockSha256"] = hashlib.sha256(lockfile.read_bytes()).hexdigest()
        summary["mavenDependencyPlugin"] = MAVEN_PLUGIN
        scanner = run(["osv-scanner", "--version"]).stdout
        if not re.search(rf"(?m)^osv-scanner version: {re.escape(SCANNER_VERSION)}\s*$", scanner):
            raise AuditError("Unexpected OSV Scanner version")
        summary["scanner"] = scanner.strip()
        policy = read_json(HERE / "policy.json")
        validate_policy(policy)
        summary["policy"] = policy
        with tempfile.TemporaryDirectory(prefix="ergon-audit-") as directory:
            workspace = Path(directory)
            inventory = maven_inventory(backend, workspace) | pnpm_inventory(lockfile)
            write_json(output / "inventory.json", [
                {**package(key), "sources": sorted(sources)} for key, sources in sorted(inventory.items())
            ])
            scan_input = workspace / "osv-scanner.json"
            write_json(scan_input, {"results": [{"packages": [
                {"package": package(key)} for key in sorted(inventory)
            ]}]})
            # Override ambient scanner ignores. Review exceptions in our own report.
            config = workspace / "osv-scanner.toml"
            config.touch()
            response = run(["osv-scanner", "scan", "source", "--all-packages", "--format=json",
                            "--licenses=" + ",".join(policy["allowedLicenses"]),
                            "--config", str(config), "-L", f"osv-scanner:{scan_input}"],
                           workspace, accepted=(0, 1))
            (output / "scanner.log").write_text(response.stderr, encoding="utf-8")
            report = json.loads(response.stdout)
            write_json(output / "scanner.json", report)
            rows = scan_packages(report)
            verify_coverage(inventory, rows)
        decisions = findings(rows, policy, read_json(HERE / "exceptions.json"), dt.datetime.now(dt.UTC).date())
        write_json(output / "findings.json", decisions)
        summary["packages"] = {eco: sum(key[0] == eco for key in rows) for eco in ("Maven", "npm")}
        summary["blocked"] = sum(row["status"] == "blocked" for row in decisions)
        summary["excepted"] = sum(row["status"] == "excepted" for row in decisions)
        summary["status"] = "violations" if summary["blocked"] else "passed"
        print(f"Dependency audit: {summary['status']}, {summary['packages']}, {summary['blocked']} blocked")
        return exit_for_findings(policy, decisions)
    except (OSError, ValueError, KeyError, TypeError, subprocess.SubprocessError) as error:
        summary["error"] = str(error)
        print(f"Dependency audit unavailable: {error}")
        return 2
    finally:
        write_json(output / "summary.json", summary)


if __name__ == "__main__":
    raise SystemExit(main())
