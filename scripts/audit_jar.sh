#!/usr/bin/env bash
set -euo pipefail
jar=$(realpath "${1:?Supply the executable Spring Boot JAR}")
output=${2:?Supply a new output directory}
mkdir -p "$(dirname "$output")"
mkdir "$output"
output=$(cd "$output" && pwd)
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
mkdir "$work/input" "$work/cache"
cp "$jar" "$work/input/application.jar"
scanner=aquasec/trivy:0.75.0
docker pull "$scanner"
docker image inspect "$scanner" --format '{{json .RepoDigests}}' > "$output/scanner-digests.json"
scan() {
  docker run --rm --user "$(id -u):$(id -g)" --cap-drop ALL \
    --security-opt no-new-privileges \
    -v "$work/input:/input:ro" -v "$work/cache:/cache" -v "$output:/output" \
    "$scanner" --cache-dir /cache "$@"
}
# One complete report is both the retained evidence and the input to the gate.
scan rootfs --disable-telemetry --scanners vuln --pkg-types library \
  --ignorefile /dev/null --list-all-pkgs --format json --exit-code 0 \
  --output /output/trivy.json /input
scan version --format json > "$output/scanner-version.json"
python3 scripts/check_jar_audit.py "$work/input/application.jar" \
  "$output/trivy.json" "$output/inventory.json"
