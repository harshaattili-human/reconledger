#!/usr/bin/env python3
"""Exercise the secured executable JAR, retained tenants and pinned-key replacement."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
JAR = ROOT / "target/reconledger-0.1.0-SNAPSHOT-app.jar"
ISSUER = "https://issuer.example.invalid/packaged-smoke"
AUDIENCE = "reconledger-smoke"
SCOPES = "recon:read recon:write recon:review"


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def openssl(*args, data=None):
    return subprocess.run(["openssl", *map(str, args)], input=data,
                          capture_output=True, check=True, timeout=30).stdout


def key_pair(directory, name):
    private = directory / (name + ".pem")
    openssl("genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048", "-out", private)
    private.chmod(0o600)
    return private, openssl("pkey", "-in", private, "-pubout")


def token(private, tenant, expiry, scope=SCOPES):
    def encode(value):
        return base64.urlsafe_b64encode(value).rstrip(b"=")
    header = encode(b'{"alg":"RS256","typ":"JWT"}')
    claims = encode(json.dumps({"iss": ISSUER, "aud": AUDIENCE, "sub": "synthetic-reviewer",
                               "tenant_id": tenant, "scope": scope, "exp": expiry}).encode())
    signed = header + b"." + claims
    signature = openssl("dgst", "-sha256", "-sign", private, data=signed)
    return (signed + b"." + encode(signature)).decode()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database", choices=["h2", "postgres"], default="h2")
    args = parser.parse_args()
    if args.database == "postgres":
        for name in ("RECON_DB_URL", "RECON_DB_USER", "RECON_DB_PASSWORD"):
            require(os.environ.get(name), "PostgreSQL smoke requires " + name)
        require(os.environ["RECON_DB_URL"].startswith("jdbc:postgresql:"), "Expected a disposable PostgreSQL URL")
    require(JAR.exists(), "Run ./mvnw verify before this check")
    report = ROOT / f"target/secured-smoke-{args.database}.json"
    report.unlink(missing_ok=True)  # A failed rerun must not leave an old success report.
    with socket.socket() as reserved:
        reserved.bind(("127.0.0.1", 0))
        port = reserved.getsockname()[1]
    client = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    base = f"http://127.0.0.1:{port}"

    def call(path, expected=200, bearer=None, body=None, key=None):
        headers = {"Content-Type": "application/json"}
        if bearer:
            headers["Authorization"] = "Bearer " + bearer
        if key:
            headers["Idempotency-Key"] = key
        request = urllib.request.Request(base + path, headers=headers,
            data=None if body is None else json.dumps(body).encode())
        try:
            response = client.open(request, timeout=5)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            raw = response.read()
            require(response.status == expected, f"{request.get_method()} {path}: expected {expected}, got {response.status}")
            if expected == 401:
                require(response.headers.get("WWW-Authenticate", "").startswith("Bearer"), "Missing bearer challenge")
            return json.loads(raw) if raw else None

    with tempfile.TemporaryDirectory(prefix="reconledger-secured-") as scratch:
        directory = Path(scratch)
        first_key, first_public = key_pair(directory, "first-private")
        second_key, second_public = key_pair(directory, "second-private")
        public_file = directory / "trusted-public.pem"
        public_file.write_bytes(first_public)
        # Isolate profile/trust selection from the invoking shell. Explicit DB variables remain.
        env = {k: v for k, v in os.environ.items()
               if not k.startswith(("SPRING_", "RECON_SECURITY_"))
               and k not in {"JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"}}
        process = None
        log_path = directory / "application.log"

        def command(secured=True):
            profiles = ("postgres," if args.database == "postgres" else "") + ("secured" if secured else "default")
            result = ["java", "-jar", str(JAR), f"--server.port={port}", "--server.address=127.0.0.1",
                      "--spring.profiles.active=" + profiles]
            if args.database == "h2":
                result += ["--spring.datasource.url=jdbc:h2:file:./.local/reconledger;DB_CLOSE_ON_EXIT=FALSE",
                           "--spring.datasource.username=sa", "--spring.datasource.password="]
            if secured:
                result += ["--recon.security.public-key=" + public_file.as_uri(),
                           "--recon.security.issuer=" + ISSUER, "--recon.security.audience=" + AUDIENCE]
            return result

        def start(log, secured=True):
            nonlocal process
            process = subprocess.Popen(command(secured), cwd=directory, env=env,
                                       stdout=log, stderr=subprocess.STDOUT)
            deadline = time.monotonic() + 45
            while time.monotonic() < deadline:
                require(process.poll() is None, "Packaged application exited during startup")
                try:
                    health = call("/actuator/health")
                    require(health == {"status": "UP"}, "Unexpected health response")
                    return
                except (OSError, urllib.error.URLError):
                    time.sleep(0.2)
            raise RuntimeError("Packaged application did not become healthy within 45 seconds")

        def stop():
            nonlocal process
            if process is None:
                return
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
                raise RuntimeError("Application needed forced shutdown")
            finally:
                process = None

        run_id = uuid.uuid4().hex
        tenants = ["smoke-a-" + run_id, "smoke-b-" + run_id]
        expiry = int(time.time()) + 900
        old_tokens = [token(first_key, tenant, expiry) for tenant in tenants]
        new_tokens = [token(second_key, tenant, expiry) for tenant in tenants]
        payload = json.loads((ROOT / "examples/mixed-batch.json").read_text())
        key = "secured-smoke-" + run_id
        review = {"targetState": "IN_REVIEW", "expectedVersion": 0, "note": "Checking synthetic evidence."}
        try:
            # Prove a rejected packaged startup is a trust error, not an unrelated launch failure.
            invalid_log = directory / "invalid-trust.log"
            invalid = command()
            invalid[-1] = "--recon.security.audience="
            with invalid_log.open("w") as log:
                process = subprocess.Popen(invalid, cwd=directory, env=env, stdout=log, stderr=subprocess.STDOUT)
                code = process.wait(timeout=45)
            process = None
            require(code != 0 and "recon.security.audience must not be blank" in invalid_log.read_text(),
                    "Blank audience did not fail startup for the expected reason")
            print("Packaged trust check passed: blank audience prevents startup.")
            with log_path.open("w") as log:
                start(log)
                call("/api/batches", 401)
                call("/api/batches", 401, new_tokens[0])
                read_only = token(first_key, tenants[0], expiry, "recon:read")
                call("/api/batches", 403, read_only, payload, key)
                batches = [call("/api/batches", 201, auth, payload, key) for auth in old_tokens]
                require(batches[0]["id"] != batches[1]["id"], "Tenants shared an idempotency result")
                result = next(r for r in batches[0]["results"] if r["reference"] == "INV-101")
                result_path = "/api/results/" + result["id"]
                events_path = result_path + "/events"
                call(result_path + "/reviews", 404, old_tokens[1], review)
                call(result_path + "/reviews", 400, old_tokens[0], {**review, "actor": "forged-reviewer"})
                require(call(events_path, bearer=old_tokens[0])["events"] == [], "Rejected review wrote audit")
                changed = call(result_path + "/reviews", bearer=old_tokens[0], body=review)
                require(changed["version"] == 1, "Initial review did not commit")
                events = call(events_path, bearer=old_tokens[0])["events"]
                require(len(events) == 1 and events[0]["actor"] == "synthetic-reviewer", "Reviewer attribution failed")
                for own, foreign, auth in zip(batches, reversed(batches), old_tokens):
                    listed = call("/api/batches?limit=1", bearer=auth)
                    require([b["id"] for b in listed["batches"]] == [own["id"]]
                            and listed["nextBeforeSequence"] is None, "Tenant list leaked another batch")
                    call("/api/batches/" + foreign["id"], 404, auth)
                print("Secured JAR checks passed: scopes, two tenant keys, foreign IDs and verified review attribution.")
                stop()
                if args.database == "h2":
                    require(list((directory / ".local").glob("*.mv.db")), "No persistent H2 file")
                public_file.write_bytes(second_public)
                start(log)
                require(time.time() < expiry - 60, "Old token expired before replacement check")
                call("/api/batches", 401, old_tokens[0])
                call(result_path + "/reviews", 401, old_tokens[0],
                     {**review, "targetState": "RESOLVED", "expectedVersion": 1})
                for batch, auth in zip(batches, new_tokens):
                    replay = call("/api/batches", bearer=auth, body=payload, key=key)
                    require(replay["id"] == batch["id"], "Tenant key did not survive restart")
                    require(replay["counts"] == batch["counts"], "Reconciliation changed after restart")
                    require(len(replay["results"]) == len(batch["results"]), "Result count changed after restart")
                    for before, after in zip(batch["results"], replay["results"]):
                        require(all(before[field] == after[field] for field in ("id", "outcome", "leftRecords", "rightRecords")),
                                "Source evidence changed after restart")
                require(call(events_path, bearer=new_tokens[0])["events"] == events, "Audit changed during restart/rejection")
                current = call(result_path, bearer=new_tokens[0])
                require(current["version"] == 1 and current["reviewState"] == "IN_REVIEW", "Review state not retained")
                call(result_path, 404, new_tokens[1])
                call(events_path, 404, new_tokens[1])
                resolved = call(result_path + "/reviews", bearer=new_tokens[0],
                                body={**review, "targetState": "RESOLVED", "expectedVersion": 1})
                require(resolved["version"] == 2, "New-key review did not commit")
                resumed = call(events_path + f"?afterSequence={events[0]['sequence']}", bearer=new_tokens[0])
                require(len(resumed["events"]) == 1 and resumed["events"][0]["resultingVersion"] == 2
                        and resumed["events"][0]["actor"] == "synthetic-reviewer"
                        and resumed["nextAfterSequence"] is None, "Audit cursor did not resume")
                print("Pinned-key restart passed: old unexpired token rejected, new key accepted, tenant state/cursor retained.")
                stop()
                start(log, secured=False)
                for batch in batches:
                    call("/api/batches/" + batch["id"], 404)
                call(result_path, 404)
                call(events_path, 404)
                call(result_path + "/reviews", 404, body={**review, "actor": "demo-reviewer"})
                demo = call("/api/batches", 201, body=payload, key=key)
                require(demo["id"] not in {b["id"] for b in batches}, "Demo reused a private tenant key")
                stop()
                print("Demo profile check passed: private tenant IDs stay inaccessible; the same key creates a demo batch.")
            report.write_text(json.dumps({"database_profile": args.database, "jar_sha256": hashlib.sha256(JAR.read_bytes()).hexdigest(),
                "openssl": openssl("version").decode().strip(), "python": sys.version.split()[0],
                "checks": ["blank-audience-startup-rejected", "scopes-and-tenant-boundaries", "authenticated-review",
                           "pinned-key-replacement-on-restart", "retained-evidence-idempotency-audit-cursor", "demo-profile-isolation"],
                "limitations": "Synthetic local signer; no external issuer, automatic rotation, revocation service or database restart."}, indent=2) + "\n")
            print(f"Secured packaged smoke passed ({args.database}); report: {report.relative_to(ROOT)}")
        except Exception:
            if log_path.exists():
                print(log_path.read_text()[-8000:], file=sys.stderr)
            raise
        finally:
            stop()


if __name__ == "__main__":
    main()
