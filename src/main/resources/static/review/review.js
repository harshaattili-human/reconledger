const $ = (id) => document.getElementById(id);
const labels = {
  MATCHED: "Matched",
  AMOUNT_MISMATCH: "Amount mismatch",
  MISSING_LEFT: "Missing left",
  MISSING_RIGHT: "Missing right",
  DUPLICATE_REFERENCE: "Duplicate reference",
  NOT_REQUIRED: "Not required",
  OPEN: "Open",
  IN_REVIEW: "In review",
  RESOLVED: "Resolved",
};
const transitions = {
  OPEN: ["IN_REVIEW"],
  IN_REVIEW: ["RESOLVED", "OPEN"],
  RESOLVED: ["IN_REVIEW"],
  NOT_REQUIRED: [],
};
let batch,
  result,
  batchCursor = null,
  eventCursor = null,
  filters = {},
  blocked = false,
  busy = false,
  accessToken = null,
  session = null,
  connectionEpoch = 0;

// Preserve the wire tokens: cursors are signed 64-bit integers and amounts are decimals.
export function parseLedger(text) {
  return JSON.parse(text, (key, value, context) => {
    if (
      typeof value === "number" &&
      [
        "amount",
        "sequence",
        "nextBeforeSequence",
        "nextAfterSequence",
      ].includes(key)
    ) {
      if (!context?.source)
        throw new Error(
          "This browser cannot preserve exact ledger values. Use a current browser.",
        );
      return context.source;
    }
    return value;
  });
}

function element(tag, text, className) {
  const node = document.createElement(tag);
  if (text !== undefined) node.textContent = text;
  if (className) node.className = className;
  return node;
}

async function request(path, body) {
  const epoch = connectionEpoch;
  const response = await fetch(path, {
    credentials: "omit",
    cache: "no-store",
    signal: AbortSignal.timeout(15000),
    redirect: "error",
    headers: {
      ...(accessToken ? { Authorization: `Bearer ${accessToken}` } : {}),
      ...(body ? { "Content-Type": "application/json" } : {}),
    },
    ...(body
      ? {
          method: "POST",
          body: JSON.stringify(body),
        }
      : {}),
  });
  const text = await response.text();
  if (epoch !== connectionEpoch) throw new Error("Workspace disconnected while the request was pending.");
  if (!response.ok) {
    if (response.status === 401) {
      disconnect("Access expired or was rejected. Reconnect, then inspect the result and history before any new review.");
    }
    let detail = `Request failed (${response.status}).`;
    try {
      detail = JSON.parse(text).detail || detail;
    } catch {
      /* Security errors may have no JSON body. */
    }
    throw Object.assign(new Error(detail), { status: response.status });
  }
  return parseLedger(text);
}

function controls() {
  $("controls").disabled = busy || !session;
  $("connect").disabled = busy;
  $("disconnect").disabled = busy;
  $("workspace").setAttribute("aria-busy", String(busy));
  $("older-batches").disabled = !batchCursor;
  $("next-events").disabled = !eventCursor;
  $("review-fields").disabled =
    busy || !session?.canReview || blocked || !result || !transitions[result.reviewState]?.length;
}

async function run(action) {
  if (busy) return;
  busy = true;
  $("error").hidden = true;
  controls();
  try {
    await action();
  } catch (error) {
    $("error").textContent = error.message;
    $("error").hidden = false;
    $("status").textContent = "Request did not complete.";
  } finally {
    busy = false,
  accessToken = null,
  session = null;
    controls();
  }
}

function clearResult() {
  result = null;
  eventCursor = null;
  blocked = false;
  $("result").hidden =
    $("review-panel").hidden =
    $("history-panel").hidden =
      true;
  $("note").value = "";
  $("events").replaceChildren();
  $("empty-events").hidden = true;
  $("review-block").hidden = true;
}

async function loadBatches(cursor = null, requestedFilters = filters) {
  const query = new URLSearchParams({ limit: "10", ...requestedFilters });
  if (cursor) query.set("beforeSequence", cursor);
  const page = await request("/api/batches?" + query);
  filters = requestedFilters;
  batchCursor = page.nextBeforeSequence;
  $("batches").replaceChildren();
  for (const item of page.batches) {
    const button = element("button");
    button.type = "button";
    button.append(
      element("span", `${item.businessDate} · ${item.currency}`),
      element("small", `Batch ${item.sequence}`),
    );
    button.setAttribute("aria-current", String(batch?.id === item.id));
    button.addEventListener("click", () =>
      run(async () => {
        await loadBatch(item.id);
        document
          .querySelectorAll("#batches button")
          .forEach((node) =>
            node.setAttribute("aria-current", String(node === button)),
          );
      }),
    );
    const li = element("li");
    li.append(button);
    $("batches").append(li);
  }
  $("empty-batches").hidden = page.batches.length > 0;
  $("status").textContent =
    `${page.batches.length} batches on this page. Date: ${filters.businessDate || "any"}. Currency: ${filters.currency || "any"}.`;
}

