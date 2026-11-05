import datetime as dt
import tempfile
import unittest
from pathlib import Path

from check import (AuditError, advisory_blocks, exit_for_findings, findings, plugin_inventory,
                   pnpm_inventory, scan_packages, validate_policy, verify_coverage)


PACKAGE = ("npm", "example", "1.0.0")
OTHER = ("Maven", "example:library", "2.0.0")
POLICY = {"allowedLicenses": ["MIT"], "advisories": "all"}
TODAY = dt.date(2026, 11, 4)


class AuditTest(unittest.TestCase):
    def test_missing_inventory_and_partial_scanner_output_are_errors(self):
        for expected, actual in [({}, {}), ({PACKAGE: ["ui"], OTHER: ["test"]}, {PACKAGE: {}})]:
            with self.subTest(expected=expected):
                with self.assertRaises(AuditError):
                    verify_coverage(expected, actual)

    def test_missing_report_is_not_a_clean_scan(self):
        for report in [{}, {"results": []}, {"results": [{"packages": []}]}]:
            with self.subTest(report=report):
                with self.assertRaises(AuditError):
                    scan_packages(report)

    def test_licenses_and_advisories_are_independent(self):
        result = findings(
            {
                PACKAGE: {"licenses": ["GPL-3.0-only"], "vulnerabilities": [{"id": "GHSA-example"}]},
                OTHER: {},
            },
            POLICY, [], TODAY,
        )
        self.assertEqual(
            {(row["kind"], row["value"], row["status"]) for row in result},
            {
                ("license", "GPL-3.0-only", "blocked"),
                ("advisory", "GHSA-example", "blocked"),
                ("license", "UNKNOWN", "blocked"),
            },
        )

    def test_exceptions_match_exact_package_and_expire(self):
        exception = {
            "ecosystem": "npm", "name": "example", "version": "1.0.0",
            "kind": "license", "value": "GPL-3.0-only", "expires": "2026-11-05",
            "reason": "Reviewed for an isolated fixture", "review": "https://github.com/example/repo/issues/1",
        }
        rows = {
            PACKAGE: {"licenses": ["GPL-3.0-only"]},
            ("npm", "example", "2.0.0"): {"licenses": ["GPL-3.0-only"]},
        }
        self.assertEqual([row["status"] for row in findings(rows, POLICY, [exception], TODAY)],
                         ["excepted", "blocked"])
        expired = dt.date(2026, 11, 5)
        self.assertEqual([row["status"] for row in findings(rows, POLICY, [exception], expired)],
                         ["blocked", "blocked"])

    def test_high_policy_checks_cvss_and_fails_closed_on_unknown_severity(self):
        row = {"groups": [
            {"ids": ["low"], "max_severity": "6.9"},
            {"ids": ["high"], "max_severity": "7.0"},
            {"ids": ["unknown"], "max_severity": ""},
        ]}
        self.assertFalse(advisory_blocks(row, "low", "high"))
        self.assertTrue(advisory_blocks(row, "high", "high"))
        self.assertTrue(advisory_blocks(row, "unknown", "high"))
        self.assertTrue(advisory_blocks(row, "low", "all"))

    def test_policy_cannot_accidentally_disable_enforcement(self):
        with self.assertRaises(AuditError):
            validate_policy({**POLICY, "mode": "typo"})

    def test_pnpm_includes_every_lock_document_and_requires_matching_snapshots(self):
        def doc(name, snapshot):
            return (
                "---\nlockfileVersion: '9.0'\nimporters: {.: {}}\npackages:\n"
                f"  '{name}@1.0.0': {{resolution: {{integrity: sha512-fixture}}}}\n"
                f"snapshots: {{'{snapshot}@1.0.0': {{}}}}\n"
            )
        with tempfile.TemporaryDirectory() as directory:
            lock = Path(directory) / "pnpm-lock.yaml"
            lock.write_text(doc("manager", "manager") + doc("@scope/runtime", "@scope/runtime"))
            self.assertEqual(
                set(pnpm_inventory(lock)),
                {("npm", "manager", "1.0.0"), ("npm", "@scope/runtime", "1.0.0")},
            )
            lock.write_text(doc("manager", "wrong"))
            with self.assertRaises(AuditError):
                pnpm_inventory(lock)

    def test_plugin_inventory_rejects_truncated_reports(self):
        header = "The following plugins have been resolved:\n   g:p:maven-plugin:1:runtime\n"
        with self.assertRaises(AuditError):
            plugin_inventory(header)
        self.assertEqual(
            plugin_inventory(header + "      g:p:jar:1\n      g:lib:jar:2\n"),
            {("Maven", "g:p", "1"), ("Maven", "g:lib", "2")},
        )

    def test_report_mode_keeps_violations_visible_without_enforcing(self):
        rows = {PACKAGE: {"licenses": ["GPL-3.0-only"]}}
        decisions = findings(rows, POLICY, [], TODAY)
        self.assertEqual(decisions[0]["status"], "blocked")
        self.assertEqual(exit_for_findings({"mode": "report"}, decisions), 0)
        self.assertEqual(exit_for_findings({"mode": "enforce"}, decisions), 1)
