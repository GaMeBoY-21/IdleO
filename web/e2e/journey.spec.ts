import { expect, test } from "@playwright/test";
import { mkdirSync, readFileSync, statSync, writeFileSync } from "node:fs";
import { formatPaise } from "../src/lib/money";
import {
  authorised,
  book,
  examples,
  fileOf,
  getSession,
  highestFitting,
  laptop,
  modelFolder,
  secondsTo,
  startAt,
  user,
  trackApiRate,
} from "./journey-support";
import { streamDropJourney } from "./journey-stream";
import { API, expectAccessible, signIn } from "./support";

/**
 * The renter's journeys against the real local stack (scripts/e2e/agent-online.sh), with real agents running the
 * sessions on their development runner. The script creates E2E_WEB_USER and grants it ₹500 before this runs; the
 * journeys share their hosts, so they run one after another.
 * - a booked session followed on Home to its charge (booked through the API), its end then told in the bell and
 *   the list of notifications, read with "Mark all read";
 * - on a laptop, from Home through "Fine-tune a model": a generated model folder and examples uploaded, Quick, a
 *   budget, "Request a GPU", the session live to settled, and its adapter downloaded and read; then Run again,
 *   filled from it with the same files from My files;
 * - an upload interrupted by a reload, carrying on from its last part when the same file is chosen again;
 * - a host with too little disk (the only 24 GB GPU) never offered the session, though it's online and idle;
 * - not enough credit; cancelling an upload; the event stream dropping and coming back;
 * - on a phone, stopping a running session.
 * The fine-tune's and the resumed upload's numbers go to E2E_JOURNEY_REPORT for the run's summary.
 */
test.skip(!user, "needs the local stack and E2E_WEB_USER (scripts/e2e/agent-online.sh)");
test.describe.configure({ mode: "serial" });

const numbers: Record<string, unknown> = {};
function report() {
  const path = process.env.E2E_JOURNEY_REPORT;
  if (path) {
    mkdirSync(path.slice(0, path.lastIndexOf("/")), { recursive: true });
    writeFileSync(path, `${JSON.stringify(numbers, null, 2)}\n`);
  }
}

test("a booked session shows live on the home screen, through to its charge", async ({ page, request }) => {
  await signIn(page, user);
  if (new URL(page.url()).pathname === "/welcome/") {
    await expect(page.getByText("₹500.00")).toBeVisible();
    await page.getByRole("button", { name: "Go to your home" }).click();
  }
  const credit = page.getByRole("link", { name: /^Available credit/ });
  await expect(credit).toHaveText(/₹500\.00/);
  await expect(page.getByRole("heading", { name: "What do you want to run?" })).toBeVisible();
  await expect(page.getByRole("region", { name: "Book again" })).toHaveCount(0);
  await expectAccessible(page);

  const session = await book(request, "cuda-smoke-test", "web-journey");
  await page.reload();
  const bar = page.locator(`a[href="/session/?id=${session.id}"]`);
  await expect(bar).toContainText(/^Running a test job · \d+:\d\d/, { timeout: 90_000 });
  await expect(bar).toContainText("so far");
  await expectAccessible(page);

  // The smoke test runs 20 s: one started minute at its host's price, charged once metering closes the session.
  await expect(bar).toHaveCount(0, { timeout: 120_000 });
  await expect
    .poll(async () => (await getSession(request, session.id)).settled, { timeout: 120_000 })
    .toBe(true);
  const charged = (await getSession(request, session.id)).chargedPaise;
  expect(charged).toBeGreaterThan(0);
  const again = page.getByRole("region", { name: "Book again" });
  await expect(again.getByText(formatPaise(charged), { exact: false })).toBeVisible({ timeout: 30_000 });
  await page.reload();
  await expect(credit).toHaveText(new RegExp(formatPaise(50_000 - charged).replace(".", "\\.")), {
    timeout: 30_000,
  });
  await page.getByRole("link", { name: "All sessions" }).click();
  await page.getByRole("link", { name: "Ended" }).click();
  await page
    .getByRole("link", { name: /CUDA smoke test/ })
    .first()
    .click();
  await expect(page).toHaveURL(new RegExp(`/session/\\?id=${session.id}`));
  await expect(page.getByRole("heading", { name: "Done" })).toBeVisible();
  await expect(page.getByText("Charged", { exact: true })).toBeVisible();
  await expect(page.getByText(formatPaise(charged), { exact: true })).toBeVisible();
  await expectAccessible(page);

  // Its end reached the bell and the list, with what it cost (ADR 0026): one unread; opening it reads it.
  await page.goto("/");
  const bell = page.getByRole("link", { name: "Notifications, 1 unread" });
  await expect(bell).toBeVisible({ timeout: 30_000 });
  await bell.click();
  const told = page.getByRole("listitem").filter({ hasText: `It cost ${formatPaise(charged)}.` });
  await expect(told).toHaveCount(1);
  await expect(told).toContainText("finished");
  await expect(told).toContainText("Unread:");
  await expect(page.getByText("1 unread")).toBeVisible();
  await expectAccessible(page);
  await page.getByRole("button", { name: "Mark all read" }).click();
  await expect(page.getByText("All read", { exact: true })).toBeVisible();
  await expect(told).not.toContainText("Unread:");
  await page.reload();
  await expect(page.getByText("All read", { exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: "Notifications", exact: true })).toBeVisible();
  await told.getByRole("link").click();
  await expect(page).toHaveURL(new RegExp(`/session/\\?id=${session.id}`));
});