async function loadBatch(id) {
  const next = await request("/api/batches/" + encodeURIComponent(id));
  clearResult();
  batch = next;
  $("batch-title").textContent = `${batch.businessDate} · ${batch.currency}`;
  $("batch-meta").textContent =
    `Batch ${batch.id} · ${batch.results.length} references`;
  $("result-picker").hidden = false;
  $("results").replaceChildren(new Option("Choose a reference", ""));
  for (const item of batch.results)
    $("results").add(
      new Option(
        `${item.reference} — ${labels[item.outcome]} · ${labels[item.reviewState]}`,
        item.id,
      ),
    );
  $("status").textContent =
    "Batch loaded. Choose a reference to compare its records.";
  $("batch-title").focus();
}

function evidence(side, records) {
  const target = $(side + "-evidence");
  target.replaceChildren();
  const table = element("table");
  table.append(
    element(
      "caption",
      `${side === "left" ? "Left" : "Right"} source · ${records.length} ${records.length === 1 ? "record" : "records"}`,
    ),
  );
  const head = element("thead"),
    row = element("tr");
  for (const text of ["Record ID", `Amount (${batch.currency})`]) {
    const th = element("th", text);
    th.scope = "col";
    row.append(th);
  }
  head.append(row);
  table.append(head);
  const body = element("tbody");
  for (const record of records) {
    const tr = element("tr");
    tr.append(element("td", record.recordId), element("td", record.amount));
    body.append(tr);
  }
  table.append(body);
  target.append(table);
  if (!records.length)
    target.append(element("p", "No record on this side.", "hint"));
}

function renderResult() {
  $("result").hidden =
    $("review-panel").hidden =
    $("history-panel").hidden =
      false;
  $("result-title").textContent = result.reference;
  $("result-state").textContent =
    `${labels[result.outcome]} · ${labels[result.reviewState]} · Version ${result.version}`;
  $("result-explanation").textContent =
    result.outcome === "DUPLICATE_REFERENCE"
      ? "Duplicate references stay exceptions even when totals agree. The records are not silently paired or combined."
      : "Original amounts are shown exactly. Reviews do not change source records.";
  evidence("left", result.leftRecords);
  evidence("right", result.rightRecords);
  $("current-note").textContent = result.resolutionNote
    ? `Current review note: ${result.resolutionNote}`
    : "No review note recorded.";
  $("target-state").replaceChildren();
  for (const state of transitions[result.reviewState])
    $("target-state").add(new Option(labels[state], state));
  $("review-block").hidden = !blocked && result.reviewState !== "NOT_REQUIRED";
  if (!session?.canReview) {
    $("review-block").hidden = false;
    $("review-block").textContent = "This token has read access only. A review requires recon:review permission.";
  }
  if (result.reviewState === "NOT_REQUIRED")
    $("review-block").textContent =
      "Matched references do not accept review transitions.";
  const option = Array.from($("results").options).find(
    (item) => item.value === result.id,
  );
  if (option)
    option.textContent = `${result.reference} — ${labels[result.outcome]} · ${labels[result.reviewState]}`;
}

async function loadEvents(cursor = null) {
  const page = await request(
    `/api/results/${encodeURIComponent(result.id)}/events?limit=10&afterSequence=${cursor || "0"}`,
  );
  eventCursor = page.nextAfterSequence;
  $("events").replaceChildren();
  for (const event of page.events) {
    const li = element("li");
    const provenance =
      event.actorSource === "DEMO_LABEL"
        ? "Self-reported name"
        : event.actorSource === "VERIFIED_JWT" &&
            typeof event.actorIssuer === "string" &&
            event.actorIssuer.trim()
          ? `Verified token subject · Issuer ${event.actorIssuer}`
          : "Identity provenance unknown";
    li.append(
      element(
        "p",
        `${labels[event.fromState]} → ${labels[event.toState]} · Version ${event.resultingVersion}`,
      ),
      element(
        "p",
        `${event.actor} · ${event.createdAt} · Event ${event.sequence}`,
        "event-meta",
      ),
      element("p", provenance, "event-meta"),
      element("p", event.note),
    );
    $("events").append(li);
  }
  $("empty-events").hidden = page.events.length > 0;
}

async function selectResult(id) {
  clearResult();
  if (!id) return;
  result = await request("/api/results/" + encodeURIComponent(id));
  requireReload("Loading review history before enabling decisions.");
  renderResult();
  await loadEvents();
  blocked = false;
  renderResult();
  $("status").textContent = "Evidence and review history loaded.";
  $("result-title").focus();
}

function requireReload(message) {
  blocked = true;
  $("review-block").textContent = message;
  $("review-block").hidden = false;
  $("status").textContent = message;
}

