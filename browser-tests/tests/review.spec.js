import { test, expect } from "@playwright/test";
import { readFileSync } from "node:fs";
import { randomUUID } from "node:crypto";
import { fixtureDate } from "../projects.js";

const fixture = JSON.parse(
  readFileSync(new URL("../../examples/mixed-batch.json", import.meta.url)),
);
const dateFor = (info, month) => fixtureDate(info, "2040", month);

async function create(request, date) {
  const response = await request.post("/api/batches", {
    headers: { "Idempotency-Key": "browser-" + randomUUID() },
    data: { ...fixture, businessDate: date },
  });
  expect(response.status()).toBe(201);
  return response.json();
}

async function openBatch(page, date) {
  await page.goto("/review/index.html");
  await expect(
    page.getByRole("button", { name: "Refresh batches" }),
  ).toBeEnabled();
  await page.getByLabel("Business date", { exact: true }).fill(date);
  await page.getByLabel("Currency", { exact: true }).fill("USD");
  await page.getByRole("button", { name: "Refresh batches" }).click();
  await expect(page.locator("#status")).toContainText(
    `Date: ${date}. Currency: USD.`,
  );
  const button = page.locator("#batches button").first();
  await button.focus();
  await page.keyboard.press("Enter");
  await expect(page.locator("#batch-title")).toBeFocused();
}

async function reference(page, batch, name = "INV-101") {
  const result = batch.results.find((item) => item.reference === name);
  await page
    .getByRole("combobox", { name: "Reference", exact: true })
    .selectOption(result.id);
  await expect(page.locator("#result-title")).toHaveText(name);
  await expect(page.locator("#status")).toHaveText(
    "Evidence and review history loaded.",
  );
  return result;
}

async function draft(page, actor, note) {
  await page.getByLabel("Reviewer name").fill(actor);
  await page.getByLabel("Review note", { exact: true }).fill(note);
}

test.beforeEach(async ({ page }) => {
  page.on("pageerror", (error) => {
    throw error;
  });
});

test("keyboard review preserves evidence and displays notes as text", async ({
  page,
  request,
  browser,
}, info) => {
  console.log(`${info.project.use.browserName} ${browser.version()} / ${info.project.name}`);
  const date = dateFor(info, "01"),
    batch = await create(request, date);
  await openBatch(page, date);
  await reference(page, batch, "INV-104");
  await expect(
    page.getByText("Duplicate references stay exceptions", { exact: false }),
  ).toBeVisible();
  await expect(page.locator("#left-evidence tbody tr")).toHaveCount(2);
  await expect(page.locator("#right-evidence tbody")).toContainText("40.00");
  await reference(page, batch, "INV-100");
  await expect(
    page.getByRole("button", { name: "Save review" }),
  ).toBeDisabled();
  const original = await reference(page, batch);
  await expect(page.locator("#left-evidence tbody")).toContainText("80.00");
  await expect(page.locator("#right-evidence tbody")).toContainText("85.00");
  await page.getByLabel("Reviewer name").focus();
  await page.keyboard.type("demo-reviewer");
  await page.keyboard.press("Tab");
  await expect(
    page.getByRole("combobox", { name: "Next state", exact: true }),
  ).toBeFocused();
  await page.keyboard.press("Tab");
  await expect(page.getByLabel("Review note", { exact: true })).toBeFocused();
  const note =
    '<img src=x onerror="window.injected=true"> Checked the $5 discrepancy.';
  await page.keyboard.type(note);
  await page.keyboard.press("Tab");
  await expect(page.getByRole("button", { name: "Save review" })).toBeFocused();
  await page.keyboard.press("Enter");
  await expect(page.locator("#status")).toContainText(
    "Review saved at version 1.",
  );
  await expect(page.locator("#events")).toContainText(note);
  await expect(page.locator("#events")).toContainText("Self-reported name");
  await expect(page.locator("#events img")).toHaveCount(0);
  expect(await page.evaluate(() => window.injected)).toBeUndefined();
  await draft(
    page,
    "demo-reviewer",
    "Synthetic difference explained; source amounts retained.",
  );
  await page
    .getByRole("combobox", { name: "Next state", exact: true })
    .selectOption("RESOLVED");
  await page.getByRole("button", { name: "Save review" }).click();
  await expect(page.locator("#result-state")).toHaveText(
    "Amount mismatch · Resolved · Version 2",
  );
  const stored = await (
    await request.get("/api/results/" + original.id)
  ).json();
  expect(stored.leftRecords).toEqual(original.leftRecords);
  expect(stored.rightRecords).toEqual(original.rightRecords);
  expect(stored.outcome).toBe("AMOUNT_MISMATCH");
  await expect
    .poll(() =>
      page.evaluate(() => document.documentElement.scrollWidth - innerWidth),
    )
    .toBeLessThanOrEqual(1);
  await info.attach("review-evidence", {
    body: await page.screenshot({ fullPage: true }),
    contentType: "image/png",
  });
});

