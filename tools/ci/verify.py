"""CI stages with complete Maven/per-test diagnostics and fail-closed report coverage."""

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
MODULES = ("domain-kernel", "control-plane", "tools/repository-policy")
COMMANDS = {
    "policy": [("-pl", ":repository-policy", "test", "exec:java@repository-policy")],
    "formatting": [("ktlint:check",)],
    "static": [("detekt:check",)],
    "compile": [
        ("-pl", "domain-kernel,control-plane", "-am", "test-compile"),
        ("-N", "flatten:flatten", "install:install"),
        ("-pl", "domain-kernel", "flatten:flatten", "jar:jar", "install:install"),
    ],
    "unit": [("-pl", "domain-kernel,control-plane", "-Pci-unit", "surefire:test")],
    "postgres": [("-pl", "control-plane", "-Pci-postgres", "surefire:test")],
    "package": [("-pl", "control-plane", "jar:jar", "spring-boot:repackage")],
}
TEST_ANNOTATION = re.compile(r"@(Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\b")


def inventory(root):
    """Reject unknown reactor modules, undiscoverable tests and misplaced container suites."""
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    modules = {n.text for n in ET.parse(root / "pom.xml").findall("m:modules/m:module", ns)}
    if modules != set(MODULES):
        raise ValueError("Reactor changed: update CI compilation and test inventory together")
    found = {}
    for module in MODULES:
        for source in sorted((root / module / "src/test/kotlin").rglob("*.kt")):
            text = source.read_text(encoding="utf-8")
            classes = re.findall(r"^(?:(?:internal|open) )*class (\w+)", text, re.MULTILINE)
            names = [name for name in classes if name.startswith("Test") or name.endswith(("Test", "Tests", "TestCase"))]
            if not names:
                if TEST_ANNOTATION.search(text):
                    raise ValueError(f"{source}: tests must match Surefire's default class-name patterns")
                continue
            package = re.search(r"^package ([\w.]+)", text, re.MULTILINE)
            if not package or source.stem not in names:
                raise ValueError(f"{source}: declare the matching top-level test class")
            for name in names:
                postgres = name.endswith("IntegrationTest")
                if postgres != ("import org.testcontainers." in text) or (postgres and module != "control-plane"):
                    raise ValueError(f"{source}: container suites must be control-plane *IntegrationTest classes")
                qualified = package[1] + "." + name
                if qualified in {name for _, name in found}:
                    raise ValueError(f"{source}: duplicate test class {qualified}")
                found[(module, qualified)] = postgres
    return found


def report_directories(root, stage):
    if stage == "policy":
        return [root / "tools/repository-policy/target/surefire-reports"]
    modules = ("control-plane",) if stage == "postgres" else MODULES[:2]
    return [root / module / "target/surefire-reports" / stage for module in modules]


def expected_tests(root, stage):
    return {
        name for (module, name), postgres in inventory(root).items()
        if (stage == "policy" and module == "tools/repository-policy")
        or (stage == "unit" and module != "tools/repository-policy" and not postgres)
        or (stage == "postgres" and postgres)
    }


def inspect_reports(root, stage):
    """Require every selected suite, with no wrong-lane, empty, failed or skipped result."""
    expected = expected_tests(root, stage)
    seen, problems = set(), []
    totals = dict(tests=0, failures=0, errors=0, skipped=0)
    for directory in report_directories(root, stage):
        for report in sorted(directory.glob("TEST-*.xml")):
            suite = ET.parse(report).getroot()
            seen.add(suite.attrib["name"].split("$")[0])
            counts = {key: int(suite.attrib.get(key, 0)) for key in totals}
            for key, count in counts.items():
                totals[key] += count
            if counts["tests"] == 0 or any(counts[key] for key in ("failures", "errors", "skipped")):
                problems.append(f"{suite.attrib['name']}: {counts}")
    if seen != expected:
        problems.append(f"Missing: {sorted(expected - seen)}; unexpected: {sorted(seen - expected)}")
    if not expected:
        problems.append("Empty test lane")
    return totals, problems


def gate(needs):
    """Only the complete expected set of successful prerequisite jobs satisfies branch protection."""
    return set(needs) == {"checks", "reactor"} and all(
        job.get("result") == "success" for job in needs.values()
    )


def emit(message):
    print(message, flush=True)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a", encoding="utf-8") as output:
            output.write(message + "\n\n")


def clear_reports(root, stage):
    # Only generated lane reports are removed: stale XML must not mask omitted suites.
    for directory in report_directories(root, stage):
        if not directory.resolve().is_relative_to(root.resolve()):
            raise ValueError(f"Report directory escapes checkout: {directory}")
        if directory.exists():
            shutil.rmtree(directory)


def run_stage(root, stage):
    """Preserve process failures and full logs, while bounding Actions console output."""
    output = root / "target/ci"
    output.mkdir(parents=True, exist_ok=True)
    log_path = output / f"{stage}.log"
    code, problems = 0, []
    with log_path.open("w", encoding="utf-8") as log:
        try:
            inventory(root)
            if stage in ("policy", "unit", "postgres"):
                clear_reports(root, stage)
            if stage == "policy":
                code = subprocess.run(
                    [sys.executable, "-m", "unittest", "discover", "-s", "tools/ci", "-v"],
                    cwd=root, stdout=log, stderr=subprocess.STDOUT, check=False,
                ).returncode
            wrapper = root / ("mvnw.cmd" if os.name == "nt" else "mvnw")
            for arguments in COMMANDS[stage]:
                if code:
                    break
                command = [str(wrapper), "-B", "-ntp", "-Dmaven.test.redirectTestOutputToFile=true", *arguments]
                log.write("$ " + " ".join(command) + "\n")
                log.flush()
                code = subprocess.run(command, cwd=root, stdout=log, stderr=subprocess.STDOUT, check=False).returncode
            if stage in ("policy", "unit", "postgres"):
                totals, problems = inspect_reports(root, stage)
                emit(f"{stage}: {totals}")
        except (OSError, ValueError, ET.ParseError, KeyError) as error:
            log.write(f"CI harness error: {error}\n")
            problems.append(str(error))
    failed = code != 0 or bool(problems)
    emit(f"### {stage}: {'FAIL' if failed else 'PASS'} — full log: target/ci/{stage}.log")
    for problem in problems[:5]:
        print("  " + problem[:800])
    if code:
        diagnostics = [line for line in log_path.read_text(encoding="utf-8", errors="replace").splitlines()
                       if "[ERROR]" in line or re.search(r"\.kt:\d+:\d+:", line)]
        for line in diagnostics[:12]:
            print("  " + line[:500])
    return 1 if failed else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("stage", choices=(*COMMANDS, "gate"))
    args = parser.parse_args()
    if args.stage == "gate":
        passed = gate(json.loads(os.environ.get("CI_NEEDS", "{}")))
        emit(f"### Required CI gate: {'PASS' if passed else 'FAIL'}")
        return 0 if passed else 1
    return run_stage(ROOT, args.stage)


if __name__ == "__main__":
    sys.exit(main())