test.describe("on a laptop", () => {
  test.use(laptop);
  let fineTuned: string | null = null;

  test("from Home, Fine-tune a model: a model folder, examples, Quick, Request a GPU, live to the adapter", async ({
    page,
    request,
  }) => {
    test.setTimeout(420_000);
    const model = modelFolder();
    await startAt(page, "/");
    const requestsInBusiestMinute = trackApiRate(page);
    await page
      .getByRole("link", { name: /Fine-tune a model/ })
      .first()
      .click();
    await expect(page).toHaveURL(/\/book\/\?service=fine-tune$/);
    await expect(page.getByRole("heading", { level: 1, name: "Fine-tune a model" })).toBeVisible();

    // The last quote the screen showed before "Request a GPU": no ceiling was set, so it's the server's.
    let quote: { ceilingPaisePerMin: number; holdPaise: number; vramNeededMb: number } | null = null;
    page.on("response", async (r) => {
      if (r.url().endsWith("/v1/session-quotes") && r.ok()) {
        quote = (await r.json()) as typeof quote;
      }
    });
    const chosenAt = Date.now();
    await page.getByTestId("model-folder").setInputFiles(model.path);
    await page.getByTestId("data-file").setInputFiles(examples());
    const yourModel = page.getByRole("region", { name: "1. Your model" });
    await expect(yourModel.getByText(/· 3 files · in My files$/)).toBeVisible({ timeout: 60_000 });
    await expect(
      page.getByRole("region", { name: "2. Your training data" }).getByText(/in My files$/),
    ).toBeVisible({
      timeout: 60_000,
    });
    const uploadSeconds = (Date.now() - chosenAt) / 1000;
    // 12 MiB of weights: 2,048 + 700 x 0.012 GiB, under the template's 6,144 MB, so its class: 8 GB.
    await expect(page.getByText("Your 12 MB model needs an 8 GB GPU.")).toBeVisible();
    await page.getByRole("button", { name: /^Quick/ }).click();
    await page.getByRole("button", { name: "Stop at ₹50", exact: true }).click();
    const card = page.getByRole("region", { name: "Your request" });
    await expect(card.getByText("finetune-model/")).toBeVisible();
    await expect(card.getByText("Quick · about 15 min")).toBeVisible();
    await expect(card.getByText(/^Best free 8 GB GPU$/)).toBeVisible({ timeout: 30_000 });
    await expect(page.getByText(/^₹\d+\.\d\d is held while it runs\./)).toBeVisible();
    await expectAccessible(page);
    const highest = await highestFitting(request, 8_000);

    const created = page.waitForResponse(
      (r) => r.url().endsWith("/v1/sessions") && r.request().method() === "POST",
    );
    const pressedAt = Date.now();
    await page.getByRole("button", { name: "Request a GPU" }).click();
    const session = (await (await created).json()) as { id: string };
    await expect(page).toHaveURL(new RegExp(`/session/\\?id=${session.id}`));
    await expect(page.getByTestId("state-chip").first()).toHaveText("Running", { timeout: 120_000 });
    const runningSeconds = (Date.now() - pressedAt) / 1000;
    const live = await getSession(request, session.id);
    const hostId = live.hostId;
    // ADR 0021: without a ceiling of the renter's own, the server's is the highest price among the GPUs that fit,
    // and the quote held 30 minutes at it, within the budget; the hold taken is at the matched GPU's price.
    const quoted = quote!;
    expect(quoted.vramNeededMb).toBe(8_000);
    expect(live.maxPricePaisePerMin).toBe(quoted.ceilingPaisePerMin);
    expect(live.maxPricePaisePerMin).toBe(highest);
    expect(quoted.holdPaise).toBe(Math.min(30 * quoted.ceilingPaisePerMin, 5_000));
    expect(live.reservedPaise).toBeLessThanOrEqual(quoted.holdPaise);
    await expect(page.getByTestId("log-panel")).toContainText("your model: 3 files", { timeout: 60_000 });
    await expectAccessible(page);

    await expect(page.getByRole("heading", { name: "Done" })).toBeVisible({ timeout: 240_000 });
    const adapter = page.getByRole("link", { name: /adapter_model\.safetensors/ });
    await expect(adapter).toBeVisible({ timeout: 60_000 });
    const bytes = await (await request.get((await adapter.getAttribute("href"))!)).body();
    const length = Number(bytes.readBigUInt64LE(0));
    const header = JSON.parse(bytes.subarray(8, 8 + length).toString()) as Record<string, { dtype?: string }>;
    expect(Object.keys(header).filter((name) => name !== "__metadata__")).toHaveLength(8);
    const log = page.getByRole("link", { name: /training_log\.json/ });
    const training = (await (await request.get((await log.getAttribute("href"))!)).json()) as {
      epochs: number;
      training_examples: number;
    };
    // Quick sets one epoch over the 40 examples.
    expect([training.epochs, training.training_examples]).toEqual([1, 40]);
    await expect
      .poll(async () => (await getSession(request, session.id)).settled, { timeout: 120_000 })
      .toBe(true);
    const done = await getSession(request, session.id);
    expect(done.chargedPaise).toBeGreaterThan(0);
    expect(done.presets).toEqual({ thoroughness: "quick" });
    expect(done.maxSpendPaise).toBe(5_000);
    await expectAccessible(page);
    fineTuned = session.id;

    numbers.fineTune = {
      modelFiles: model.files,
      modelBytes: Object.values(model.files).reduce((sum, size) => sum + size, 0),
      uploadSeconds: Number(uploadSeconds.toFixed(1)),
      vramNeededMb: done.vramNeededMb,
      hostId,
      ceilingPaisePerMin: live.maxPricePaisePerMin,
      highestFittingPaisePerMin: highest,
      quotedHoldPaise: quoted.holdPaise,
      heldPaise: live.reservedPaise,
      requestToRunningSeconds: Number(runningSeconds.toFixed(1)),
      serverRunningAfterSeconds: secondsTo(done, "RUNNING"),
      adapterBytes: bytes.length,
      chargedPaise: done.chargedPaise,
      // The renter limit is 60 a minute (ADR 0025); the booking, its uploads and following it live stay well under.
      requestsInBusiestMinute: requestsInBusiestMinute(),
    };
    expect(requestsInBusiestMinute(), "API requests in the busiest minute").toBeLessThanOrEqual(45);
    report();
  });

  test("Run again: the same model and data from My files, and Quick", async ({ page }) => {
    test.skip(fineTuned === null, "needs the fine-tune above");
    await startAt(page, `/session/?id=${fineTuned!}`);
    await page.getByRole("link", { name: "Run again" }).click();
    await expect(page).toHaveURL(/template=fine-tune-simulated&from=/);
    await expect(page.getByRole("button", { name: /^Quick/ })).toHaveAttribute("aria-pressed", "true");
    await expect(page.getByRole("button", { name: "Stop at ₹50", exact: true })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
    const yourModel = page.getByRole("region", { name: "1. Your model" });
    await expect(yourModel.getByRole("radio", { name: "From My files" })).toBeChecked();
    // The one picked is the model the session used.
    await expect(yourModel.locator('button[aria-pressed="true"]')).toContainText(/^finetune-model\//);
    await expect(
      page.getByRole("region", { name: "Your request" }).getByText("examples.jsonl"),
    ).toBeVisible();
    await expectAccessible(page);
  });

  test("an upload interrupted by a reload carries on from its last part", async ({ page, request }) => {
    const path = fileOf("big-examples.jsonl", 24);
    await startAt(page, "/book/?service=fine-tune");
    page.on("dialog", (dialog) => void dialog.accept());
    // Storage takes the first two parts; the rest wait, as if the connection had dropped.
    let taken = 0;
    await page.route(/\/idleo-local\/uploads\//, async (route) => {
      if (route.request().method() !== "PUT") {
        await route.continue();
      } else if (taken < 2) {
        taken++;
        await route.continue();
      }
    });
    // The progress shows a part's bytes as storage takes them; its ETag, which resuming needs, arrives with storage's
    // answer a moment later. So the reload waits for both answers.
    const answered = new Promise<void>((resolve) => {
      let count = 0;
      page.on("response", (r) => {
        if (r.request().method() === "PUT" && r.url().includes("/idleo-local/uploads/") && r.ok()) {
          count++;
          if (count === 2) {
            resolve();
          }
        }
      });
    });
    const opened = page.waitForResponse(
      (r) => r.url().endsWith("/v1/uploads") && r.request().method() === "POST",
    );
    const startedAt = Date.now();
    const data = page.getByRole("region", { name: "2. Your training data" });
    await data.getByTestId("data-file").setInputFiles(path);
    const first = ((await (await opened).json()) as { upload: { id: string } }).upload.id;
    // Two 5 MiB parts of 24 MiB.
    await expect(data.getByText(/^41% of 24 MB/)).toBeVisible({ timeout: 30_000 });
    await answered;
    await page.waitForTimeout(500);

    await page.unrouteAll({ behavior: "ignoreErrors" });
    await page.reload();
    const parts: number[] = [];
    page.on("request", (r) => {
      if (r.method() === "PUT" && r.url().includes("/idleo-local/uploads/")) {
        parts.push(Number(new URL(r.url()).searchParams.get("partNumber")));
      }
    });
    const again = page.waitForResponse(
      (r) => r.url().endsWith("/v1/uploads") && r.request().method() === "POST",
    );
    await page
      .getByRole("region", { name: "2. Your training data" })
      .getByTestId("data-file")
      .setInputFiles(path);
    const resumed = (await (await again).json()) as { upload: { id: string } };
    await expect(
      page.getByRole("region", { name: "2. Your training data" }).getByText(/in My files$/),
    ).toBeVisible({
      timeout: 60_000,
    });
    const totalSeconds = (Date.now() - startedAt) / 1000;
    expect(resumed.upload.id).toBe(first);
    expect(parts.sort((a, b) => a - b)).toEqual([3, 4, 5]);
    const ready = await request.get(`${API}/v1/uploads/${first}`, { headers: await authorised(request) });
    expect(((await ready.json()) as { upload: { status: string; sizeBytes: number } }).upload).toMatchObject({
      status: "ready",
      sizeBytes: statSync(path).size,
    });
    numbers.resumedUpload = {
      sizeBytes: readFileSync(path).length,
      parts: 5,
      takenBeforeTheReload: 2,
      resumedFromPart: parts[0],
      sentAfterTheReload: parts.length,
      totalSeconds: Number(totalSeconds.toFixed(1)),
    };
    report();
  });

  test("a host with too little disk is never offered the session, though it's online and idle", async ({
    page,
    request,
  }) => {
    const host = process.env.E2E_SMALL_DISK_HOST;
    test.skip(!host, "needs the small-disk host (scripts/e2e/agent-online.sh)");
    // The only 24 GB GPU is online and idle, as availability counts it...
    const availability = (await (
      await request.get(`${API}/v1/availability`, { headers: await authorised(request) })
    ).json()) as { gpus: { vramMb: number; hostsIdle: number }[] };
    expect(availability.gpus.filter((gpu) => gpu.vramMb >= 24_000)).toEqual([
      expect.objectContaining({ hostsIdle: 1 }),
    ]);
    // ...but its disk can't hold a session's scratch space, so for a 24 GB session nothing is free.
    await startAt(page, "/book/?template=needs-big-gpu");
    await expect(page.getByText("No GPU is free right now", { exact: true })).toBeVisible({
      timeout: 30_000,
    });
    await expect(page.getByRole("button", { name: "Request a GPU" })).toBeDisabled();
    await expectAccessible(page);
    const headers = await authorised(request);
    const refused = await request.post(`${API}/v1/sessions`, {
      headers: { ...headers, "Idempotency-Key": `${user}-web-no-gpu` },
      data: { templateId: "needs-big-gpu", maxSpendPaise: 10_000 },
    });
    expect(refused.status()).toBe(409);
    expect(((await refused.json()) as { type: string }).type).toBe("/problems/no-gpu-free");
    // With a ceiling it's booked, and ends without a host: no offer was ever made.
    const booked = await request.post(`${API}/v1/sessions`, {
      headers: { ...headers, "Idempotency-Key": `${user}-web-small-disk` },
      data: { templateId: "needs-big-gpu", maxSpendPaise: 10_000, maxPricePaisePerMin: 100 },
    });
    const { id } = (await booked.json()) as { id: string };
    await expect
      .poll(async () => (await getSession(request, id)).state, { timeout: 30_000 })
      .toBe("NO_HOST_FREE");
    const ended = await getSession(request, id);
    expect(ended.events.map((event) => event.state)).toEqual(["REQUESTED", "NO_HOST_FREE"]);
    expect(ended.events.at(-1)?.reason).toBe("no_candidate");
  });

  test("not enough credit: both numbers, and the button stays disabled", async ({ page }) => {
    await startAt(page, "/book/?template=cuda-smoke-test", `${user}-broke`);
    await expect(page.getByText(/₹\d+\.\d\d is held while it runs, and you have ₹0\.00\./)).toBeVisible({
      timeout: 30_000,
    });
    await expect(page.getByRole("button", { name: "Request a GPU" })).toBeDisabled();
    await expectAccessible(page);
  });

  test("cancelling an upload deletes it", async ({ page, request }) => {
    await startAt(page, "/book/?template=echo-input");
    // Slow storage down, so the upload is still going when it's cancelled.
    await page.route(/\/idleo-local\/uploads\//, async (route) => {
      await new Promise((resolve) => setTimeout(resolve, 3_000));
      await route.continue().catch(() => undefined);
    });
    const opened = page.waitForResponse(
      (r) => r.url().endsWith("/v1/uploads") && r.request().method() === "POST",
    );
    await page.getByTestId("file-file").setInputFiles(fileOf("big.bin", 24));
    const upload = ((await (await opened).json()) as { upload: { id: string } }).upload;
    await page.getByRole("button", { name: "Cancel the upload of big.bin" }).click();
    await expect(page.getByText("Choose a file", { exact: true })).toBeVisible();
    await expect
      .poll(
        async () => {
          const response = await request.get(`${API}/v1/uploads/${upload.id}`, {
            headers: await authorised(request),
          });
          return ((await response.json()) as { upload: { status: string } }).upload.status;
        },
        { timeout: 30_000 },
      )
      .toBe("deleted");
  });

  test("the stream drops: the screen says so, keeps polling, and the stream comes back", streamDropJourney);
});