test("audit identity labels preserve unknown history and render issuers literally", async ({
  page,
  request,
}, info) => {
  const date = dateFor(info, "06"),
    batch = await create(request, date);
  const issuer = '<img src=x onerror="window.issuerInjected=true">';
  // Display fixture only: the demo cannot create verified JWT events through HTTP.
  await page.route("**/api/results/*/events?*", async (route) => {
    await route.fulfill({
      json: {
        events: [
          { actorSource: "LEGACY_UNKNOWN", actorIssuer: null },
          { actorSource: "DEMO_LABEL", actorIssuer: null },
          { actorSource: "VERIFIED_JWT", actorIssuer: issuer },
          {},
        ].map((identity, index) => ({
          sequence: index + 1,
          actor: "same-name",
          fromState: "OPEN",
          toState: "IN_REVIEW",
          resultingVersion: index + 1,
          note: "Synthetic display fixture",
          createdAt: "2040-01-01T00:00:00Z",
          ...identity,
        })),
        nextAfterSequence: null,
      },
    });
  });
  await openBatch(page, date);
  await reference(page, batch);
  const events = page.locator("#events li");
  await expect(events.nth(0)).toContainText("Identity provenance unknown");
  await expect(events.nth(1)).toContainText("Self-reported name");
  await expect(events.nth(2)).toContainText(
    `Verified token subject · Issuer ${issuer}`,
  );
  await expect(events.nth(3)).toContainText("Identity provenance unknown");
  await expect(page.locator("#events img")).toHaveCount(0);
  expect(await page.evaluate(() => window.issuerInjected)).toBeUndefined();
  await expect
    .poll(() =>
      page.evaluate(() => document.documentElement.scrollWidth - innerWidth),
    )
    .toBeLessThanOrEqual(1);
  await info.attach("review-identity-labels", {
    body: await page.screenshot({ fullPage: true }),
    contentType: "image/png",
  });
});

test("a competing review retains the draft and requires an explicit reload", async ({
  page,
  context,
  request,
}, info) => {
  const date = dateFor(info, "02"),
    batch = await create(request, date);
  const other = await context.newPage();
  await openBatch(page, date);
  const result = await reference(page, batch);
  await openBatch(other, date);
  await reference(other, batch);
  await draft(page, "first-reviewer", "I am checking the original evidence.");
  await draft(other, "second-reviewer", "Retain this draft after conflict.");
  await page.getByRole("button", { name: "Save review" }).click();
  await expect(page.locator("#result-state")).toContainText("Version 1");
  const writes = [];
  other.on("request", (req) => {
    if (req.method() === "POST") writes.push(req.postDataJSON());
  });
  await other.getByRole("button", { name: "Save review" }).click();
  await expect(other.locator("#review-block")).toContainText(
    "This result changed",
  );
  await expect(other.getByLabel("Review note", { exact: true })).toHaveValue(
    "Retain this draft after conflict.",
  );
  await expect(
    other.getByRole("button", { name: "Save review" }),
  ).toBeDisabled();
  expect(writes).toHaveLength(1);
  expect(writes[0].expectedVersion).toBe(0);
  await info.attach("review-conflict", {
    body: await other.screenshot({ fullPage: true }),
    contentType: "image/png",
  });
  await other
    .getByRole("button", { name: "Reload result and history" })
    .click();
  await expect(other.locator("#events")).toContainText("first-reviewer");
  await expect(
    other.getByRole("button", { name: "Save review" }),
  ).toBeEnabled();
  await expect(other.getByLabel("Review note", { exact: true })).toHaveValue(
    "Retain this draft after conflict.",
  );
  await other
    .getByRole("combobox", { name: "Next state", exact: true })
    .selectOption("RESOLVED");
  await other.getByRole("button", { name: "Save review" }).click();
  await expect(other.locator("#result-state")).toContainText(
    "Resolved · Version 2",
  );
  expect(writes).toHaveLength(2);
  expect(writes[1].expectedVersion).toBe(1);
  const events = await (
    await request.get(`/api/results/${result.id}/events`)
  ).json();
  expect(events.events.map((event) => event.actor)).toEqual([
    "first-reviewer",
    "second-reviewer",
  ]);
  await other.close();
});

