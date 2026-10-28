import { test, expect } from "@playwright/test";
import { readFileSync } from "node:fs";
import { randomUUID } from "node:crypto";
import { fixtureDate } from "../projects.js";

const input = JSON.parse(readFileSync(new URL("../../examples/mixed-batch.json", import.meta.url)));
const tokens = () => JSON.parse(readFileSync(new URL("../../.local/browser-auth.json", import.meta.url)));
const headers = (value) => ({ Authorization: `Bearer ${value}` });

async function create(request, token, date) {
  const response = await request.post("/api/batches", {
    headers: { ...headers(token), "Idempotency-Key": "secured-browser-" + randomUUID() },
    data: { ...input, businessDate: date },
  });
  expect(response.status()).toBe(201);
  return response.json();
}
async function connect(page, token) {
  await page.goto("/review/index.html");
  await expect(page.locator("#connection")).toBeVisible();
  await page.locator("#access-token").evaluate((input, value) => { input.value = value; }, token);
  await page.getByRole("button", { name: "Connect", exact: true }).click();
  await expect(page.getByRole("button", { name: "Refresh batches" })).toBeEnabled();
  await expect(page.locator("#access-token")).toHaveValue("");
}
async function select(page, batch) {
  await page.getByLabel("Business date", { exact: true }).fill(batch.businessDate);
  await page.getByRole("button", { name: "Refresh batches" }).click();
  await expect(page.locator("#status")).toContainText(`Date: ${batch.businessDate}`);
  await page.locator("#batches button").first().click();
  const result = batch.results.find((item) => item.reference === "INV-101");
  await page.getByRole("combobox", { name: "Reference", exact: true }).selectOption(result.id);
  await expect(page.locator("#status")).toHaveText("Evidence and review history loaded.");
  return result;
}
async function emptyStorage(page) {
  expect(await page.evaluate(() => [localStorage.length, sessionStorage.length, document.cookie])).toEqual([0, 0, ""]);
}

async function rejectWithCredential(page, path, method, token, status) {
  const observed = [];
  await page.route((url) => url.pathname === path, async (route) => {
    expect(route.request().method()).toBe(method);
    const forwarded = { ...await route.request().allHeaders(), authorization: `Bearer ${token}` };
    expect(Object.keys(forwarded).filter((name) => name.toLowerCase() === "authorization")).toHaveLength(1);
    // Check the real decoder/scope response before delivering it to the page.
    let response;
    try {
      response = await route.fetch({ headers: forwarded, maxRedirects: 0, maxRetries: 0 });
    } catch {
      // Transport errors may include request headers; never attach that diagnostic.
      throw new Error("Credential-forwarding request failed before receiving a response.");
    }
    observed.push(response.status());
    expect(response.status()).toBe(status);
    await route.fulfill({ response });
  }, { times: 1 });
  return observed;
}