test("on a phone: a running session stopped, with its charge, and Run again", async ({ page, request }) => {
  const session = await book(request, "long-run", "web-stop");
  await startAt(page, `/session/?id=${session.id}`);
  await expect(page.getByTestId("state-chip").first()).toHaveText("Running", { timeout: 90_000 });
  await page.getByRole("button", { name: "Stop session" }).click();
  const dialog = page.getByRole("dialog", { name: "Stop this session?" });
  await expect(dialog).toBeVisible();
  await expectAccessible(page);
  await dialog.getByRole("button", { name: "Stop the session" }).click();
  await expect(dialog).toBeHidden();
  await expect(page.getByText(/Saving a last checkpoint|^Done$/).first()).toBeVisible();
  await expect(page.getByRole("heading", { name: "Done" })).toBeVisible({ timeout: 90_000 });
  await expect(page.getByText("Charged", { exact: true })).toBeVisible({ timeout: 90_000 });
  const settled = await getSession(request, session.id);
  await expect(page.getByText(formatPaise(settled.chargedPaise), { exact: true })).toBeVisible();
  await expectAccessible(page);
  await page.getByRole("link", { name: "Run again" }).click();
  await expect(page).toHaveURL(/template=long-run&from=/);
  // A template without budget chips asks for the amount, filled from the session.
  await expect(page.getByLabel("Stop at, in ₹")).toHaveValue(
    formatPaise(settled.maxSpendPaise).replace("₹", "").replaceAll(",", ""),
  );
});