test("a lost response after commit does not automatically retry the review", async ({
  page,
  request,
}, info) => {
  const date = dateFor(info, "03"),
    batch = await create(request, date);
  await openBatch(page, date);
  const result = await reference(page, batch);
  let writes = 0;
  await page.route(
    `**/api/results/${result.id}/reviews`,
    async (route) => {
      writes++;
      const response = await route.fetch();
      expect(response.status()).toBe(200);
      await route.abort("failed");
    },
    { times: 1 },
  );
  await draft(
    page,
    "network-reviewer",
    "The response will be deliberately dropped.",
  );
  await page.getByRole("button", { name: "Save review" }).click();
  await expect(page.locator("#review-block")).toContainText(
    "Save was not confirmed",
  );
  await expect(
    page.getByRole("button", { name: "Save review" }),
  ).toBeDisabled();
  expect(writes).toBe(1);
  expect(
    (await (await request.get("/api/results/" + result.id)).json()).version,
  ).toBe(1);
  await page.getByRole("button", { name: "Reload result and history" }).click();
  await expect(page.locator("#result-state")).toContainText("Version 1");
  await expect(page.locator("#events")).toContainText("network-reviewer");
  await expect(page.getByLabel("Review note", { exact: true })).toHaveValue(
    "The response will be deliberately dropped.",
  );
  expect(
    (await (await request.get(`/api/results/${result.id}/events`)).json())
      .events,
  ).toHaveLength(1);
});

test("batch and audit pages retain their cursors and filters", async ({
  page,
  request,
}, info) => {
  const date = dateFor(info, "04"),
    batches = [];
  for (let i = 0; i < 11; i++) batches.push(await create(request, date));
  await openBatch(page, date);
  await expect(page.locator("#batches button")).toHaveCount(10);
  await page.getByRole("button", { name: "Older batches" }).click();
  await expect(page.locator("#batches button")).toHaveCount(1);
  await expect(
    page.getByRole("button", { name: "Older batches" }),
  ).toBeDisabled();
  await page.locator("#batches button").click();
  const result = batches[0].results.find(
    (item) => item.reference === "INV-101",
  );
  for (let version = 0; version < 11; version++) {
    const response = await request.post(`/api/results/${result.id}/reviews`, {
      data: {
        expectedVersion: version,
        targetState: version % 2 ? "OPEN" : "IN_REVIEW",
        actor: "history-reviewer",
        note: `Synthetic history transition ${version + 1}.`,
      },
    });
    expect(response.status()).toBe(200);
  }
  await reference(page, batches[0]);
  await expect(page.locator("#events li")).toHaveCount(10);
  await page.getByRole("button", { name: "Next events" }).click();
  await expect(page.locator("#events li")).toHaveCount(1);
  await expect(page.locator("#events")).toContainText(
    "Synthetic history transition 11.",
  );
  await expect(
    page.getByRole("button", { name: "Next events" }),
  ).toBeDisabled();
  await page.getByLabel("Currency", { exact: true }).fill("ZZZ");
  await page.getByRole("button", { name: "Refresh batches" }).click();
  await expect(page.locator("#empty-batches")).toBeVisible();
});

test("wire numbers beyond the safe integer range stay exact in cursor requests", async ({
  page,
  request,
}, info) => {
  const date = dateFor(info, "05"),
    batch = await create(request, date),
    cursor = "9223372036854775806";
  let requested;
  // Contract fixture: no real database sequence is advanced to this value.
  await page.route("**/api/batches?*", async (route) => {
    const url = new URL(route.request().url());
    if (url.searchParams.has("beforeSequence")) {
      requested = url.searchParams.get("beforeSequence");
      await route.fulfill({ json: { batches: [], nextBeforeSequence: null } });
    } else
      await route.fulfill({
        contentType: "application/json",
        body: `{"batches":[{"id":"${batch.id}","sequence":${cursor},"businessDate":"${date}","currency":"USD","createdAt":"2040-01-01T00:00:00Z"}],"nextBeforeSequence":${cursor}}`,
      });
  });
  await openBatch(page, date);
  await expect(page.locator("#batches")).toContainText(cursor);
  await page.getByRole("button", { name: "Older batches" }).click();
  await expect(page.locator("#empty-batches")).toBeVisible();
  expect(requested).toBe(cursor);
  const values = await page.evaluate(async () => {
    const { parseLedger } = await import("/review/review.js");
    return parseLedger(
      '{"amount":-999999999999.99,"nextAfterSequence":9223372036854775807,"sequence":9007199254740993}',
    );
  });
  expect(values).toEqual({
    amount: "-999999999999.99",
    nextAfterSequence: "9223372036854775807",
    sequence: "9007199254740993",
  });
});
