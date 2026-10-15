#!/usr/bin/env python3
"""Run the packaged demo on an isolated in-memory database for browser checks."""
import os
from pathlib import Path
import signal
import subprocess
import tempfile

JAR = Path(__file__).resolve().parents[1] / "target/reconledger-0.1.0-SNAPSHOT-app.jar"

if __name__ == "__main__":
    if not JAR.is_file():
        raise SystemExit("Package the application with ./mvnw verify first.")
    env = {key: value for key, value in os.environ.items()
           if not key.startswith(("SPRING_", "RECON_"))
           and key not in {"JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"}}
    with tempfile.TemporaryDirectory(prefix="reconledger-browser-") as directory:
        process = subprocess.Popen(["java", "-jar", str(JAR),
            "--server.address=127.0.0.1", "--server.port=8801", "--spring.profiles.active=default",
            "--spring.datasource.url=jdbc:h2:mem:browser;DB_CLOSE_DELAY=-1",
            "--spring.datasource.username=sa", "--spring.datasource.password="], cwd=directory, env=env)
        def stop(signum, frame):
            process.terminate()
        signal.signal(signal.SIGTERM, stop)
        signal.signal(signal.SIGINT, stop)
        try:
            raise SystemExit(process.wait())
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=8)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=2)