$("filters").addEventListener("submit", (event) => {
  event.preventDefault();
  run(async () => {
    const next = {};
    if ($("business-date").value) next.businessDate = $("business-date").value;
    if ($("currency").value) next.currency = $("currency").value;
    await loadBatches(null, next);
  });
});
$("older-batches").addEventListener("click", () =>
  run(async () => {
    await loadBatches(batchCursor);
    $("browse-title").focus();
  }),
);
$("results").addEventListener("change", () =>
  run(() => selectResult($("results").value)),
);
$("next-events").addEventListener("click", () =>
  run(async () => {
    await loadEvents(eventCursor);
    $("history-title").focus();
  }),
);
$("reload-result").addEventListener("click", () =>
  run(async () => {
    // Keep the draft, but do not enable saving until both reads succeed.
    blocked = true;
    result = await request("/api/results/" + encodeURIComponent(result.id));
    renderResult();
    await loadEvents();
    blocked = false;
    renderResult();
    $("status").textContent =
      "Latest result loaded. Check its state and history before saving your retained draft.";
    $("result-title").focus();
  }),
);
$("review-form").addEventListener("submit", (event) => {
  event.preventDefault();
  if (blocked || !result || !session?.canReview) return;
  run(async () => {
    const input = {
      expectedVersion: result.version,
      targetState: $("target-state").value,
      ...(session.secured ? {} : { actor: $("actor").value }),
      note: $("note").value,
    };
    blocked = true;
    try {
      result = await request(
        "/api/results/" + encodeURIComponent(result.id) + "/reviews",
        input,
      );
    } catch (error) {
      if (!session) return;
      requireReload(
        error.status === 409
          ? "This result changed or the transition is no longer allowed. Your draft is retained. Reload result and history before deciding again."
          : error.status && error.status < 500
            ? `Review rejected: ${error.message} Your draft is retained. Reload result and history before another submission.`
            : "Save was not confirmed. Your draft is retained. Reload result and history to check whether it committed before submitting again.",
      );
      return;
    }
    $("note").value = "";
    renderResult();
    try {
      await loadEvents();
    } catch {
      if (!session) return;
      requireReload(
        "Review saved, but history could not load. Reload result and history before another review.",
      );
      return;
    }
    blocked = false;
    renderResult();
    $("status").textContent =
      `Review saved at version ${result.version}. Original evidence is unchanged.`;
    $("result-title").focus();
  });
});

function disconnect(message = "Disconnected. Workspace data and draft cleared.") {
  connectionEpoch++;
  accessToken = null;
  session = null;
  batch = null;
  batchCursor = null;
  filters = {};
  clearResult();
  $("error").textContent = $("review-block").textContent = "";
  $("error").hidden = true;
  for (const id of ["batches", "results", "left-evidence", "right-evidence"])
    $(id).replaceChildren();
  for (const id of ["batch-meta", "result-title", "result-state", "result-explanation", "current-note", "identity"])
    $(id).textContent = "";
  for (const id of ["actor", "access-token", "business-date", "currency"])
    $(id).value = "";
  $("batch-title").textContent = "Choose a batch";
  $("result-picker").hidden = $("identity").hidden = $("disconnect").hidden = true;
  $("empty-batches").hidden = true;
  $("connection").hidden = false;
  $("notice").textContent = message;
  $("status").textContent = message;
  $("mode").textContent = "Secured workspace · disconnected";
  controls();
}

async function connect() {
  session = await request("/api/review-session");
  $("connection").hidden = true;
  $("disconnect").hidden = $("identity").hidden = !session.secured;
  $("actor-label").hidden = session.secured;
  $("actor").required = !session.secured;
  $("mode").textContent = session.secured ? "Secured workspace · synthetic data" : "Local demo · synthetic data";
  $("notice").textContent = session.secured
    ? "Reviews use your verified token identity. Disconnect clears this page; it does not revoke the token."
    : "This workspace uses the unauthenticated demo profile. Reviewer names are self-reported. Use synthetic records only.";
  $("identity").textContent = session.secured
    ? `Tenant ${session.tenant} · Subject ${session.subject} · Issuer ${session.issuer}` : "";
  await loadBatches();
}

$("connect-form").addEventListener("submit", (event) => {
  event.preventDefault();
  if (busy) return;
  const candidate = $("access-token").value.trim();
  $("access-token").value = "";
  run(async () => {
    accessToken = candidate;
    try { await connect(); }
    catch (error) {
      disconnect("Connection failed. Check the token and its read permission.");
      throw error;
    }
  });
});
$("disconnect").addEventListener("click", () => {
  if (busy) return;
  disconnect();
  $("access-token").focus();
});
// Back/forward cache must not resurrect an authenticated workspace.
window.addEventListener("pagehide", () => disconnect());

run(async () => {
  parseLedger('{"sequence":9007199254740993}');
  try { await connect(); } catch (error) { if (error.status !== 401) throw error; }
});
