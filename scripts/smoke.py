#!/usr/bin/env python3
"""Verify real HTTP and process restart against H2 or an explicitly supplied test PostgreSQL database."""
import argparse
import http.client
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import sys
import uuid

ROOT = Path(__file__).resolve().parents[1]
JAR = ROOT / "target/reconledger-0.1.0-SNAPSHOT-app.jar"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database", choices=["h2", "postgres"], default="h2")
    args = parser.parse_args()
    if args.database == "postgres":
        required = ("RECON_DB_URL", "RECON_DB_USER", "RECON_DB_PASSWORD")
        missing = [key for key in required if not os.environ.get(key)]
        if missing:
            raise SystemExit("PostgreSQL smoke check requires: " + ", ".join(missing))
        if not os.environ["RECON_DB_URL"].startswith("jdbc:postgresql:"):
            raise SystemExit("RECON_DB_URL must select a PostgreSQL test database.")
    if not JAR.exists():
        raise SystemExit("Run ./mvnw verify before this smoke check.")
    with socket.socket() as reserved:
        reserved.bind(("127.0.0.1", 0))
        port = reserved.getsockname()[1]
    base = f"http://127.0.0.1:{port}"
    # The service is local; avoid sending these requests through an external proxy.
    client = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def call(path, body=None, headers=None):
        request = urllib.request.Request(base + path,
            data=None if body is None else json.dumps(body).encode(),
            headers={"Content-Type": "application/json", **(headers or {})})
        with client.open(request, timeout=5) as response:
            return response.status, json.load(response)

    def check_body_limit():
        payload = json.loads((ROOT / "examples/mixed-batch.json").read_text())
        encoded = json.dumps(payload).encode()
        oversized = encoded + b" " * (524289 - len(encoded))
        key = "body-smoke-" + str(uuid.uuid4())
        for chunked in (False, True):
            connection = http.client.HTTPConnection("127.0.0.1", port, timeout=10)
            try:
                body = (oversized[i:i + 8192] for i in range(0, len(oversized), 8192)) if chunked else oversized
                connection.request("POST", "/api/batches", body=body, encode_chunked=chunked,
                    headers={"Content-Type": "application/json", "Idempotency-Key": key})
                response = connection.getresponse()
                problem = json.loads(response.read())
                assert response.status == 413 and problem["status"] == 413
            finally:
                connection.close()
        status, _ = call("/api/batches", payload, {"Idempotency-Key": key})
        assert status == 201, "Rejected oversized requests must not reserve the key"
        print("Body limit check passed: fixed-length and chunked HTTP rejection, then a safe retry.")

    with tempfile.TemporaryDirectory(prefix="reconledger-smoke-") as scratch:
        directory = Path(scratch)
        process = None

        def start(log):
            nonlocal process
            command = ["java", "-jar", str(JAR), f"--server.port={port}", "--server.address=127.0.0.1"]
            if args.database == "postgres":
                command.append("--spring.profiles.active=postgres")
            else:
                # Keep the default smoke test isolated even if the caller has database env vars.
                command.extend(["--spring.profiles.active=default",
                    "--spring.datasource.url=jdbc:h2:file:./.local/reconledger;DB_CLOSE_ON_EXIT=FALSE",
                    "--spring.datasource.username=sa", "--spring.datasource.password="])
            process = subprocess.Popen(command,
                cwd=directory, stdout=log, stderr=subprocess.STDOUT)
            deadline = time.monotonic() + 40
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError("Application exited during startup")
                try:
                    status, health = call("/actuator/health")
                    if status == 200 and health["status"] == "UP":
                        return
                except (OSError, urllib.error.URLError):
                    pass
                time.sleep(0.2)
            raise RuntimeError("Application did not become healthy within 40 seconds")

        def stop():
            nonlocal process
            if process is not None:
                process.terminate()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
                process = None

        log_path = directory / "application.log"
        try:
            with log_path.open("w") as log:
                start(log)
                check_body_limit()
                receipt_path = directory / "receipt.json"
                subprocess.run([sys.executable, str(ROOT / "scripts/demo.py"), "--base-url", base,
                                "--output", str(receipt_path)], check=True, timeout=45)
                receipt = json.loads(receipt_path.read_text())
                stop()
                if args.database == "h2":
                    assert list((directory / ".local").glob("*.mv.db")), "Expected a persistent H2 database file"
                start(log)
                status, batch = call("/api/batches/" + receipt["batchId"])
                assert status == 200 and batch["counts"] == receipt["counts"]
                status, listed = call(receipt["batchListPath"])
                assert status == 200
                assert next(item for item in listed["batches"] if item["id"] == receipt["batchId"]) == receipt["batchSummary"]
                _, result = call("/api/results/" + receipt["resultId"])
                assert result["version"] == 2 and result["reviewState"] == "RESOLVED"
                events_path = "/api/results/" + receipt["resultId"] + "/events"
                _, page = call(events_path)
                assert page["events"] == receipt["events"] and page["nextAfterSequence"] is None
                _, resumed = call(events_path + f"?limit=1&afterSequence={receipt['auditCursor']}")
                assert resumed["events"] == receipt["events"][1:] and resumed["nextAfterSequence"] is None
                payload = json.loads((ROOT / "examples/mixed-batch.json").read_text())
                status, replay = call("/api/batches", payload, {"Idempotency-Key": receipt["idempotencyKey"]})
                assert status == 200 and replay["id"] == receipt["batchId"]
                print(f"Restart check passed ({args.database}): source evidence, review state, audit cursor and idempotency survived.")
        except Exception:
            print(log_path.read_text()[-8000:], file=sys.stderr)
            raise
        finally:
            stop()


if __name__ == "__main__":
    main()
