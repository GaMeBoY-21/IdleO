import { expect, test, type APIRequestContext, type Page } from "@playwright/test";
import { createHash, randomBytes } from "node:crypto";
import { mkdirSync, writeFileSync } from "node:fs";
import { createServer, request as forward } from "node:http";
import type { AddressInfo, Socket } from "node:net";
import { formatPaise } from "../src/lib/money";
import { API, expectAccessible, signIn } from "./support";

/**
 * The renter's journeys against the real local stack (scripts/e2e/agent-online.sh), with a real agent running the
 * sessions on its development runner. The script creates E2E_WEB_USER and grants it ₹500 before this runs; the
 * journeys share their hosts, so they run one after another.
 * - a booked session followed on Home to its charge (booked through the API);
 * - on a laptop, the whole request: a service, its template, its settings, a 12 MiB upload, the quote without a
 *   ceiling, "Request a GPU", finding and found, the session live to settled, and an output whose SHA-256 is the
 *   upload's; the ceiling the server chose and the hold it took; and Run again, filled from it;
 * - no GPU free: Book says so and the button waits, and the API refuses a booking without a ceiling;
 * - on a phone, stopping a running session;
 * - not enough credit; cancelling an upload; the event stream dropping and coming back.
 * The laptop booking's numbers go to E2E_JOURNEY_REPORT for the run's summary.
 */
const user = process.env.E2E_WEB_USER ?? "";
const signInServer = process.env.SIGN_IN_URL ?? "http://localhost:8099/idleo-local";
const MIB = 1024 * 1024;

test.skip(!user, "needs the local stack and E2E_WEB_USER (scripts/e2e/agent-online.sh)");
test.describe.configure({ mode: "serial" });

const laptop = {
  viewport: { width: 1280, height: 800 },
  isMobile: false,
  hasTouch: false,
  deviceScaleFactor: 1,
};

/** The same invented user, as the script's operator tooling signs in. */
async function token(request: APIRequestContext, who = user): Promise<string> {
  const response = await request.post(`${signInServer}/token`, {
    form: { grant_type: "client_credentials", client_id: who, client_secret: "unused", scope: "openid" },
  });
  return ((await response.json()) as { access_token: string }).access_token;
}

interface Session {
  id: string;
  state: string;
  settled: boolean;
  chargedPaise: number;
  reservedPaise: number;
  maxSpendPaise: number;
  maxPricePaisePerMin: number;
  pricePaisePerMin: number | null;
  createdAt: string;
  events: { state: string; at: string }[];
}

async function getSession(request: APIRequestContext, id: string): Promise<Session> {
  const response = await request.get(`${API}/v1/sessions/${id}`, {
    headers: { Authorization: `Bearer ${await token(request)}` },
  });
  return (await response.json()) as Session;
}

/** Books through the API, as the CLI would, for journeys about what comes after booking; the server sets the ceiling. */
async function book(request: APIRequestContext, templateId: string, key: string): Promise<Session> {
  const response = await request.post(`${API}/v1/sessions`, {
    headers: { Authorization: `Bearer ${await token(request)}`, "Idempotency-Key": `${user}-${key}` },
    data: { templateId, maxSpendPaise: 10_000 },
  });
  expect(response.status()).toBe(201);
  return (await response.json()) as Session;
}

/** The highest price among the GPUs free now with at least this much memory, as availability says. */
async function highestFitting(request: APIRequestContext, vramMb: number): Promise<number | null> {
  const response = await request.get(`${API}/v1/availability`, {
    headers: { Authorization: `Bearer ${await token(request)}` },
  });
  const { gpus } = (await response.json()) as {
    gpus: { vramMb: number; hostsIdle: number; pricePaisePerMin: { max: number } }[];
  };
  const fitting = gpus.filter((gpu) => gpu.hostsIdle > 0 && gpu.vramMb >= vramMb);
  return fitting.length === 0 ? null : Math.max(...fitting.map((gpu) => gpu.pricePaisePerMin.max));
}

/** Stops a session through the API, so the next journey finds the host free; then waits until it's settled. */
async function stopAndSettle(request: APIRequestContext, id: string): Promise<void> {
  await request.post(`${API}/v1/sessions/${id}/stop`, {
    headers: { Authorization: `Bearer ${await token(request)}` },
    data: { checkpointFirst: false },
  });
  await expect.poll(async () => (await getSession(request, id)).settled, { timeout: 120_000 }).toBe(true);
}

