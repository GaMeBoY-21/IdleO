import { expect, test, type APIRequestContext } from "@playwright/test";
import { execFile, spawn, type ChildProcess } from "node:child_process";
import { mkdirSync, mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { promisify } from "node:util";
import { authorised, getSession, laptop, startAt, user } from "./journey-support";
import { API, expectAccessible } from "./support";

/**
 * The owner's journeys against the real local stack (scripts/e2e/agent-online.sh), with a real agent on a fake RTX 4060
 * Laptop that the journey links itself, by approval (ADR 0032, ADR 0023). They run after every other scenario
 * is over, so the machine they add is the only change in the stack, and one after another, on one machine:
 * - link it by approval (`idleo-agent link`, one click on the approval page: no code), see it pending on Hosting, and have
 *   an operator activate it (the one step done by hand; the admin console calls the same service);
 * - pause it and resume it, and see the scheduler follow: one host fewer fits while it's paused;
 * - change its price, and see the quote's range move with it;
 * - earn from a session, and see the Hosting tab, the earnings and the machine's sessions agree with the ledger;
 * - remove it, which signs its agent out and keeps its earnings.
 * The numbers go to E2E_HOSTING_REPORT for the run's summary.
 */
const agentBinary = process.env.E2E_AGENT ?? "";
test.skip(
  !user || !agentBinary,
  "needs the local stack, E2E_WEB_USER and E2E_AGENT (scripts/e2e/agent-online.sh)",
);
test.describe.configure({ mode: "serial" });
test.use(laptop);

const run = promisify(execFile);
const numbers: Record<string, unknown> = {};
const agents: ChildProcess[] = [];
let hostId = "";

function report() {
  const path = process.env.E2E_HOSTING_REPORT;
  if (path) {
    mkdirSync(path.slice(0, path.lastIndexOf("/")), { recursive: true });
    writeFileSync(path, `${JSON.stringify(numbers, null, 2)}\n`);
  }
}

test.afterAll(() => agents.forEach((agent) => agent.kill("SIGTERM")));

/** How many hosts a quote for a small job says fit now, and the price range among them. */
async function quote(request: APIRequestContext) {
  const response = await request.post(`${API}/v1/session-quotes`, {
    headers: await authorised(request),
    data: { templateId: "cuda-smoke-test", maxSpendPaise: 10_000 },
  });
  expect(response.status()).toBe(200);
  return (await response.json()) as {
    hostsFitNow: number;
    pricePaisePerMin: { min: number; max: number } | null;
  };
}

const earnings = async (request: APIRequestContext) =>
  (await (await request.get(`${API}/v1/earnings`, { headers: await authorised(request) })).json()) as {
    totalPaise: number;
    weekPaise: number;
    history: { entries: { amountPaise: number; minutes: number; machineName: string }[] };
  };

test("offers the released Windows app on Add a machine, and its link redirects to the setup program", async ({
  page,
  request,
}) => {
  // The script published a small signed release to this stack's storage (ADR 0034), older than the agents, so no agent
  // is offered it as an update.
  const version = process.env.E2E_RELEASE_VERSION ?? "";
  const setupBytes = Number(process.env.E2E_RELEASE_SETUP_BYTES ?? "0");
  expect(version, "E2E_RELEASE_VERSION from scripts/e2e/agent-online.sh").toMatch(/^\d+\.\d+\.\d+$/);
  await startAt(page, "/hosting/add/");
  const download = page.getByRole("link", { name: "Download for Windows" });
  await expect(download).toHaveAttribute("href", `${API}/v1/agent-releases/latest/setup`);
  await expect(page.getByText(`v${version} · ${(setupBytes / 1_000_000).toFixed(1)} MB`)).toBeVisible();
  await expectAccessible(page);

  // The link is a redirect to storage, which serves the bare setup program under its own name.
  const redirect = await request.get(`${API}/v1/agent-releases/latest/setup`, { maxRedirects: 0 });
  expect(redirect.status()).toBe(302);
  const location = redirect.headers()["location"] ?? "";
  expect(location).toContain(`/releases/stable/${version}/IdleO-Setup.exe`);
  const setup = await request.get(location);
  expect(setup.status()).toBe(200);
  expect(setup.headers()["content-disposition"]).toBe('attachment; filename="IdleO-Setup.exe"');
  expect((await setup.body()).length).toBe(setupBytes);
  numbers.release = { version, setupBytes };
  report();
});

test("links a laptop by approval: asked for, approved in one click, pending on Hosting, then an operator activates it", async ({
  page,
}) => {
  // The host app, as an owner would run it: `idleo-agent link` asks, prints the approve address (no browser to open on a
  // test machine), and waits. No code is ever shown or typed.
  const dir = mkdtempSync(join(tmpdir(), "idleo-hosting-"));
  const config = join(dir, "agent.toml");
  writeFileSync(
    config,
    `ca_root = "${process.env.E2E_AGENT_CA}"\ndata_dir = "${join(dir, "data")}"\nlog_format = "text"\n\n[control_plane]\nhost = "localhost"\nport = ${process.env.E2E_GRPC_PORT}\n`,
  );
  const asked = Date.now();
  const linking = spawn(
    agentBinary,
    [
      "--config",
      config,
      "link",
      "--api",
      API,
      "--no-browser",
      "--name",
      "Journey laptop",
      "--fake-gpu",
      "--fake-gpu-model",
      "RTX 4060 Laptop",
      "--fake-gpu-vram-mb",
      "8188",
    ],
    { stdio: ["ignore", "pipe", "pipe"], env: { ...process.env, IDLEO_LOG: "info" } },
  );
  agents.push(linking);
  let output = "";
  linking.stdout.on("data", (chunk: Buffer) => (output += chunk.toString()));
  const finished = new Promise<number | null>((resolve) => linking.on("close", resolve));
  await expect
    .poll(() => /Open this link on any device to approve: (\S+)/.exec(output)?.[1], {
      timeout: 60_000,
    })
    .toBeTruthy();
  const approveUrl = /Open this link on any device to approve: (\S+)/.exec(output)![1]!;
  expect(output).toContain("Waiting for you to approve in the browser");
  // The page the owner would be sent to, on this stack's web app.
  await startAt(page, new URL(approveUrl).pathname);
  await expect(
    page.getByRole("heading", { level: 1, name: "Link this laptop to your account?" }),
  ).toBeVisible();
  await expect(page.getByText("RTX 4060 Laptop · 8 GB")).toBeVisible();
  await expect(page.getByLabel("Name", { exact: true })).toHaveValue("Journey laptop");
  await expect(page.getByText(/You'll earn/)).toContainText("an hour (renters pay");
  await expectAccessible(page);
  const approving = Date.now();
  await page.getByRole("button", { name: "Approve and start earning" }).click();
  await expect(page.getByRole("heading", { name: "Linked" })).toBeVisible();
  await expect(page.getByText(/We check every new laptop before it goes live/)).toBeVisible();
  // The agent's poll gets its ticket and finishes linking by itself.
  expect(await finished).toBe(0);
  const linked = Date.now();
  expect(output).toMatch(/Linked to \S… as 'Journey laptop'\. You can close the browser\./);
  numbers.link = {
    askToApproveMs: approving - asked,
    approveToLinkedMs: linked - approving,
    totalMs: linked - asked,
  };
  hostId = new URL(
    (await page.getByRole("link", { name: "Open your laptop" }).getAttribute("href", { timeout: 10_000 })) ??
      "",
    "http://x",
  ).searchParams.get("id")!;
  expect(hostId).toMatch(/^[0-9a-f-]{36}$/);

  // Linked, the host app runs and connects. The machine is pending: it is not offered work until it's activated.
  agents.push(
    spawn(
      agentBinary,
      [
        "--config",
        config,
        "run",
        "--fake-gpu",
        "--fake-gpu-model",
        "RTX 4060 Laptop",
        "--fake-gpu-vram-mb",
        "8188",
        "--fake-free-disk-mb",
        "200000",
        "--dev-runner",
      ],
      { stdio: "ignore", env: { ...process.env, IDLEO_LOG: "info" } },
    ),
  );
  await page.goto("/hosting/");
  const pending = page.getByRole("link", { name: /Journey laptop/ });
  await expect(pending.locator("xpath=..")).toContainText(/waiting for our checks/i, { timeout: 60_000 });
  await expectAccessible(page);

  // The check an operator does by hand until onboarding's benchmark exists.
  await run(process.env.E2E_ACTIVATE_HOST!, [hostId], { timeout: 300_000 });
  await page.goto(`/hosting/machine/?id=${hostId}`);
  await expect(page.getByTestId("machine-chip")).toHaveText("Idle", { timeout: 30_000 });
  await expect(page.getByRole("heading", { level: 1, name: "Journey laptop" })).toBeVisible();
  await expect(page.getByText(/^Online since/)).toBeVisible();
  await expectAccessible(page);
  await page.getByRole("link", { name: "Hosting", exact: true }).first().click();
  // The laptop's machine tile: its state, then its GPU and price.
  const tile = page.getByRole("link", { name: /Journey laptop/ });
  await expect(tile).toContainText("Idle");
  await expect(tile).toContainText("RTX 4060 Laptop · 8 GB");
  numbers.addedMachine = { hostId, name: "Journey laptop" };
  report();
});

test("pauses it and resumes it, and one host fewer fits while it's paused", async ({ page, request }) => {
  const before = await quote(request);
  await startAt(page, `/hosting/machine/?id=${hostId}`);
  await page.getByRole("button", { name: "Pause hosting" }).click();
  await page.getByLabel("1 hour").check();
  await page.getByRole("button", { name: "Pause hosting" }).click();
  await expect(page.getByTestId("machine-chip")).toHaveText("Paused");
  await expect(page.getByText(/^Paused until .*A session that's running finishes first\./)).toBeVisible();
  await expectAccessible(page);
  await expect.poll(async () => (await quote(request)).hostsFitNow).toBe(before.hostsFitNow - 1);
  await page.getByRole("button", { name: "Resume" }).click();
  await expect(page.getByTestId("machine-chip")).toHaveText("Idle");
  await expect.poll(async () => (await quote(request)).hostsFitNow).toBe(before.hostsFitNow);
  numbers.pause = { hostsFitBefore: before.hostsFitNow, hostsFitWhilePaused: before.hostsFitNow - 1 };
  report();
});

test("changes its price, and the quote's range moves with it", async ({ page, request }) => {
  await startAt(page, `/hosting/price/?id=${hostId}`);
  const priceText = (
    await page
      .getByRole("status")
      .filter({ hasText: /^Price:/ })
      .innerText()
  ).replace(/\s+/g, " ");
  const rupees = /₹(\d+)\.(\d\d)/.exec(priceText)!;
  const hourly = Number(rupees[1]) * 100 + Number(rupees[2]);
  const price = hourly / 60;
  expect(Number.isInteger(price)).toBe(true);
  const before = await quote(request);
  // Its price is the highest on the stack (the others ask 2 paise a minute), so it's the top of the range.
  expect(before.pricePaisePerMin?.max).toBe(price);
  for (let step = 0; step < 3; step++) {
    await page.getByRole("button", { name: "Raise the price by ₹0.60 an hour" }).click();
  }
  await expect(page.getByText(`₹${(((price + 3) * 60 * 0.8) / 100).toFixed(2)} an hour`)).toBeVisible();
  await page.getByRole("button", { name: "Save" }).click();
  await expect(page).toHaveURL(/\/hosting\/machine\/\?id=/);
  await expect(page.getByRole("link", { name: /Price and limits/ })).toContainText(
    `₹${((price + 3) * 0.6).toFixed(2)} an hour`,
  );
  await expect.poll(async () => (await quote(request)).pricePaisePerMin?.max).toBe(price + 3);
  numbers.price = {
    before: price,
    after: price + 3,
    quoteMaxBefore: before.pricePaisePerMin?.max,
    quoteMaxAfter: price + 3,
  };
  report();
});

test("earns from a session, and the Hosting tab, earnings and sessions it ran agree with the ledger", async ({
  page,
  request,
}) => {
  // The cheapest host on the stack, so the session comes here: 1 paisa a minute.
  const saved = await request.put(`${API}/v1/hosts/${hostId}/listing`, {
    headers: await authorised(request),
    data: { batchPricePaisePerMin: 1, gpuTempCapC: 85, maxConcurrentSessions: 1, keepFreeGb: 10 },
  });
  expect(saved.status()).toBe(200);
  const booked = await request.post(`${API}/v1/sessions`, {
    headers: { ...(await authorised(request)), "Idempotency-Key": `${user}-hosting-journey` },
    data: { templateId: "charged-run", maxSpendPaise: 10_000 },
  });
  expect(booked.status()).toBe(201);
  const { id } = (await booked.json()) as { id: string };

  await startAt(page, `/hosting/machine/?id=${hostId}`);
  await expect(page.getByTestId("machine-chip")).toHaveText("Running a session", { timeout: 90_000 });
  expect((await getSession(request, id)).hostId).toBe(hostId);
  await expect(page.getByText(/earned so far · you keep 80%/)).toBeVisible();
  await expectAccessible(page);

  await expect.poll(async () => (await getSession(request, id)).settled, { timeout: 300_000 }).toBe(true);
  const session = await getSession(request, id);
  // 150 s is 3 started minutes at 1 paisa: charged 3, of which the host keeps 2 (80%, rounded down).
  expect(session.chargedPaise).toBe(3);
  await expect.poll(async () => (await earnings(request)).totalPaise, { timeout: 60_000 }).toBe(2);
  const all = await earnings(request);
  expect(all.weekPaise).toBe(2);
  expect(all.history.entries[0]).toMatchObject({ amountPaise: 2, minutes: 3, machineName: "Journey laptop" });

  await page.goto("/hosting/");
  await expect(page.getByText("₹0.02").first()).toBeVisible();
  await expect(page.getByText(/in total ·/)).toContainText("₹0.02");
  await expect(page.getByText(/Weekly UPI payouts start with the paid pilot/)).toBeVisible();
  await expectAccessible(page);
  await page.getByRole("link", { name: /^All/ }).click();
  await expect(page.getByRole("main")).toContainText("Journey laptop");
  await expect(page.getByRole("main")).toContainText("+₹0.02");
  await expect(page.getByRole("main")).toContainText("3 min");
  await expectAccessible(page);
  await page.goto(`/hosting/sessions/?id=${hostId}`);
  const row = page.getByRole("main").getByRole("listitem").first();
  await expect(row).toContainText("3 min");
  await expect(row).toContainText("+₹0.02");
  await expect(row).toContainText("Completed");
  await expect(page.getByRole("main")).not.toContainText(user);
  await expectAccessible(page);
  numbers.earnings = {
    sessionId: id,
    chargedPaise: session.chargedPaise,
    totalPaise: all.totalPaise,
    weekPaise: all.weekPaise,
  };
  report();
});

test("removes it, which signs its agent out and keeps what it earned", async ({ page, request }) => {
  await startAt(page, `/hosting/machine/?id=${hostId}`);
  await page.getByRole("button", { name: /Rename or remove/ }).click();
  await page.getByRole("button", { name: "Remove this machine" }).click();
  await expect(page.getByRole("dialog", { name: "Remove Journey laptop?" })).toBeVisible();
  await expectAccessible(page);
  await page.getByRole("button", { name: "Remove Journey laptop" }).click();
  await expect(page).toHaveURL(/\/hosting\/$/);
  await expect(page.getByText("Earn from the GPU you aren't using")).toBeVisible();
  const gone = await request.get(`${API}/v1/hosts/${hostId}`, { headers: await authorised(request) });
  expect(gone.status()).toBe(410);
  expect(((await gone.json()) as { type: string }).type).toBe("/problems/host-removed");
  expect((await earnings(request)).totalPaise).toBe(2);
  await page.goto("/hosting/earnings/");
  await expect(page.getByRole("main")).toContainText("₹0.02");
  numbers.removed = { hostId, earningsKept: 2 };
  report();
});
