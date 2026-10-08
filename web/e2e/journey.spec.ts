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
 * journeys share its one host, so they run one after another.
 * - a booked session followed on Home to its charge (booked through the API);
 * - on a laptop, the whole booking: a template's form, a 12 MiB upload, the quote, booking, the session live to
 *   settled, and an output whose SHA-256 is the upload's; and Run again, filled from it;
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
}

async function getSession(request: APIRequestContext, id: string): Promise<Session> {
  const response = await request.get(`${API}/v1/sessions/${id}`, {
    headers: { Authorization: `Bearer ${await token(request)}` },
  });
  return (await response.json()) as Session;
}

/** Books through the API, as the CLI would, for journeys about what comes after booking. */
async function book(request: APIRequestContext, templateId: string, key: string): Promise<Session> {
  const response = await request.post(`${API}/v1/sessions`, {
    headers: { Authorization: `Bearer ${await token(request)}`, "Idempotency-Key": `${user}-${key}` },
    data: { templateId, maxPricePaisePerMin: 5, maxSpendPaise: 10_000 },
  });
  expect(response.status()).toBe(201);
  return (await response.json()) as Session;
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
  const credit = page.getByRole("region", { name: "Credit" });
  await expect(credit.getByText("₹500.00").first()).toBeVisible();
  await expect(page.getByText("No sessions yet")).toBeVisible();
  await expectAccessible(page);

  const session = await book(request, "cuda-smoke-test", "web-journey");
  await page.reload();
  const running = page.getByRole("region", { name: "Running now" });
  await expect(running.getByText("Running", { exact: true })).toBeVisible({ timeout: 90_000 });
  await expect(running.getByText("so far", { exact: true })).toBeVisible();
  await expect(page.getByText(/Live · updated/)).toBeVisible();
  await expectAccessible(page);

  // The smoke test runs 20 s: one started minute at 2 paise, charged once metering closes the session.
  const recent = page.getByRole("region", { name: "Recent sessions" });
  await expect(recent.getByText("Completed", { exact: true })).toBeVisible({ timeout: 120_000 });
  await expect(recent.getByText("₹0.02")).toBeVisible({ timeout: 90_000 });
  await page.reload();
  await expect(credit.getByText("₹499.98").first()).toBeVisible({ timeout: 30_000 });
  await recent.getByRole("link", { name: /CUDA smoke test/ }).click();
  await expect(page).toHaveURL(new RegExp(`/session/\\?id=${session.id}`));
  await expect(page.getByText("Charged", { exact: true })).toBeVisible();
  await expectAccessible(page);
});

