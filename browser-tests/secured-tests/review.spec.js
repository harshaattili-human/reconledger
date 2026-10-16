import { test, expect } from "@playwright/test";
import { readFileSync } from "node:fs";
import { randomUUID } from "node:crypto";

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

test("verified reviewer saves with tenant isolation and clears credentials on disconnect/reload", async ({ page, request }, info) => {
  const t = tokens(), date = `2041-01-${info.project.name === "desktop" ? "15" : "16"}`;
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
  await page.reload();
  await expect(page.locator("#connection")).toBeVisible();
  await expect(page.locator("#result")).toBeHidden();
  await emptyStorage(page);
});

test("read-only permission and rejected credentials never enable a review", async ({ page, request }, info) => {
  const t = tokens(), batch = await create(request, t.alpha, `2041-02-${info.project.name === "desktop" ? "15" : "16"}`);
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
  const t = tokens(), batch = await create(request, t.alpha, `2041-03-${info.project.name === "desktop" ? "15" : "16"}`);
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
  // A real expired credential is substituted at the transport boundary after connection.
  await page.route("**/api/results/*", async (route) => {
    await route.continue({ headers: { ...route.request().headers(), ...headers(t.expired) } });
  }, { times: 1 });
  await page.getByRole("button", { name: "Reload result and history" }).click();
  await expect(page.locator("#connection")).toBeVisible();
  await expect(page.locator("#note")).toHaveValue("");
  await expect(page.locator("#events")).toBeEmpty();
  await expect(page.locator("#left-evidence")).toBeEmpty();
  await expect(page.locator("#notice")).toContainText("Access expired or was rejected");
  await connect(page, t.alpha);
  await select(page, batch);
  await expect(page.locator("#result-state")).toContainText("Version 1");
  await expect(page.locator("#events li")).toHaveCount(1);
  expect(writes).toBe(1);
});
