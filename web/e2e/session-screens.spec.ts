import { expect, test } from "@playwright/test";
import * as data from "./fixtures";
import { capture, expectAccessible, fakeApi, signIn, watchConsole, type Answer } from "./support";

/**
 * The live session and All sessions in every state (web/DESIGN.md "States"), light and dark, phone and laptop. The
 * API answers from fixtures; the event stream sends one snapshot and closes, so the screen reopens it now and then.
 */
type Session = data.Session;

const snapshot = (session: Session) => ({ events: `event: session\ndata: ${JSON.stringify(session)}\n\n` });

function api(session: Session, overrides: Record<string, Answer> = {}): Record<string, Answer> {
  return {
    "/v1/me": { json: data.me() },
    "/v1/templates": { json: data.templates },
    "/v1/wallet": { json: data.asha.wallet },
    "/v1/sessions/[^/]+/events": snapshot(session),
    "/v1/sessions/[^/]+/logs": {
      json: { chunks: data.logChunks(session), nextCursor: "after-6", complete: session.settled },
    },
    "/v1/sessions/[^/]+/outputs": { json: data.outputs },
    "/v1/sessions/[^/]+": { json: session },
    "/v1/uploads/[^/]+": { json: data.sessionUpload },
    "/v1/sessions": { json: data.sessionsPage(data.asha.sessions) },
    ...overrides,
  };
}

let checkConsole: () => void;
test.beforeEach(async ({ page }) => {
  checkConsole = watchConsole(page);
  await page.clock.setFixedTime(data.NOW);
});
test.afterEach(() => checkConsole());

const open = (session: Session) => `/session/?id=${session.id}`;