async function startAt(page: Page, path: string, who = user) {
  await signIn(page, who);
  if (new URL(page.url()).pathname === "/welcome/") {
    await page.getByRole("button", { name: "Go to your home" }).click();
    await page.waitForURL((url) => url.pathname === "/");
  }
  await page.goto(path);
}

let booked: { id: string; sha256: string } | null = null;

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
});

test.describe("on a laptop", () => {
  test.use(laptop);

  test("the whole request: a service, its settings, an upload, Request a GPU, live to settled and the output", async ({
    page,
    request,
  }, info) => {
    const file = randomBytes(12 * MIB);
    const sha256 = createHash("sha256").update(file).digest("hex");
    await startAt(page, "/book/");
    await page.getByRole("button", { name: /^Test jobs/ }).click();
    await page.getByRole("button", { name: /^Echo an input/ }).click();
    await expect(page).toHaveURL(/service=developer-tests&template=echo-input/);
    await page.getByRole("button", { name: /^Settings/ }).click();
    const sheet = page.getByRole("dialog", { name: "Settings" });
    await sheet.getByLabel("Greeting", { exact: true }).fill("hello");
    await sheet.getByLabel("Repeat (optional)").fill("3");
    await expect(sheet.getByLabel("The most per hour, in ₹ (optional)")).toHaveValue("");
    await sheet.getByRole("button", { name: "Done" }).click();

    const chosenAt = Date.now();
    await page
      .getByTestId("input-file")
      .setInputFiles({ name: "greetings.bin", mimeType: "application/octet-stream", buffer: file });
    await expect(page.getByRole("progressbar", { name: "Uploading greetings.bin" })).toBeVisible();
    await expect(page.getByText("12 MB · uploaded", { exact: true })).toBeVisible({ timeout: 60_000 });
    const uploadSeconds = (Date.now() - chosenAt) / 1000;

    // The most per hour was left empty, so the quote has no ceiling: the server's is the highest price among the
    // GPUs that fit now (checked against availability below).
    const quoted = await page.waitForResponse(
      (r) => r.url().endsWith("/v1/session-quotes") && r.request().method() === "POST" && r.ok(),
    );
    const quote = (await quoted.json()) as {
      ceilingPaisePerMin: number;
      holdPaise: number;
      availablePaise: number;
    };
    await expect(
      page.getByText(
        `${formatPaise(quote.holdPaise)} is held while it runs. You have ${formatPaise(quote.availablePaise)}.`,
      ),
    ).toBeVisible();
    await expectAccessible(page);

    const highest = await highestFitting(request, 1024);
    const created = page.waitForResponse(
      (r) => r.url().endsWith("/v1/sessions") && r.request().method() === "POST",
    );
    const pressedAt = Date.now();
    await page.getByRole("button", { name: "Request a GPU" }).click();
    const session = (await (await created).json()) as Session;
    await expect(page).toHaveURL(new RegExp(`/session/\\?id=${session.id}`));
    // Finding and found may each last less than a poll; whichever shows, the first time a GPU is named is "found".
    await expect(page.getByRole("heading", { name: /Finding your GPU|GPU found|on an? / })).toBeVisible({
      timeout: 30_000,
    });
    await expect(page.getByRole("heading", { name: /GPU found|on an? / })).toBeVisible({ timeout: 90_000 });
    const foundSeconds = (Date.now() - pressedAt) / 1000;
    await expect(page.getByTestId("state-chip").first()).toHaveText("Running", { timeout: 90_000 });
    const runningSeconds = (Date.now() - pressedAt) / 1000;
    // The hold is taken when a host is matched (ADR 0015): 30 minutes at the matched GPU's price, within the cap.
    // The quote showed it at the ceiling, the most it can be; with every host at one price, the two are the same.
    const live = await getSession(request, session.id);
    const thirtyMinutes = Math.min(30 * live.maxPricePaisePerMin, live.maxSpendPaise);
    const after = (state: string) => {
      const at = live.events.find((event) => event.state === state)?.at;
      return at ? Number(((Date.parse(at) - Date.parse(live.createdAt)) / 1000).toFixed(1)) : null;
    };
    await expect(page.locator("[data-pulse]")).toBeVisible();
    await expect(page.getByTestId("log-panel")).toContainText("hello", { timeout: 60_000 });
    await expectAccessible(page);

    await expect(page.getByRole("heading", { name: "Done" })).toBeVisible({ timeout: 120_000 });
    await expect(page.getByText("Charged", { exact: true })).toBeVisible({ timeout: 90_000 });
    const settled = await getSession(request, session.id);
    expect(settled.settled).toBe(true);
    await expect(page.getByText(formatPaise(settled.chargedPaise), { exact: true })).toBeVisible();

    const output = page.getByRole("link", { name: /result\.json/ });
    await expect(output).toBeVisible();
    const result = (await (await request.get((await output.getAttribute("href"))!)).json()) as {
      inputs: Record<string, { sha256: string; size_bytes: number }[]>;
      params: Record<string, string>;
      echo: string[] | null;
    };
    expect(result.inputs.file?.[0]?.sha256).toBe(sha256);
    expect(result.params).toMatchObject({ SESSION_PARAM_GREETING: "hello", SESSION_PARAM_REPEAT: "3" });
    expect(result.echo).toEqual(["hello", "hello", "hello"]);
    await expectAccessible(page);
    booked = { id: session.id, sha256 };

    const numbers = {
      requestToFoundSeconds: Number(foundSeconds.toFixed(1)),
      requestToRunningSeconds: Number(runningSeconds.toFixed(1)),
      serverMatchedAfterSeconds: after("MATCHED"),
      serverRunningAfterSeconds: after("RUNNING"),
      matchedPricePaisePerMin: live.pricePaisePerMin,
      uploadMiBPerSecond: Number((12 / uploadSeconds).toFixed(1)),
      ceilingChosenPaisePerMin: session.maxPricePaisePerMin,
      highestFittingPaisePerMin: highest,
      quotedHoldPaise: quote.holdPaise,
      heldWhileRunningPaise: live.reservedPaise,
      thirtyMinutesAtCeilingPaise: thirtyMinutes,
      chargedOnScreen: formatPaise(settled.chargedPaise),
      chargedByApiPaise: settled.chargedPaise,
      fileSha256: sha256,
      outputSha256: result.inputs.file?.[0]?.sha256,
    };
    console.log(`journey numbers: ${JSON.stringify(numbers)}`);
    expect(session.maxPricePaisePerMin).toBe(quote.ceilingPaisePerMin);
    expect(session.maxPricePaisePerMin).toBe(highest);
    expect(live.reservedPaise).toBe(Math.min(30 * live.pricePaisePerMin!, live.maxSpendPaise));
    expect(live.reservedPaise).toBeLessThanOrEqual(quote.holdPaise);
    expect(quote.holdPaise).toBe(thirtyMinutes);
    const report = process.env.E2E_JOURNEY_REPORT;
    if (report) {
      mkdirSync(report.slice(0, report.lastIndexOf("/")), { recursive: true });
      writeFileSync(report, `${JSON.stringify(numbers, null, 2)}\n`);
    }
    await info.attach("numbers", { body: JSON.stringify(numbers), contentType: "application/json" });
  });

  test("Run again opens Book filled from the session, with the same upload", async ({ page }) => {
    test.skip(booked === null, "needs the booking above");
    await startAt(page, `/session/?id=${booked!.id}`);
    await page.getByRole("link", { name: "Run again" }).click();
    await expect(page).toHaveURL(/template=echo-input&from=/);
    await expect(page.getByText("12 MB · uploaded", { exact: true })).toBeVisible();
    await page.getByRole("button", { name: /^Settings/ }).click();
    const sheet = page.getByRole("dialog", { name: "Settings" });
    await expect(sheet.getByLabel("Greeting", { exact: true })).toHaveValue("hello");
    await expect(sheet.getByLabel("Repeat (optional)")).toHaveValue("3");
    await expect(sheet.getByLabel("Spending cap, in ₹")).toHaveValue("500.00");
    await expectAccessible(page);
  });

  test("no GPU is free for it: Book says so and waits, and the API refuses a booking without a ceiling", async ({
    page,
    request,
  }) => {
    await startAt(page, "/book/?template=needs-big-gpu");
    await expect(page.getByText("No GPU is free right now", { exact: true })).toBeVisible({
      timeout: 30_000,
    });
    await expect(page.getByRole("button", { name: "Request a GPU" })).toBeDisabled();
    await expectAccessible(page);
    const refused = await request.post(`${API}/v1/sessions`, {
      headers: { Authorization: `Bearer ${await token(request)}`, "Idempotency-Key": `${user}-web-no-gpu` },
      data: { templateId: "needs-big-gpu", maxSpendPaise: 10_000 },
    });
    expect(refused.status()).toBe(409);
    expect(((await refused.json()) as { type: string }).type).toBe("/problems/no-gpu-free");
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
    await page.getByTestId("input-file").setInputFiles({
      name: "big.bin",
      mimeType: "application/octet-stream",
      buffer: randomBytes(24 * MIB),
    });
    const upload = (await (await opened).json()) as { id: string };
    await page.getByRole("button", { name: "Cancel the upload of big.bin" }).click();
    await expect(page.getByText("Choose a file", { exact: true })).toBeVisible();
    await expect
      .poll(
        async () => {
          const response = await request.get(`${API}/v1/uploads/${upload.id}`, {
            headers: { Authorization: `Bearer ${await token(request)}` },
          });
          return ((await response.json()) as { status: string }).status;
        },
        { timeout: 30_000 },
      )
      .toBe("deleted");
  });

  test("the stream drops: the screen says so, keeps polling, and the stream comes back", async ({
    page,
    request,
  }) => {
    // The event stream goes through a proxy in this test, which can cut it and refuse it, as a dropped connection
    // would; everything else goes straight to the API.
    const api = new URL(API);
    const sockets = new Set<Socket>();
    let refuse = false;
    const proxy = createServer((incoming, outgoing) => {
      if (refuse) {
        incoming.socket.destroy();
        return;
      }
      const upstream = forward(
        {
          host: api.hostname,
          port: api.port,
          path: incoming.url,
          method: incoming.method,
          headers: { ...incoming.headers, host: api.host },
        },
        (response) => {
          outgoing.writeHead(response.statusCode ?? 502, response.headers);
          response.pipe(outgoing);
        },
      );
      upstream.on("error", () => outgoing.destroy());
      incoming.pipe(upstream);
    });
    proxy.on("connection", (socket) => {
      sockets.add(socket);
      socket.on("close", () => sockets.delete(socket));
    });
    await new Promise<void>((resolve) => proxy.listen(0, "127.0.0.1", resolve));
    const proxied = `http://127.0.0.1:${(proxy.address() as AddressInfo).port}`;
    await page.route("**/v1/sessions/*/events", (route) =>
      route.continue({ url: route.request().url().replace(api.origin, proxied) }),
    );

    const session = await book(request, "long-run", "web-stream-drop");
    try {
      await startAt(page, `/session/?id=${session.id}`);
      await expect(page.getByTestId("state-chip").first()).toHaveText("Running", { timeout: 90_000 });
      await expect(page.getByText(/Live, updated/)).toBeVisible();

      refuse = true;
      sockets.forEach((socket) => socket.destroy());
      await expect(page.getByText(/Live updates stopped/)).toBeVisible({ timeout: 30_000 });
      await expect(page.getByText(/Live, updated/)).toHaveCount(0);
      let polls = 0;
      page.on("request", (r) => {
        if (r.url().endsWith(`/v1/sessions/${session.id}`) && r.method() === "GET") {
          polls++;
        }
      });
      await expect.poll(() => polls, { timeout: 20_000 }).toBeGreaterThanOrEqual(2);
      await expectAccessible(page);

      refuse = false;
      await expect(page.getByText(/Live updates stopped/)).toHaveCount(0, { timeout: 45_000 });
      await expect(page.getByText(/Live, updated/)).toBeVisible();
    } finally {
      proxy.close();
      sockets.forEach((socket) => socket.destroy());
      await stopAndSettle(request, session.id);
    }
  });
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
  await page.getByRole("button", { name: /^Settings/ }).click();
  await expect(page.getByRole("dialog", { name: "Settings" }).getByLabel("Spending cap, in ₹")).toHaveValue(
    formatPaise(settled.maxSpendPaise).replace("₹", "").replaceAll(",", ""),
  );
});
