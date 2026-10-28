#!/usr/bin/env python3
"""Exercise the executable JAR against a disposable local HTTPS JWKS server."""
import argparse
import base64
from contextlib import contextmanager
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import socket
import ssl
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request
import uuid

from secured_smoke import ROOT, JAR, ISSUER, AUDIENCE, SCOPES, openssl, require


def encode(value):
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode()


def signing_key(directory, kid):
    private = directory / f"{kid}.pem"
    openssl("genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048",
            "-pkeyopt", "rsa_keygen_pubexp:65537", "-out", private)
    private.chmod(0o600)
    modulus = openssl("rsa", "-in", private, "-modulus", "-noout").decode().strip().split("=", 1)[1]
    return private, {"kty": "RSA", "kid": kid, "use": "sig", "alg": "RS256",
                     "n": encode(bytes.fromhex(modulus)), "e": encode(b"\x01\x00\x01")}


def token(private, kid, tenant, expiry, scope=SCOPES):
    header = encode(json.dumps({"alg": "RS256", "typ": "JWT", "kid": kid}).encode())
    claims = encode(json.dumps({"iss": ISSUER, "aud": AUDIENCE, "sub": "synthetic-reviewer",
                               "tenant_id": tenant, "scope": scope, "exp": expiry}).encode())
    signed = (header + "." + claims).encode()
    return signed.decode() + "." + encode(openssl("dgst", "-sha256", "-sign", private, data=signed))


def certificates(directory):
    """Two CAs; valid host, wrong host, and untrusted-chain server certificates."""
    for name in ("trusted", "rogue"):
        openssl("req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "2",
                "-subj", f"/CN=ReconLedger synthetic {name} CA", "-keyout", directory / f"{name}.key",
                "-out", directory / f"{name}.crt", "-addext", "basicConstraints=critical,CA:TRUE",
                "-addext", "keyUsage=critical,keyCertSign,cRLSign")
        (directory / f"{name}.key").chmod(0o600)
    for name, ca, san in (("valid", "trusted", "IP:127.0.0.1"),
                          ("wrong-host", "trusted", "DNS:wrong.example.invalid"),
                          ("untrusted", "rogue", "IP:127.0.0.1")):
        openssl("req", "-new", "-newkey", "rsa:2048", "-nodes", "-subj", "/CN=Synthetic JWKS",
                "-keyout", directory / f"{name}.key", "-out", directory / f"{name}.csr")
        (directory / f"{name}.key").chmod(0o600)
        ext = directory / f"{name}.ext"
        ext.write_text("basicConstraints=critical,CA:FALSE\nkeyUsage=critical,digitalSignature,keyEncipherment\n"
                       "extendedKeyUsage=serverAuth\nsubjectAltName=" + san + "\n")
        openssl("x509", "-req", "-in", directory / f"{name}.csr", "-CA", directory / f"{ca}.crt",
                "-CAkey", directory / f"{ca}.key", "-set_serial", str(100 + len(name)), "-days", "2",
                "-extfile", ext, "-out", directory / f"{name}.crt")
    store = directory / "trust.p12"
    subprocess.run(["keytool", "-importcert", "-noprompt", "-alias", "synthetic-ca", "-file",
                    str(directory / "trusted.crt"), "-keystore", str(store), "-storetype", "PKCS12",
                    "-storepass", "disposable-trust"], capture_output=True, check=True, timeout=30)
    store.chmod(0o600)
    return store