test.describe("a session", () => {
  test("running, live, with its logs and checkpoints", async ({ page }, info) => {
    await fakeApi(page, api(data.running));
    await signIn(page, "asha", open(data.running));
    await expect(page.getByRole("heading", { name: "Fine-tuning on an RTX 4080 Laptop" })).toBeVisible();
    await expect(page.getByText("Started 22 min ago")).toBeVisible();
    await expect(page.locator("[data-pulse]")).toBeVisible();
    await expect(page.getByText("of about 45 min")).toBeVisible();
    await expect(page.getByText("₹3.08").first()).toBeVisible();
    await expect(page.getByText("RTX 4080 Laptop · 12 GB")).toBeVisible();
    await expect(page.getByText("97% busy · 71°C")).toBeVisible();
    await expect(page.getByText(/^Last checkpoint saved/)).toBeVisible();
    await expect(page.getByRole("button", { name: "Stop session" })).toBeVisible();
    await expect(page.getByText(/Live, updated/)).toBeVisible();
    if (info.project.name.includes("laptop")) {
      await expect(page.getByText("Host found")).toBeVisible();
      await expect(page.getByText("46 MB")).toBeVisible();
      await expect(page.getByTestId("log-panel")).toContainText("loading the model in 4-bit");
    } else {
      await expect(page.getByRole("button", { name: /^Logs/ })).toHaveAttribute("aria-expanded", "false");
    }
    await expectAccessible(page);
    await capture(page, info, "session-running");
  });

  test("running, with the logs open on a phone", async ({ page }, info) => {
    test.skip(info.project.name.includes("laptop"), "the logs are open on a laptop already");
    await fakeApi(page, api(data.running));
    await signIn(page, "asha", open(data.running));
    await page.getByRole("button", { name: /^Logs/ }).click();
    await expect(page.getByTestId("log-panel")).toContainText("epoch 1 step");
    await expectAccessible(page);
    await capture(page, info, "session-running-logs");
  });

  test("asking before it stops", async ({ page }, info) => {
    const finishing: Session = {
      ...data.running,
      state: "FINISHING",
      events: [
        ...data.running.events,
        { state: "FINISHING", reason: "renter_stopped", at: data.NOW.toISOString() },
      ],
    };
    // Once stopped, the server says so everywhere, as the real one would: the stream reopens with the new state.
    let stopped = false;
    await fakeApi(
      page,
      api(data.running, {
        "/v1/sessions/[^/]+/stop": () => {
          stopped = true;
          return { json: finishing };
        },
        "/v1/sessions/[^/]+/events": () => snapshot(stopped ? finishing : data.running),
        "/v1/sessions/[^/]+": () => ({ json: stopped ? finishing : data.running }),
      }),
    );
    await signIn(page, "asha", open(data.running));
    await page.getByRole("button", { name: "Stop session" }).click();
    const dialog = page.getByRole("dialog", { name: "Stop this session?" });
    await expect(dialog).toContainText(
      "It saves a checkpoint, then stops. Nothing more is charged once it has stopped",
    );
    // On a phone the dialog's button opens under the finger that pressed Stop: hovered, it must keep its contrast.
    await dialog.getByRole("button", { name: "Stop the session" }).hover();
    await expectAccessible(page);
    await page.mouse.move(0, 0);
    await capture(page, info, "session-stop-dialog");
    await dialog.getByRole("button", { name: "Stop the session" }).click();
    await expect(dialog).toBeHidden();
    await expect(page.getByText("Saving a last checkpoint and its outputs…")).toBeVisible();
  });

  test("finding its GPU", async ({ page }, info) => {
    await fakeApi(page, api(data.finding));
    await signIn(page, "asha", open(data.finding));
    await expect(page.getByRole("heading", { name: "Finding your GPU" })).toBeVisible();
    await expect(page.getByText("Usually under a minute.", { exact: false })).toBeVisible();
    await expect(page.getByText("dataset.jsonl uploaded")).toBeVisible();
    await expect(page.getByText("Nothing is charged until it runs")).toBeVisible();
    await expect(page.getByRole("button", { name: "Cancel request" })).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "session-finding");
  });

  test("cancelled while finding its GPU: nothing charged", async ({ page }, info) => {
    // On the real stack a free host takes a request within a second, so this is the screen's side; the control
    // plane's tests cancel in every state that can be cancelled.
    const cancelled: Session = {
      ...data.finding,
      state: "CANCELLED",
      settled: true,
      reservedPaise: 0,
      endedAt: data.NOW.toISOString(),
      events: [
        ...data.finding.events,
        { state: "CANCELLED", reason: "renter_cancelled", at: data.NOW.toISOString() },
      ],
    };
    let cancelledNow = false;
    await fakeApi(
      page,
      api(data.finding, {
        "/v1/sessions/[^/]+/cancel": () => {
          cancelledNow = true;
          return { json: cancelled };
        },
        "/v1/sessions/[^/]+/events": () => snapshot(cancelledNow ? cancelled : data.finding),
        "/v1/sessions/[^/]+": () => ({ json: cancelledNow ? cancelled : data.finding }),
      }),
    );
    await signIn(page, "asha", open(data.finding));
    await page.getByRole("button", { name: "Cancel request" }).click();
    await expect(page.getByRole("heading", { name: "Cancelled" })).toBeVisible();
    await expect(page.getByText("You cancelled it before it started.")).toBeVisible();
    await expect(page.getByText("Nothing was charged")).toBeVisible();
    await expect(page.getByRole("link", { name: "Try again" })).toHaveAttribute(
      "href",
      /template=echo-input&from=/,
    );
    await expectAccessible(page);
    await capture(page, info, "session-cancelled-while-finding");
  });

  test("its GPU found, starting", async ({ page }, info) => {
    await fakeApi(page, api(data.found));
    await signIn(page, "asha", open(data.found));
    await expect(page.getByRole("heading", { name: "GPU found" })).toBeVisible();
    await expect(page.getByText("RTX 4080 Laptop · 12 GB")).toBeVisible();
    await expect(page.getByText("Starting…")).toBeVisible();
    await expect(page.getByRole("button", { name: "Cancel request" })).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "session-found");
  });

  test("moved to another host", async ({ page }, info) => {
    await fakeApi(page, api(data.movedLive));
    await signIn(page, "asha", open(data.movedLive));
    await expect(
      page.getByText(
        /Moved to another host and resumed from checkpoint \d+\. The lost minutes aren't charged\./,
      ),
    ).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "session-moved");
  });

  test("with credit running low", async ({ page }, info) => {
    await fakeApi(page, api(data.lowCreditRunning, { "/v1/wallet": { json: data.low.wallet } }));
    await signIn(page, "asha", open(data.lowCreditRunning));
    await expect(page.getByText("Your credit is running low.", { exact: false })).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "session-low-credit");
  });

  test("when its live updates stop", async ({ page }, info) => {
    await fakeApi(page, api(data.running, { "/v1/sessions/[^/]+/events": { status: 503 } }));
    await signIn(page, "asha", open(data.running));
    await expect(
      page.getByText(
        /Live updates stopped\. Showing figures from .*, checked every 5 seconds\. Reconnecting…/,
      ),
    ).toBeVisible();
    await expect(page.getByText(/Live, updated/)).toHaveCount(0);
    await expectAccessible(page);
    await capture(page, info, "session-stream-down");
  });

  test("done, with its outputs", async ({ page }, info) => {
    await fakeApi(page, api(data.completed));
    await signIn(page, "asha", open(data.completed));
    await expect(page.getByRole("heading", { name: "Done" })).toBeVisible();
    await expect(page.getByRole("link", { name: /result\.json/ })).toBeVisible();
    await expect(page.getByRole("link", { name: "Download outputs" })).toBeVisible();
    await expect(page.getByRole("link", { name: "Run again" })).toHaveAttribute("href", /from=/);
    await expect(page.getByText("Charged", { exact: true })).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "session-completed");
  });

  test("failed, with why in plain words", async ({ page }, info) => {
    await fakeApi(page, api(data.failed));
    await signIn(page, "asha", open(data.failed));
    await expect(page.getByRole("heading", { name: "It stopped with an error" })).toBeVisible();
    await expect(page.getByText("The job ran out of memory.")).toBeVisible();
    await expect(page.getByRole("link", { name: "Try again" })).toHaveAttribute("href", /from=/);
    await expectAccessible(page);
    await capture(page, info, "session-failed");
  });

  test("when no GPU took it", async ({ page }, info) => {
    await fakeApi(page, api(data.noHostFree));
    await signIn(page, "asha", open(data.noHostFree));
    await expect(page.getByRole("heading", { name: "No GPU was free" })).toBeVisible();
    await expect(page.getByText("Nothing was charged")).toBeVisible();
    await expect(page.getByRole("link", { name: "Try again" })).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "session-no-host");
  });

  test("cancelled before it started", async ({ page }, info) => {
    await fakeApi(page, api(data.cancelled));
    await signIn(page, "asha", open(data.cancelled));
    await expect(page.getByRole("heading", { name: "Cancelled" })).toBeVisible();
    await expect(page.getByText("You cancelled it before it started.")).toBeVisible();
    await expect(page.getByText("Nothing was charged")).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "session-cancelled");
  });

  test("while loading", async ({ page }, info) => {
    await fakeApi(
      page,
      api(data.running, { "/v1/sessions/[^/]+": "hold", "/v1/sessions/[^/]+/events": "hold" }),
    );
    await signIn(page, "asha", open(data.running));
    await expect(page.getByLabel("Loading this session")).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "session-loading");
  });

  test("not found", async ({ page }, info) => {
    await fakeApi(
      page,
      api(data.running, {
        "/v1/sessions/[^/]+/events": { status: 404 },
        "/v1/sessions/[^/]+": { status: 404 },
      }),
    );
    await signIn(page, "asha", "/session/?id=01a11300-0000-7000-8000-00000000dead");
    await expect(page.getByText(/couldn't find this session/)).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "session-not-found");
  });
});

