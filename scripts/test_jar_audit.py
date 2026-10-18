"""Synthetic archive/report tests; these do not run the vulnerability scanner."""
import copy
import tempfile
import unittest
from pathlib import Path
import zipfile

from check_jar_audit import check


class JarAuditTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.jar = Path(self.temp.name) / "app.jar"
        with zipfile.ZipFile(self.jar, "w") as archive:
            archive.writestr("BOOT-INF/lib/example-1.jar", b"synthetic library")
        self.report = {"SchemaVersion": 2, "Results": [{"Type": "jar", "Packages": [
            {"Name": "example:example", "Version": "1",
             "FilePath": "application.jar/BOOT-INF/lib/example-1.jar"}]}]}

    def test_inventory_and_hash(self):
        result = check(self.jar, self.report)
        self.assertEqual(1, result["library_count"])
        self.assertEqual(64, len(result["jar_sha256"]))
        self.assertEqual(0, result["fixable_high_critical_entries"])

    def test_missing_or_wrong_path_and_unidentified_package(self):
        for changes in ({"FilePath": "other/example-1.jar"}, {"Version": ""},
                        {"Name": ""}, {"FilePath": ""}):
            with self.subTest(changes=changes):
                report = copy.deepcopy(self.report)
                report["Results"][0]["Packages"][0].update(changes)
                with self.assertRaises(ValueError):
                    check(self.jar, report)

    def test_empty_and_wrong_schema(self):
        for report in ({}, {"SchemaVersion": 2, "Results": []}):
            with self.assertRaises(ValueError):
                check(self.jar, report)
        with zipfile.ZipFile(self.jar, "w") as archive:
            archive.writestr("app.class", b"plain jar")
        with self.assertRaises(ValueError):
            check(self.jar, self.report)

    def test_unfiltered_findings_and_gate(self):
        self.report["Results"][0]["Vulnerabilities"] = [
            {"Severity": "HIGH", "FixedVersion": "2"},
            {"Severity": "CRITICAL"}, {"Severity": "LOW", "FixedVersion": "2"}]
        result = check(self.jar, self.report)
        self.assertEqual(3, result["finding_entries"])
        self.assertEqual(1, result["fixable_high_critical_entries"])


if __name__ == "__main__":
    unittest.main()
