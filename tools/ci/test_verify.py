"""Regression evidence for test-lane coverage, preserved failures and aggregate gating."""

import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import verify


class VerificationTest(unittest.TestCase):
    def setUp(self):
        self.enterContext(patch.object(verify, "emit"))
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        (self.root / "pom.xml").write_text(
            '<project xmlns="http://maven.apache.org/POM/4.0.0"><modules>'
            + "".join(f"<module>{module}</module>" for module in verify.MODULES)
            + "</modules></project>", encoding="utf-8",
        )

    def source(self, name="ExampleTest", module="control-plane", container=False):
        source = self.root / module / "src/test/kotlin" / f"{name}.kt"
        source.parent.mkdir(parents=True, exist_ok=True)
        source.write_text(
            "package example\n"
            + ("import org.testcontainers.junit.jupiter.Testcontainers\n" if container else "")
            + f"class {name} {{ @Test fun example() = Unit }}", encoding="utf-8",
        )

    def report(self, stage, name="ExampleTest", **counts):
        directory = verify.report_directories(self.root, stage)[-1]
        directory.mkdir(parents=True, exist_ok=True)
        values = dict(tests=1, failures=0, errors=0, skipped=0) | counts
        attributes = " ".join(f'{key}="{value}"' for key, value in values.items())
        (directory / f"TEST-example.{name}.xml").write_text(
            f'<testsuite name="example.{name}" {attributes}/>', encoding="utf-8",
        )

    def test_partition_and_pom_filters_match(self):
        self.source()
        self.source("ExampleIntegrationTest", container=True)
        unit = verify.expected_tests(self.root, "unit")
        postgres = verify.expected_tests(self.root, "postgres")
        self.assertFalse(unit & postgres)
        self.assertEqual(unit | postgres, {"example.ExampleTest", "example.ExampleIntegrationTest"})
        ns = {"m": "http://maven.apache.org/POM/4.0.0"}
        pom = verify.ET.parse(verify.ROOT / "pom.xml")
        for profile, kind in (("ci-unit", "exclude"), ("ci-postgres", "include")):
            path = f".//m:profile[m:id='{profile}']//m:{kind}"
            self.assertEqual([node.text for node in pom.findall(path, ns)], ["**/*IntegrationTest.class"])

    def test_unknown_or_misclassified_suites_fail(self):
        for name, container in (("ExampleTest", True), ("ExampleIntegrationTest", False), ("Example", False)):
            with self.subTest(name=name):
                self.source(name, container=container)
                with self.assertRaises(ValueError):
                    verify.inventory(self.root)
                (self.root / f"control-plane/src/test/kotlin/{name}.kt").unlink()
        pom = self.root / "pom.xml"
        pom.write_text(pom.read_text().replace("</modules>", "<module>new-module</module></modules>"))
        with self.assertRaises(ValueError):
            verify.inventory(self.root)

    def test_complete_successful_reports(self):
        self.source()
        self.report("unit")
        self.assertEqual(verify.inspect_reports(self.root, "unit"), (
            dict(tests=1, failures=0, errors=0, skipped=0), [],
        ))

    def test_missing_empty_failing_skipped_and_wrong_lane_reports_fail(self):
        self.source()
        self.assertTrue(verify.inspect_reports(self.root, "unit")[1])
        for counts in (dict(tests=0), dict(failures=1), dict(errors=1), dict(skipped=1)):
            self.report("unit", **counts)
            self.assertTrue(verify.inspect_reports(self.root, "unit")[1])
        self.report("unit", name="OtherIntegrationTest")
        self.assertTrue(verify.inspect_reports(self.root, "unit")[1])
        self.assertTrue(verify.inspect_reports(self.root, "postgres")[1])

    def test_stale_reports_removed_only_from_selected_lane(self):
        self.report("unit")
        self.report("postgres", name="ExampleIntegrationTest")
        verify.clear_reports(self.root, "unit")
        self.assertFalse(verify.report_directories(self.root, "unit")[-1].exists())
        self.assertTrue(verify.report_directories(self.root, "postgres")[-1].exists())

    def test_runner_preserves_process_failure_and_full_log(self):
        self.source()
        with patch.object(verify.subprocess, "run") as run:
            run.return_value.returncode = 7
            self.assertEqual(verify.run_stage(self.root, "compile"), 1)
            self.assertEqual(run.call_count, 1)
        self.assertIn("test-compile", (self.root / "target/ci/compile.log").read_text())

    def test_runner_rejects_success_without_reports(self):
        self.source()
        with patch.object(verify.subprocess, "run") as run:
            run.return_value.returncode = 0
            self.assertEqual(verify.run_stage(self.root, "unit"), 1)

    def test_gate_rejects_all_non_success_and_missing_dependencies(self):
        success = {"checks": {"result": "success"}, "reactor": {"result": "success"}}
        self.assertTrue(verify.gate(success))
        for job in success:
            for state in ("failure", "cancelled", "skipped", None):
                needs = json.loads(json.dumps(success))
                needs[job]["result"] = state
                self.assertFalse(verify.gate(needs))
        self.assertFalse(verify.gate({}))
        self.assertFalse(verify.gate({"checks": {"result": "success"}}))

    def test_workflow_keeps_parallel_checks_and_always_evaluated_gate(self):
        workflow = (verify.ROOT / ".github/workflows/ci.yml").read_text()
        self.assertIn("stage: [policy, formatting, static]", workflow)
        self.assertIn("fail-fast: false", workflow)
        self.assertIn("needs: [checks, reactor]", workflow)
        self.assertIn("if: ${{ always() }}", workflow)
        self.assertEqual(workflow.count("if: always()"), 2)
        self.assertNotIn("continue-on-error", workflow)
        for stage in ("compile", "unit", "postgres", "package", "gate"):
            self.assertIn(f"python3 tools/ci/verify.py {stage}", workflow)

    def test_duplicate_binary_names_fail_closed(self):
        self.source()
        self.source(module="domain-kernel")
        with self.assertRaises(ValueError):
            verify.inventory(self.root)

    def test_all_top_level_suites_in_one_file_are_required(self):
        self.source()
        source = self.root / "control-plane/src/test/kotlin/ExampleTest.kt"
        source.write_text(source.read_text() + "\ninternal class AnotherTest { @Test fun other() = Unit }")
        self.assertEqual(verify.expected_tests(self.root, "unit"), {"example.ExampleTest", "example.AnotherTest"})
        self.report("unit")
        self.assertTrue(verify.inspect_reports(self.root, "unit")[1])
        self.report("unit", name="AnotherTest")
        self.assertFalse(verify.inspect_reports(self.root, "unit")[1])