test.describe("all sessions", () => {
  const list = (overrides: Record<string, Answer> = {}) =>
    api(data.running, {
      "/v1/sessions": (_call, request) => {
        const url = new URL(request.url());
        return {
          json: data.sessionsPage(data.asha.sessions, {
            active: url.searchParams.get("active") === "true",
            cursor: url.searchParams.get("cursor"),
          }),
        };
      },
      ...overrides,
    });

  test("active", async ({ page }, info) => {
    await fakeApi(page, list());
    await signIn(page, "asha", "/sessions/");
    await expect(page.getByRole("link", { name: /QLoRA fine-tune.*Running/ })).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "sessions-active");
  });

  test("ended, a page at a time", async ({ page }, info) => {
    await fakeApi(page, list());
    await signIn(page, "asha", "/sessions/?show=ended");
    await expect(page.getByRole("button", { name: "Show more" })).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "sessions-ended");
    await page.getByRole("button", { name: "Show more" }).click();
    await expect(page.getByRole("button", { name: "Show more" })).toHaveCount(0);
    await expect(page.locator("main li")).toHaveCount(data.asha.sessions.length - 1);
  });

  test("with nothing running", async ({ page }, info) => {
    await fakeApi(page, list({ "/v1/sessions": { json: { sessions: [], nextCursor: null } } }));
    await signIn(page, "asha", "/sessions/");
    await expect(page.getByText("Nothing running")).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "sessions-empty");
  });

  test("while loading", async ({ page }, info) => {
    await fakeApi(page, list({ "/v1/sessions": "hold" }));
    await signIn(page, "asha", "/sessions/");
    await expect(page.getByLabel("Loading your sessions")).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "sessions-loading");
  });

  test("when they can't load", async ({ page }, info) => {
    await fakeApi(page, list({ "/v1/sessions": { status: 503 } }));
    await signIn(page, "asha", "/sessions/");
    await expect(page.getByText(/couldn't load your sessions/)).toBeVisible({ timeout: 15_000 });
    await expectAccessible(page);
    await capture(page, info, "sessions-error");
  });
});