test("verified reviewer saves with tenant isolation and clears credentials on disconnect/reload", async ({ page, request, browser }, info) => {
  console.log(`${info.project.use.browserName} ${browser.version()} / ${info.project.name}`);
  const t = tokens(), date = fixtureDate(info, "2041", "01");
  const alpha = await create(request, t.alpha, date), beta = await create(request, t.beta, date);
  for (const path of ["/review/index.html", "/review/review.js", "/review/review.css"])
    expect((await request.get(path)).status()).toBe(200);
  expect((await request.post("/review/index.html", { headers: headers(t.alpha) })).status()).toBe(403);
  expect((await request.get("/review/unknown", { headers: headers(t.alpha) })).status()).toBe(403);
  await connect(page, t.alpha);
  await expect(page.locator("#identity")).toContainText("Tenant browser-alpha");
  const result = await select(page, alpha);
  await expect(page.locator("#batches button")).toHaveCount(1);
  await expect(page.getByLabel("Reviewer name")).toBeHidden();
  const writes = [];
  page.on("request", (request) => { if (request.method() === "POST") writes.push(request.postDataJSON()); });
  await page.getByLabel("Review note", { exact: true }).fill("Checked synthetic evidence as a verified reviewer.");
  await page.getByRole("button", { name: "Save review" }).click();
  await expect(page.locator("#status")).toContainText("Review saved at version 1");
  expect(writes).toHaveLength(1);
  expect(writes[0]).not.toHaveProperty("actor");
  await expect(page.locator("#events")).toContainText("Verified token subject");
  const events = await (await request.get(`/api/results/${result.id}/events`, { headers: headers(t.alpha) })).json();
  expect(events.events[0].actor).toBe("synthetic-reviewer");
  expect(events.events[0].actorSource).toBe("VERIFIED_JWT");
  expect((await request.get(`/api/batches/${alpha.id}`, { headers: headers(t.beta) })).status()).toBe(404);
  await emptyStorage(page);
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth - innerWidth)).toBeLessThanOrEqual(1);
  await info.attach("secured-review", { body: await page.screenshot({ fullPage: true }), contentType: "image/png" });
  await page.getByRole("button", { name: "Disconnect and clear workspace" }).click();
  await expect(page.locator("#connection")).toBeVisible();
  await expect(page.locator("#batches button")).toHaveCount(0);
  await expect(page.locator("#left-evidence")).toBeEmpty();
  await expect(page.locator("#events")).toBeEmpty();
  await connect(page, t.beta);
  await select(page, beta);
  await expect(page.locator("#identity")).toContainText("Tenant browser-beta");
  await expect(page.locator("#events li")).toHaveCount(0);
  let releaseRead, readStarted;
  const held = new Promise((resolve) => { releaseRead = resolve; });
  const started = new Promise((resolve) => { readStarted = resolve; });
  await page.route("**/api/batches?*", async (route) => {
    const response = await route.fetch();
    readStarted();
    await held;
    await route.fulfill({ response });
  }, { times: 1 });
  await page.getByRole("button", { name: "Refresh batches" }).click();
  await started;
  await expect(page.getByRole("button", { name: "Disconnect and clear workspace" })).toBeDisabled();
  // Controlled lifecycle event tests a late response, independent of browser BFCache eligibility.
  await page.evaluate(() => window.dispatchEvent(new PageTransitionEvent("pagehide")));
  releaseRead();
  await expect(page.locator("#error")).toContainText("Workspace disconnected");
  await expect(page.locator("#batches button")).toHaveCount(0);
  await expect(page.locator("#identity")).toBeEmpty();
  await page.reload();
  await expect(page.locator("#connection")).toBeVisible();
  await expect(page.locator("#result")).toBeHidden();
  await emptyStorage(page);
});

test("read-only permission and rejected credentials never enable a review", async ({ page, request }, info) => {
  const t = tokens(), batch = await create(request, t.alpha, fixtureDate(info, "2041", "02"));
  for (const value of [t.expired, t.writer, "invalid-token"]) {
    await page.goto("/review/index.html");
    await expect(page.locator("#connection")).toBeVisible();
    await page.locator("#access-token").evaluate((input, token) => { input.value = token; }, value);
    await page.getByRole("button", { name: "Connect", exact: true }).click();
    await expect(page.locator("#error")).toBeVisible();
    await expect(page.locator("#access-token")).toHaveValue("");
    await expect(page.getByRole("button", { name: "Refresh batches" })).toBeDisabled();
    await emptyStorage(page);
  }
  await connect(page, t.reader);
  const result = await select(page, batch);
  await expect(page.getByRole("button", { name: "Save review" })).toBeDisabled();
  await expect(page.locator("#review-block")).toContainText("read access only");
  expect((await request.post(`/api/results/${result.id}/reviews`, { headers: headers(t.reader),
    data: { targetState: "IN_REVIEW", expectedVersion: 0, note: "Must not commit." } })).status()).toBe(403);
  expect((await (await request.get(`/api/results/${result.id}/events`, { headers: headers(t.reader) })).json()).events).toHaveLength(0);
});

