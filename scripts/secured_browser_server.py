#!/usr/bin/env python3
"""Serve the opt-in secured UI with disposable local trust and an H2 database."""
import json
import os
from pathlib import Path
import signal
import subprocess
import tempfile
import time

from secured_smoke import AUDIENCE, ISSUER, JAR, key_pair, token

ROOT = Path(__file__).resolve().parents[1]
RECEIPT = ROOT / '.local' / 'browser-auth.json'

if __name__ == '__main__':
    if not JAR.is_file():
        raise SystemExit('Package the application first.')
    RECEIPT.parent.mkdir(mode=0o700, exist_ok=True)
    RECEIPT.unlink(missing_ok=True)
    env = {key: value for key, value in os.environ.items()
           if not key.startswith(('SPRING_', 'RECON_'))
           and key not in {'JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'}}
    with tempfile.TemporaryDirectory(prefix='recon-secured-browser-') as directory:
        directory = Path(directory)
        private, public = key_pair(directory, 'browser')
        pem = directory / 'public.pem'
        pem.write_bytes(public)
        now = int(time.time())
        tokens = {
            'alpha': token(private, 'browser-alpha', now + 3600),
            'beta': token(private, 'browser-beta', now + 3600),
            'reader': token(private, 'browser-alpha', now + 3600, 'recon:read'),
            'writer': token(private, 'browser-alpha', now + 3600, 'recon:write'),
            'expired': token(private, 'browser-alpha', now - 600),
        }
        # Test input only; never attach this file or token-bearing network traces.
        descriptor = os.open(RECEIPT, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        with os.fdopen(descriptor, 'w') as output:
            json.dump(tokens, output)
        process = None
        try:
            process = subprocess.Popen(['java', '-jar', str(JAR),
                '--server.address=127.0.0.1', '--server.port=8802',
                '--spring.profiles.active=secured',
                '--recon.security.review-ui-enabled=true',
                '--recon.security.public-key=' + pem.as_uri(),
                '--recon.security.issuer=' + ISSUER,
                '--recon.security.audience=' + AUDIENCE,
                '--spring.datasource.url=jdbc:h2:mem:secured-browser;DB_CLOSE_DELAY=-1',
                '--spring.datasource.username=sa', '--spring.datasource.password='],
                cwd=directory, env=env)
            def stop(signum, frame):
                process.terminate()
            signal.signal(signal.SIGTERM, stop)
            signal.signal(signal.SIGINT, stop)
            raise SystemExit(process.wait())
        finally:
            RECEIPT.unlink(missing_ok=True)
            if process is not None and process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=8)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=2)
