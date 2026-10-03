#!/usr/bin/env python3
"""Exercise a running local service with synthetic records; Python stdlib only."""
import argparse
import json
from pathlib import Path
import urllib.error
import urllib.request
import uuid


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:8081")
    parser.add_argument("--output", type=Path, help="Optional JSON receipt for later GET/restart checks")
    args = parser.parse_args()
    payload = json.loads((Path(__file__).resolve().parents[1] / "examples/mixed-batch.json").read_text())
    key = "demo-" + str(uuid.uuid4())
    # Demonstrations target a local service; do not route requests through a proxy.
    client = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def call(path, body=None, headers=None):
        request = urllib.request.Request(
            args.base_url.rstrip("/") + path,
            data=None if body is None else json.dumps(body).encode(),
            headers={"Content-Type": "application/json", **(headers or {})},
        )
        try:
            response = client.open(request, timeout=20)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            return response.status, dict(response.headers), json.load(response)

    status, _, batch = call("/api/batches", payload, {"Idempotency-Key": key})
    assert status == 201, (status, batch)
    assert batch["counts"] == {
        "MATCHED": 2, "AMOUNT_MISMATCH": 1, "MISSING_LEFT": 1,
        "MISSING_RIGHT": 1, "DUPLICATE_REFERENCE": 1,
    }, batch["counts"]
    print(f"Created batch {batch['id']} from 11 synthetic source records.")
    for result in batch["results"]:
        print(f"  {result['reference']:10} {result['outcome']}")

    status, headers, replay = call("/api/batches", payload, {"Idempotency-Key": key})
    assert status == 200 and replay["id"] == batch["id"], (status, replay)
    assert next(v for k, v in headers.items() if k.lower() == "idempotency-replayed") == "true"
    print("Same-key retry returned the existing batch.")

    changed = {**payload, "currency": "EUR"}
    status, _, conflict = call("/api/batches", changed, {"Idempotency-Key": key})
    assert status == 409, (status, conflict)
    print("Changed payload with the same key returned HTTP 409.")

    exception = next(r for r in batch["results"] if r["outcome"] == "AMOUNT_MISMATCH")
    path = f"/api/results/{exception['id']}"
    for version, state, note in [
        (0, "IN_REVIEW", "Comparing synthetic source records."),
        (1, "RESOLVED", "Demo investigation completed; discrepancy is documented, source amounts remain unchanged."),
    ]:
        status, _, review = call(path + "/reviews", {
            "targetState": state, "expectedVersion": version, "actor": "demo-reviewer", "note": note,
        })
        assert status == 200 and review["version"] == version + 1, (status, review)
    status, _, events = call(path + "/events")
    assert status == 200 and len(events) == 2, (status, events)
    print("Reviewed the amount mismatch; both transitions are recorded in audit history.")
    status, _, fetched = call("/api/batches/" + batch["id"])
    assert status == 200 and fetched["counts"] == batch["counts"]
    receipt = {"batchId": batch["id"], "resultId": exception["id"], "idempotencyKey": key,
               "counts": batch["counts"], "events": events}
    if args.output:
        args.output.write_text(json.dumps(receipt, indent=2) + "\n")
    print("Demo checks passed. Resolution tracks a review decision; it does not alter transaction records.")


if __name__ == "__main__":
    main()