test("access rejection clears a draft and an uncertain save requires inspection after reconnect", async ({ page, request }, info) => {
  const t = tokens(), batch = await create(request, t.alpha, fixtureDate(info, "2041", "03"));
  await connect(page, t.alpha);
  const result = await select(page, batch);
  let writes = 0;
  await page.route(`**/api/results/${result.id}/reviews`, async (route) => {
    writes++;
    const response = await route.fetch();
    expect(response.status()).toBe(200);
    await route.abort("failed");
  }, { times: 1 });
  await page.getByLabel("Review note", { exact: true }).fill("Response will be lost after this commit.");
  await page.getByRole("button", { name: "Save review" }).click();
  await expect(page.locator("#review-block")).toContainText("Save was not confirmed");
  expect(writes).toBe(1);
  await expect(page.getByRole("button", { name: "Reload result and history" })).toBeEnabled();
  const resultPath = `/api/results/${result.id}`;
  const rejections = await rejectWithCredential(page, resultPath, "GET", t.expired, 401);
  await page.getByRole("button", { name: "Reload result and history" }).click();
  // The route helper already asserts the exact forwarded request's server status.
  // A page-wide response waiter can also observe the route.fetch exchange and is
  // therefore not an identity for the response delivered to the application.
  await expect(page.locator("#connection")).toBeVisible();
  await expect(page.locator("#note")).toHaveValue("");
  await expect(page.locator("#events")).toBeEmpty();
  await expect(page.locator("#left-evidence")).toBeEmpty();
  await expect(page.locator("#notice")).toContainText("Access expired or was rejected");
  expect(rejections).toEqual([401]);
  await connect(page, t.alpha);
  await select(page, batch);
  await expect(page.locator("#result-state")).toContainText("Version 1");
  await expect(page.locator("#events li")).toHaveCount(1);
  expect(writes).toBe(1);
});

test("rejected review saves preserve or clear drafts without committing or retrying", async ({ page, request }, info) => {
  const t = tokens(), batch = await create(request, t.alpha, fixtureDate(info, "2041", "04"));
  await connect(page, t.alpha);
  const result = await select(page, batch);
  const resultPath = `/api/results/${result.id}`, reviewPath = `${resultPath}/reviews`;
  let writes = 0;
  page.on("request", (outgoing) => {
    if (outgoing.method() === "POST" && new URL(outgoing.url()).pathname === reviewPath) writes++;
  });
  const unchanged = async () => {
    const current = await (await request.get(resultPath, { headers: headers(t.alpha) })).json();
    expect(current.version).toBe(0);
    expect(current.reviewState).toBe("OPEN");
    expect((await (await request.get(`${resultPath}/events`, { headers: headers(t.alpha) })).json()).events).toHaveLength(0);
  };
  const denied = await rejectWithCredential(page, reviewPath, "POST", t.reader, 403);
  const draft = "Permission changed after the workspace connected.";
  await page.getByLabel("Review note", { exact: true }).fill(draft);
  await page.getByRole("button", { name: "Save review" }).click();
  await expect(page.locator("#review-block")).toContainText("Review rejected:");
  await expect(page.locator("#note")).toHaveValue(draft);
  await expect(page.getByRole("button", { name: "Save review" })).toBeDisabled();
  await expect(page.locator("#identity")).toContainText("browser-alpha");
  expect(denied).toEqual([403]);
  expect(writes).toBe(1);
  await unchanged();
  await info.attach("scope-rejected-draft", { body: await page.screenshot({ fullPage: true }), contentType: "image/png" });

  await page.getByRole("button", { name: "Reload result and history" }).click();
  await expect(page.getByRole("button", { name: "Save review" })).toBeEnabled();
  await expect(page.locator("#note")).toHaveValue(draft);
  const expired = await rejectWithCredential(page, reviewPath, "POST", t.expired, 401);
  await page.getByRole("button", { name: "Save review" }).click();
  await expect(page.locator("#connection")).toBeVisible();
  await expect(page.locator("#note")).toHaveValue("");
  await expect(page.locator("#identity")).toBeEmpty();
  await expect(page.locator("#left-evidence")).toBeEmpty();
  await expect(page.locator("#events")).toBeEmpty();
  await expect(page.locator("#notice")).toContainText("Access expired or was rejected");
  expect(expired).toEqual([401]);
  expect(writes).toBe(2);
  await unchanged();
  await emptyStorage(page);
  await info.attach("expired-save-cleared", { body: await page.screenshot({ fullPage: true }), contentType: "image/png" });

  await connect(page, t.alpha);
  await select(page, batch);
  await expect(page.locator("#result-state")).toContainText("Version 0");
  await page.getByLabel("Review note", { exact: true }).fill("Reconnected and inspected unchanged history before deciding.");
  await page.getByRole("button", { name: "Save review" }).click();
  await expect(page.locator("#status")).toContainText("Review saved at version 1");
  await expect(page.locator("#events li")).toHaveCount(1);
  expect(writes).toBe(3);
});