test.describe("on a laptop", () => {
  test.use(laptop);

  test("the whole booking: form, upload, quote, live session, settled charge and the output", async ({
    page,
    request,
  }, info) => {
    const file = randomBytes(12 * MIB);
    const sha256 = createHash("sha256").update(file).digest("hex");
    await startAt(page, "/book/");
    await page.getByRole("button", { name: /Echo an input/ }).click();
    await expect(page).toHaveURL(/template=echo-input&step=configure/);
    await page.getByLabel("Greeting", { exact: true }).fill("hello");
    await page.getByLabel("Repeat (optional)").fill("3");

    const chosenAt = Date.now();
    await page
      .getByTestId("input-file")
      .setInputFiles({ name: "greetings.bin", mimeType: "application/octet-stream", buffer: file });
    await expect(page.getByRole("progressbar", { name: "Uploading greetings.bin" })).toBeVisible();
    await expect(page.getByText("12 MB · uploaded")).toBeVisible({ timeout: 60_000 });
    const uploadSeconds = (Date.now() - chosenAt) / 1000;
    await expectAccessible(page);

    const quoted = page.waitForResponse(
      (r) => r.url().endsWith("/v1/session-quotes") && r.request().method() === "POST",
    );
    await page.getByRole("button", { name: "Review" }).click();
    const quote = (await (await quoted).json()) as {
      holdPaise: number;
      availablePaise: number;
      hostsFitNow: number;
      pricePaisePerMin: { min: number; max: number } | null;
    };
    await expect(page.getByText(`${formatPaise(quote.holdPaise)} is held now`)).toBeVisible();
    await expect(page.getByText(`Hosts free now: ${quote.hostsFitNow}`, { exact: false })).toBeVisible();
    await expect(
      page.getByText(
        `Your available credit after the hold: ${formatPaise(quote.availablePaise - quote.holdPaise)}`,
      ),
    ).toBeVisible();
    await expectAccessible(page);

    const created = page.waitForResponse(
      (r) => r.url().endsWith("/v1/sessions") && r.request().method() === "POST",
    );
    const pressedAt = Date.now();
    await page.getByRole("button", { name: "Book for up to ₹500.00" }).click();
    const session = (await (await created).json()) as Session;
    await expect(page).toHaveURL(new RegExp(`/session/\\?id=${session.id}`));
    await expect(page.getByTestId("state-chip").first()).toHaveText("Running", { timeout: 90_000 });
    const runningSeconds = (Date.now() - pressedAt) / 1000;
    // The hold is taken when a host is matched, not at booking (ADR 0015).
    const heldWhileRunning = (await getSession(request, session.id)).reservedPaise;
    await expect(page.locator("[data-pulse]")).toBeVisible();
    await expect(page.getByTestId("log-panel")).toContainText("hello", { timeout: 60_000 });
    await expect(page.getByText(/^Checkpoint \d+$/).first()).toBeVisible({ timeout: 60_000 });
    await expectAccessible(page);

    await expect(page.getByTestId("state-chip").first()).toHaveText("Completed", { timeout: 120_000 });
    await expect(page.getByText("Charged", { exact: true })).toBeVisible({ timeout: 90_000 });
    const settled = await getSession(request, session.id);
    expect(settled.settled).toBe(true);
    await expect(
      page
        .getByRole("definition")
        .filter({ hasText: formatPaise(settled.chargedPaise) })
        .first(),
    ).toBeVisible();

    const output = page.getByRole("link", { name: /result\.json/ });
    await expect(output).toBeVisible();
    const result = (await (await request.get((await output.getAttribute("href"))!)).json()) as {
      input: { sha256: string; size_bytes: number } | null;
      params: Record<string, string>;
      echo: string[] | null;
    };
    expect(result.input?.sha256).toBe(sha256);
    expect(result.params).toMatchObject({ SESSION_PARAM_GREETING: "hello", SESSION_PARAM_REPEAT: "3" });
    expect(result.echo).toEqual(["hello", "hello", "hello"]);
    await expectAccessible(page);
    booked = { id: session.id, sha256 };

    const numbers = {
      bookToRunningSeconds: Number(runningSeconds.toFixed(1)),
      uploadMiBPerSecond: Number((12 / uploadSeconds).toFixed(1)),
      quotedHoldPaise: quote.holdPaise,
      heldWhileRunningPaise: heldWhileRunning,
      chargedOnScreen: formatPaise(settled.chargedPaise),
      chargedByApiPaise: settled.chargedPaise,
      fileSha256: sha256,
      outputSha256: result.input?.sha256,
    };
    console.log(`journey numbers: ${JSON.stringify(numbers)}`);
    expect(heldWhileRunning).toBe(quote.holdPaise);
    const report = process.env.E2E_JOURNEY_REPORT;
    if (report) {
      mkdirSync(report.slice(0, report.lastIndexOf("/")), { recursive: true });
      writeFileSync(report, `${JSON.stringify(numbers, null, 2)}\n`);
    }
    await info.attach("numbers", { body: JSON.stringify(numbers), contentType: "application/json" });
  });

  test("Run again opens the form filled from the session, with the same upload", async ({ page }) => {
    test.skip(booked === null, "needs the booking above");
    await startAt(page, `/session/?id=${booked!.id}`);
    await page.getByRole("link", { name: "Run again" }).click();
    await expect(page).toHaveURL(/template=echo-input&step=configure&from=/);
    await expect(page.getByLabel("Greeting", { exact: true })).toHaveValue("hello");
    await expect(page.getByLabel("Repeat (optional)")).toHaveValue("3");
    await expect(page.getByText("12 MB · uploaded")).toBeVisible();
    await expect(page.getByLabel("Spending cap, in ₹")).toHaveValue("500.00");
    await expectAccessible(page);
  });

  test("not enough credit: the review gives both numbers and the button stays disabled", async ({ page }) => {
    await startAt(page, "/book/?template=cuda-smoke-test&step=configure", `${user}-broke`);
    await page.getByRole("button", { name: "Review" }).click();
    await expect(page.getByText(/Booking holds ₹\d+\.\d\d; you have ₹0\.00 available\./)).toBeVisible();
    await expect(page.getByRole("button", { name: /Book for up to/ })).toBeDisabled();
    await expectAccessible(page);
  });

  test("cancelling an upload deletes it", async ({ page, request }) => {
    await startAt(page, "/book/?template=echo-input&step=configure");
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
    await expect(page.getByRole("button", { name: "Choose a file" })).toBeVisible();
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
  await page.getByRole("button", { name: "Stop", exact: true }).click();
  const dialog = page.getByRole("dialog", { name: "Stop this session?" });
  await expect(dialog).toBeVisible();
  await expectAccessible(page);
  await dialog.getByRole("button", { name: "Stop the session" }).click();
  await expect(dialog).toBeHidden();
  await expect(page.getByText(/Finishing|Completed/).first()).toBeVisible();
  await expect(page.getByTestId("state-chip").first()).toHaveText("Completed", { timeout: 90_000 });
  await expect(page.getByText("Charged", { exact: true })).toBeVisible({ timeout: 90_000 });
  const settled = await getSession(request, session.id);
  await expect(
    page
      .getByRole("definition")
      .filter({ hasText: formatPaise(settled.chargedPaise) })
      .first(),
  ).toBeVisible();
  await expectAccessible(page);
  await page.getByRole("link", { name: "Run again" }).click();
  await expect(page).toHaveURL(/template=long-run&step=configure&from=/);
  await expect(page.getByLabel("Spending cap, in ₹")).toHaveValue(
    formatPaise(settled.maxSpendPaise).replace("₹", "").replaceAll(",", ""),
  );
});