@contextmanager
def jwks_server(directory, certificate, initial_key):
    state = {"keys": [initial_key], "requests": 0, "status": 200}

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, *args):
            pass

        def do_GET(self):
            require(self.path == "/jwks", "Unexpected JWKS request path")
            state["requests"] += 1
            body = json.dumps({"keys": state["keys"]}).encode()
            self.send_response(state["status"])
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(body)
            self.close_connection = True

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(directory / f"{certificate}.crt", directory / f"{certificate}.key")
    server.socket = context.wrap_socket(server.socket, server_side=True)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield f"https://127.0.0.1:{server.server_port}/jwks", state
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)
        require(not thread.is_alive(), "HTTPS fixture did not shut down")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--database", choices=["h2", "postgres"], default="h2")
    args = parser.parse_args()
    require(JAR.is_file(), "Run ./mvnw verify before this check")
    if args.database == "postgres":
        for name in ("RECON_DB_URL", "RECON_DB_USER", "RECON_DB_PASSWORD"):
            require(os.environ.get(name), "Disposable PostgreSQL configuration required: " + name)
        require(os.environ["RECON_DB_URL"].startswith("jdbc:postgresql:"), "Expected PostgreSQL URL")
    report = ROOT / f"target/jwks-smoke-{args.database}.json"
    report.unlink(missing_ok=True)
    client = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    run_id = uuid.uuid4().hex
    tenant = "tls-" + run_id
    payload = json.loads((ROOT / "examples/mixed-batch.json").read_text())
    observations = []
    with tempfile.TemporaryDirectory(prefix="recon-jwks-") as temporary:
        directory = Path(temporary)
        truststore = certificates(directory)
        first, jwk_a = signing_key(directory, "key-a")
        second, jwk_b = signing_key(directory, "key-b")
        expiry = int(time.time()) + 900
        auth_a = token(first, "key-a", tenant, expiry)
        auth_b = token(second, "key-b", tenant, expiry)
        env = {k: v for k, v in os.environ.items() if not k.startswith(("SPRING_", "RECON_SECURITY_"))
               and k not in {"JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"}}
        for certificate in ("untrusted", "wrong-host", "valid"):
            with jwks_server(directory, certificate, jwk_a) as (uri, state), tempfile.TemporaryFile(mode="w+") as log:
                with socket.socket() as reserved:
                    reserved.bind(("127.0.0.1", 0))
                    port = reserved.getsockname()[1]
                base = f"http://127.0.0.1:{port}"

                def call(path, expected=200, auth=None, body=None, key=None):
                    headers = {"Content-Type": "application/json"}
                    if auth:
                        headers["Authorization"] = "Bearer " + auth
                    if key:
                        headers["Idempotency-Key"] = key
                    request = urllib.request.Request(base + path, headers=headers,
                        data=None if body is None else json.dumps(body).encode())
                    try:
                        response = client.open(request, timeout=8)
                    except urllib.error.HTTPError as exc:
                        response = exc
                    with response:
                        require(response.status == expected, f"Expected {expected}, received {response.status}")
                        if expected == 503:
                            require(response.headers.get("Cache-Control") == "no-store", "Missing no-store")
                            require(response.headers.get("WWW-Authenticate") is None and
                                    response.headers.get("Retry-After") is None, "Unexpected retry/auth challenge")
                        raw = response.read()
                        return json.loads(raw) if raw else None

                command = ["java", f"-Djavax.net.ssl.trustStore={truststore}",
                           "-Djavax.net.ssl.trustStoreType=PKCS12", "-Djavax.net.ssl.trustStorePassword=disposable-trust",
                           "-jar", str(JAR), f"--server.port={port}", "--server.address=127.0.0.1",
                           "--spring.profiles.active=" + ("postgres,secured" if args.database == "postgres" else "secured"),
                           "--recon.security.public-key=", "--recon.security.jwk-set-uri=" + uri,
                           "--recon.security.issuer=" + ISSUER, "--recon.security.audience=" + AUDIENCE]
                if certificate == "valid":
                    command += ["--recon.security.jwks-cache-ttl-seconds=5"]
                if args.database == "h2":
                    command += ["--spring.datasource.url=jdbc:h2:file:./.local/reconledger;DB_CLOSE_ON_EXIT=FALSE",
                                "--spring.datasource.username=sa", "--spring.datasource.password="]
                process = subprocess.Popen(command, cwd=directory, env=env, stdout=log, stderr=subprocess.STDOUT)
                try:
                    deadline = time.monotonic() + 45
                    while True:
                        require(process.poll() is None, "Packaged application exited during startup")
                        try:
                            require(call("/actuator/health") == {"status": "UP"}, "Unexpected health")
                            break
                        except (OSError, urllib.error.URLError):
                            require(time.monotonic() < deadline, "Application readiness timeout")
                            time.sleep(.1)
                    call("/api/batches", 401)
                    if certificate != "valid":
                        call("/api/batches", 503, auth_a, payload, certificate + run_id)
                        require(state["requests"] == 0, "Rejected certificate reached HTTP handler")
                    else:
                        # The same tenant/key previously rejected by TLS must create, never replay.
                        batches = [call("/api/batches", 201, auth_a, payload, case + run_id)
                                   for case in ("untrusted", "wrong-host")]
                        require(state["requests"] >= 1, "No successful HTTPS key retrieval")
                        read_only = token(first, "key-a", tenant, expiry, "recon:read")
                        call("/api/batches", 403, read_only, payload, "read-only-" + run_id)
                        result = next(r for r in batches[0]["results"] if r["reference"] == "INV-101")
                        result_path = "/api/results/" + result["id"]
                        review = {"targetState": "IN_REVIEW", "expectedVersion": 0, "note": "Synthetic HTTPS check."}
                        require(call(result_path + "/reviews", auth=auth_a, body=review)["version"] == 1,
                                "Review did not persist")
                        events = call(result_path + "/events", auth=auth_a)["events"]
                        require(len(events) == 1 and events[0]["actorSource"] == "VERIFIED_JWT" and
                                events[0]["actorIssuer"] == ISSUER, "Review provenance missing")
                        before = state["requests"]
                        state["keys"] = [jwk_b]
                        require(call(result_path, auth=auth_b)["version"] == 1, "Rotated key failed")
                        require(state["requests"] > before, "Unknown kid did not refetch over HTTPS")
                        require(time.time() < expiry - 60, "Old token expired before key-removal check")
                        call(result_path + "/reviews", 401, auth_a,
                             {**review, "targetState": "RESOLVED", "expectedVersion": 1})
                        require(call(result_path + "/events", auth=auth_b)["events"] == events,
                                "Rejected removed-key review changed audit")
                        replay = call("/api/batches", auth=auth_b, body=payload, key="untrusted" + run_id)
                        require(replay["id"] == batches[0]["id"], "Rotation changed idempotency ownership")
                        # Expiry of a known key must refresh even without a new kid.
                        state["keys"] = [jwk_a]
                        time.sleep(5.1)  # Actual configured TTL, not a simulated clock.
                        expired_key = "expiry-" + run_id
                        before_expiry_fetch = state["requests"]
                        call("/api/batches", 401, auth_b, payload, expired_key)
                        require(state["requests"] > before_expiry_fetch, "Expired known key did not refresh")
                        require(time.time() < expiry - 60, "Token expired before cache-expiry check")
                        require(call(result_path + "/events", auth=auth_a)["events"] == events,
                                "Key expiry changed review history")
                        # Removing a signing key invalidates every token signed by it,
                        # not an individual user. Recovery must leave rejected keys free.
                        call("/api/batches", 201, auth_a, payload, expired_key)
                        state["status"] = 503
                        warm_fetches = state["requests"]
                        call(result_path, auth=auth_a)
                        require(state["requests"] == warm_fetches, "Fresh known key unexpectedly fetched")
                        time.sleep(5.1)
                        unavailable_key = "expired-outage-" + run_id
                        call("/api/batches", 503, auth_a, payload, unavailable_key)
                        call(result_path + "/reviews", 503, auth_a,
                             {**review, "targetState": "RESOLVED", "expectedVersion": 1})
                        state["status"] = 200
                        call("/api/batches", 201, auth_a, payload, unavailable_key)
                        require(call(result_path + "/events", auth=auth_a)["events"] == events,
                                "Expired-cache outage mutated audit")
                        require(call(result_path, auth=auth_a)["version"] == 1,
                                "Expired-cache outage mutated review version")
                    observations.append({"certificate": certificate, "http_jwks_requests": state["requests"],
                                         "expected_auth_status": 200 if certificate == "valid" else 503})
                finally:
                    process.terminate()
                    try:
                        process.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=5)
                        raise RuntimeError("Application required forced shutdown")
                # Never persist application logs, tokens, private keys or the synthetic database.
        report.write_text(json.dumps({"schema_version": 1, "database": args.database,
            "valid_phase_cache_ttl_seconds": 5,
            "jar_sha256": hashlib.sha256(JAR.read_bytes()).hexdigest(),
            "python": __import__("platform").python_version(), "openssl": ssl.OPENSSL_VERSION,
            "java": subprocess.run(["java", "-version"], capture_output=True, text=True, check=True).stderr.splitlines()[0],
            "observations": observations,
            "checks": ["untrusted chain rejected before HTTP", "trusted chain wrong host rejected before HTTP",
                       "explicit JVM truststore accepts matching host", "TLS-rejected keys later create new batches",
                       "scope rejection", "verified review provenance", "unknown kid rotation without JAR restart",
                       "removed unexpired key rejected without audit mutation", "idempotency retained after rotation",
                       "known key expires without unknown-kid trigger", "expired-cache outage rejects writes",
                       "expiry/outage recovery preserves audit and unreserved idempotency keys"],
            "limits": "Local synthetic HTTPS issuer only; no external IdP membership, discovery, revocation or deployment proof."
        }, indent=2) + "\n")
    print(f"Packaged HTTPS JWKS checks passed ({args.database}); receipt: {report.name}")


if __name__ == "__main__":
    main()
