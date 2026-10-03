#!/usr/bin/env python3
"""Verify the packaged application over real HTTP, including a file-database restart."""
import json
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request
import sys

ROOT = Path(__file__).resolve().parents[1]
JAR = ROOT / "target/reconledger-0.1.0-SNAPSHOT-app.jar"


def main():
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

    with tempfile.TemporaryDirectory(prefix="reconledger-smoke-") as scratch:
        directory = Path(scratch)
        process = None

        def start(log):
            nonlocal process
            process = subprocess.Popen(["java", "-jar", str(JAR), f"--server.port={port}"],
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
                receipt_path = directory / "receipt.json"
                subprocess.run([sys.executable, str(ROOT / "scripts/demo.py"), "--base-url", base,
                                "--output", str(receipt_path)], check=True, timeout=45)
                receipt = json.loads(receipt_path.read_text())
                stop()
                assert list((directory / ".local").glob("*.mv.db")), "Expected a persistent H2 database file"
                start(log)
                status, batch = call("/api/batches/" + receipt["batchId"])
                assert status == 200 and batch["counts"] == receipt["counts"]
                _, result = call("/api/results/" + receipt["resultId"])
                assert result["version"] == 2 and result["reviewState"] == "RESOLVED"
                _, events = call("/api/results/" + receipt["resultId"] + "/events")
                assert events == receipt["events"]
                payload = json.loads((ROOT / "examples/mixed-batch.json").read_text())
                status, replay = call("/api/batches", payload, {"Idempotency-Key": receipt["idempotencyKey"]})
                assert status == 200 and replay["id"] == receipt["batchId"]
                print("Restart check passed: source evidence, review state, audit events and idempotency survived.")
        except Exception:
            print(log_path.read_text()[-8000:], file=sys.stderr)
            raise
        finally:
            stop()


if __name__ == "__main__":
    main()
