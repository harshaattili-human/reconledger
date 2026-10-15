#!/usr/bin/env python3
"""Rehearse a logical PostgreSQL restore in two newly created disposable containers."""
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

from secured_smoke import AUDIENCE, ISSUER, JAR, ROOT, key_pair, require, token


def run(*args, **kwargs):
    return subprocess.run(list(args), check=True, timeout=120, **kwargs)


def main():
    require(JAR.exists(), "Run ./mvnw verify first")
    report = ROOT / "target/postgres-restore.json"
    report.unlink(missing_ok=True)
    run_id = uuid.uuid4().hex
    containers = []
    process = None
    client = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with socket.socket() as reserved:
        reserved.bind(("127.0.0.1", 0))
        port = reserved.getsockname()[1]
    base = f"http://127.0.0.1:{port}"
    password = uuid.uuid4().hex  # Disposable cluster credential; never included in the report.
    env = {k: v for k, v in os.environ.items()
           if not k.startswith(("SPRING_", "RECON_", "PG", "POSTGRES_"))
           and k not in {"JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"}}
    env.update(POSTGRES_PASSWORD=password, RECON_DB_USER="recon_restore", RECON_DB_PASSWORD=password)

    def docker(*args, **kwargs):
        return run("docker", *args, env=env, **kwargs)

    def sql(container, query, database="reconledger"):
        return docker("exec", container, "psql", "-X", "-v", "ON_ERROR_STOP=1", "-At",
                      "-U", "recon_restore", "-d", database, "-c", query,
                      capture_output=True).stdout.decode().strip()

    def cluster(label):
        name = "recon-restore-" + run_id + "-" + label
        # No supplied container/database names or host mounts: only this run's resources are touched.
        docker("run", "--detach", "--name", name, "-e", "POSTGRES_PASSWORD",
               "-e", "POSTGRES_USER=recon_restore", "-e", "POSTGRES_DB=reconledger",
               "-p", "127.0.0.1::5432", "postgres:16", capture_output=True)
        containers.append(name)
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            ready = subprocess.run(["docker", "exec", name, "pg_isready", "-U", "recon_restore",
                                    "-d", "reconledger"], capture_output=True, timeout=5, env=env)
            if ready.returncode == 0:
                break
            time.sleep(0.2)
        else:
            raise RuntimeError("Disposable PostgreSQL did not become ready")
        binding = json.loads(docker("inspect", "--format", "{{json .NetworkSettings.Ports}}",
                                    name, capture_output=True).stdout)["5432/tcp"][0]
        require(binding["HostIp"] == "127.0.0.1", "Database must be loopback-only")
        return name, "jdbc:postgresql://127.0.0.1:" + binding["HostPort"] + "/reconledger"

    def call(path, bearer=None, body=None, key=None, expected=200):
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
            if key and expected in (200, 201):
                require(response.headers.get("Idempotency-Replayed") == str(expected == 200).lower(),
                        "Incorrect idempotency replay header")
            return json.loads(raw) if raw else None

    def stop():
        nonlocal process
        if process is not None:
            process.terminate()
            try:
                process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
                raise RuntimeError("Application needed forced shutdown")
            finally:
                process = None

    tables = {"recon_batch": "id", "source_record": "batch_id,source_side,record_id",
              "recon_result": "id", "review_event": "sequence", "flyway_schema_history": "installed_rank"}

    def snapshot(container):
        return {table: sql(container, f"SELECT row_to_json(t)::text FROM {table} t ORDER BY {order}")
                for table, order in tables.items()}

    def sequences(container):
        return json.loads(sql(container, "SELECT json_object_agg(sequencename,last_value) FROM pg_sequences WHERE schemaname='public'"))

    with tempfile.TemporaryDirectory(prefix="reconledger-restore-") as scratch:
        directory = Path(scratch)
        private, public = key_pair(directory, "synthetic-private")
        public_file = directory / "trusted-public.pem"
        public_file.write_bytes(public)
        expiry = int(time.time()) + 1800
        tokens = [token(private, "restore-" + t, expiry) for t in ("alpha", "beta")]
        payload = json.loads((ROOT / "examples/mixed-batch.json").read_text())
        log_path = directory / "application.log"

        def start(url, log):
            nonlocal process
            app_env = {**env, "RECON_DB_URL": url}
            process = subprocess.Popen(["java", "-jar", str(JAR), f"--server.port={port}",
                "--server.address=127.0.0.1", "--spring.profiles.active=postgres,secured",
                "--recon.security.public-key=" + public_file.as_uri(), "--recon.security.issuer=" + ISSUER,
                "--recon.security.audience=" + AUDIENCE], cwd=directory, env=app_env,
                stdout=log, stderr=subprocess.STDOUT)
            deadline = time.monotonic() + 45
            while time.monotonic() < deadline:
                require(process.poll() is None, "Packaged application exited during startup")
                try:
                    require(call("/actuator/health") == {"status": "UP"}, "Unexpected health response")
                    return
                except (OSError, urllib.error.URLError):
                    time.sleep(0.2)
            raise RuntimeError("Application did not become healthy")

        try:
            source, source_url = cluster("source")
            target, target_url = cluster("target")
            with log_path.open("w") as log:
                start(source_url, log)
                batch_ids, results, events, pages = [], [], [], []
                for auth in tokens:
                    batches = [call("/api/batches", auth, payload, "restore-key-" + str(i), 201) for i in range(2)]
                    batch_ids.append([b["id"] for b in batches])
                    result = next(r for r in batches[0]["results"] if r["reference"] == "INV-101")
                    path = "/api/results/" + result["id"]
                    for version, state in enumerate(("IN_REVIEW", "RESOLVED")):
                        call(path + "/reviews", auth, {"targetState": state, "expectedVersion": version,
                             "note": "Synthetic restore rehearsal: " + state})
                    results.append(call(path, auth))
                    events.append(call(path + "/events?limit=1", auth))
                    pages.append(call("/api/batches?limit=1", auth))
                stop()  # Quiesce this fixture's only writer before capturing expected state and archive.
                for table, column in (("recon_batch", "list_sequence"), ("review_event", "sequence")):
                    sql(source, f"SELECT nextval(pg_get_serial_sequence('{table}','{column}')) FROM generate_series(1,7)")
                before = snapshot(source)
                before_sequences = sequences(source)
                versions = {"server": sql(source, "SHOW server_version"),
                            "pg_dump": docker("exec", source, "pg_dump", "--version", capture_output=True).stdout.decode().strip(),
                            "pg_restore": docker("exec", target, "pg_restore", "--version", capture_output=True).stdout.decode().strip()}
                archive = directory / "snapshot.dump"
                with archive.open("wb") as output:
                    docker("exec", source, "pg_dump", "-U", "recon_restore", "-d", "reconledger", "-Fc", stdout=output)
                archive.chmod(0o600)
                # A source-side commit after the backup must be absent from the restored branch.
                start(source_url, log)
                later = call("/api/batches", tokens[0], payload, "after-backup-key", 201)
                call("/api/results/" + results[0]["id"] + "/reviews", tokens[0],
                     {"targetState": "IN_REVIEW", "expectedVersion": 2, "note": "After the backup."})
                stop()
                empty_query = "SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace WHERE n.nspname='public' AND c.relkind IN ('r','p','S','v','m','f')"
                require(sql(target, empty_query) == "0", "Refusing restore into a nonempty target")
                docker("exec", target, "createdb", "-U", "recon_restore", "restore_rejected", capture_output=True)
                broken = subprocess.run(["docker", "exec", "-i", target, "pg_restore", "-U", "recon_restore",
                    "-d", "restore_rejected", "--exit-on-error", "--single-transaction", "--no-owner", "--no-acl"],
                    input=archive.read_bytes()[:archive.stat().st_size // 2], capture_output=True, timeout=120, env=env)
                require(broken.returncode != 0, "Truncated archive unexpectedly restored")
                require(sql(target, empty_query, "restore_rejected") == "0", "Failed restore left partial objects")
                with archive.open("rb") as data:
                    docker("exec", "-i", target, "pg_restore", "-U", "recon_restore", "-d", "reconledger",
                           "--exit-on-error", "--single-transaction", "--no-owner", "--no-acl", stdin=data)
                require(snapshot(target) == before, "Restored SQL rows differ from the backup checkpoint")
                require(sequences(target) == before_sequences, "Allocated sequence state was not restored")
                start(target_url, log)  # Also exercises Flyway validation of restored migration history.
                call("/api/batches", expected=401)
                for index, auth in enumerate(tokens):
                    for i, batch_id in enumerate(batch_ids[index]):
                        detail = call("/api/batches/" + batch_id, auth)
                        require(call("/api/batches", auth, payload, "restore-key-" + str(i)) == detail,
                                "Restored idempotency replay differs from current batch")
                    call("/api/batches", auth, {**payload, "currency": "EUR"}, "restore-key-0", 409)
                    page = call("/api/batches?limit=1", auth)
                    require(page == pages[index], "Restored first page changed")
                    tail = call("/api/batches?limit=1&beforeSequence=" + str(page["nextBeforeSequence"]), auth)
                    require([b["id"] for b in tail["batches"]] == [batch_ids[index][0]]
                            and tail["nextBeforeSequence"] is None, "Saved batch cursor did not resume")
                    path = "/api/results/" + results[index]["id"]
                    require(call(path, auth) == results[index], "Restored review state/evidence changed")
                    require(call(path + "/events?limit=1", auth) == events[index], "Restored audit page changed")
                    cursor = events[index]["nextAfterSequence"]
                    tail = call(path + "/events?limit=1&afterSequence=" + str(cursor), auth)
                    require(len(tail["events"]) == 1 and tail["events"][0]["resultingVersion"] == 2
                            and tail["nextAfterSequence"] is None, "Saved audit cursor did not resume")
                    foreign = tokens[1 - index]
                    call("/api/batches/" + batch_ids[index][0], foreign, expected=404)
                    call(path, foreign, expected=404)
                    call(path + "/events", foreign, expected=404)
                    review = {"targetState": "IN_REVIEW", "expectedVersion": 2, "note": "After restore."}
                    call(path + "/reviews", foreign, review, expected=404)
                    call(path + "/reviews", auth, {**review, "expectedVersion": 1}, expected=409)
                    require(call(path, auth) == results[index], "Rejected review changed restored state")
                    changed = call(path + "/reviews", auth, review)
                    require(changed["version"] == 3 and changed["resolutionNote"] is None, "Restored review cannot advance")
                    appended = call(path + "/events?afterSequence=" + str(tail["events"][0]["sequence"]), auth)
                    require(len(appended["events"]) == 1 and appended["events"][0]["sequence"] > max(before_sequences.values())
                            and appended["events"][0]["actor"] == "synthetic-reviewer", "Restored audit sequence/identity failed")
                call("/api/batches/" + later["id"], tokens[0], expected=404)
                fresh = call("/api/batches", tokens[0], payload, "after-backup-key", 201)
                require(fresh["id"] != later["id"], "Post-backup key unexpectedly retained")
                newest = call("/api/batches?limit=1", tokens[0])["batches"][0]
                require(newest["id"] == fresh["id"] and newest["sequence"] > max(before_sequences.values()),
                        "Restored batch sequence did not advance beyond allocated gap")
                stop()
                receipt = {"source_commit": run("git", "rev-parse", "HEAD", cwd=ROOT, capture_output=True).stdout.decode().strip(),
                    "jar_sha256": hashlib.sha256(JAR.read_bytes()).hexdigest(), "python": sys.version.split()[0],
                    "postgres": versions, "image_id": docker("inspect", "--format", "{{.Image}}", source, capture_output=True).stdout.decode().strip(),
                    "archive_sha256": hashlib.sha256(archive.read_bytes()).hexdigest(), "archive_bytes": archive.stat().st_size,
                    "checkpoint_rows": {table: len(rows.splitlines()) for table, rows in before.items()},
                    "allocated_sequences": before_sequences,
                    "checks": ["truncated-archive-rejected-without-partial-objects", "full-table-and-flyway-row-equality", "sequence-gaps-retained", "tenant-boundaries",
                               "key-replay-and-conflict", "batch-and-audit-cursor-resume", "new-reviews-and-batch",
                               "post-backup-writes-absent"],
                    "limitations": "Quiesced synthetic logical backup to a fresh PostgreSQL cluster; no crash, PITR, roles/ACL recovery, external issuer, H2 or recovery-time objective."}
                report.write_text(json.dumps(receipt, indent=2) + "\n")
                print(json.dumps(receipt, indent=2))
        except Exception:
            if log_path.exists():
                print(log_path.read_text()[-8000:], file=sys.stderr)
            raise
        finally:
            try:
                stop()
            finally:
                for name in reversed(containers):
                    docker("rm", "--force", "--volumes", name, capture_output=True)


if __name__ == "__main__":
    main()
