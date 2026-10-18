"""Check Trivy coverage of every library shipped in a Spring Boot executable JAR."""
import hashlib
import json
from pathlib import Path
import sys
import zipfile


def check(jar, report):
    if report.get("SchemaVersion") != 2:
        raise ValueError("Expected Trivy JSON schema 2")
    with zipfile.ZipFile(jar) as archive:
        names = [n for n in archive.namelist()
                 if n.startswith("BOOT-INF/lib/") and n.endswith(".jar")]
        if not names or len(names) != len(set(names)):
            raise ValueError("Expected unique packaged BOOT-INF/lib JAR entries")
        libraries = {n: hashlib.sha256(archive.read(n)).hexdigest() for n in names}
    packages = [p for r in report.get("Results", []) if r.get("Type") == "jar"
                for p in r.get("Packages", [])]
    covered = set()
    for package in packages:
        path = package.get("FilePath", "")
        if not package.get("Name") or not package.get("Version"):
            continue
        for name in libraries:
            if path == name or path.endswith("/" + name):
                covered.add(name)
    missing = set(libraries) - covered
    if missing:
        raise ValueError(f"Scanner missed packaged libraries: {sorted(missing)}")
    findings = [v for r in report.get("Results", [])
                for v in r.get("Vulnerabilities", [])]
    blocking = [v for v in findings if v.get("Severity") in {"HIGH", "CRITICAL"}
                and v.get("FixedVersion")]
    return {"jar_sha256": hashlib.sha256(Path(jar).read_bytes()).hexdigest(),
            "libraries": libraries, "library_count": len(libraries),
            "finding_entries": len(findings),
            "fixable_high_critical_entries": len(blocking)}


if __name__ == "__main__":
    receipt = check(sys.argv[1], json.loads(Path(sys.argv[2]).read_text()))
    Path(sys.argv[3]).write_text(json.dumps(receipt, indent=2) + "\n")
    print(f"Inventoried {receipt['library_count']} packaged libraries; "
          f"{receipt['finding_entries']} advisory entries; "
          f"{receipt['fixable_high_critical_entries']} fixable high/critical entries.")
    sys.exit(1 if receipt["fixable_high_critical_entries"] else 0)
