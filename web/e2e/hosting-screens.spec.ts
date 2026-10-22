import { expect, test, type Page } from "@playwright/test";
import * as data from "./fixtures";
import * as hosting from "./fixtures-hosting";
import * as trust from "./fixtures-trust";
import { hostingApi } from "./hosting-api";
import { capture, expectAccessible, fakeApi, onPhone, signIn, watchConsole, type Answer } from "./support";

/**
 * The hosting screens (ADR 0023) in every state, light and dark, phone and laptop: the Hosting tab, a machine in each
 * state, its price and limits, the sessions it ran, earnings, and the four steps of adding one. The API answers from
 * fixtures (e2e/fixtures-hosting.ts), whose figures add up.
 */
function api(overrides: Record<string, Answer> = {}): Record<string, Answer> {
  return {
    "/v1/me": { json: data.me() },
    "/v1/templates": { json: data.templates },
    "/v1/wallet": { json: data.asha.wallet },
    "/v1/sessions": { json: data.sessionsPage(data.asha.sessions) },
    ...hostingApi(),
    ...overrides,
  };
}

let checkConsole: () => void;
test.beforeEach(async ({ page }) => {
  checkConsole = watchConsole(page);
  await page.clock.setFixedTime(data.NOW);
});
test.afterEach(() => checkConsole());

const open = async (page: Page, path: string, overrides: Record<string, Answer> = {}) => {
  await fakeApi(page, api(overrides));
  await signIn(page, "asha", path);
};

test.describe("the Hosting tab", () => {
  test("with a running machine and a paused one", async ({ page }, info) => {
    await open(page, "/hosting/");
    await expect(page.getByText("Earned this week")).toBeVisible();
    if (onPhone(info)) {
      await expect(page.getByText("in total · payouts start with the paid pilot")).toBeVisible();
    } else {
      await expect(page.getByText("in total ·")).toBeVisible();
      await expect(page.getByText("Weekly UPI payouts start with the paid pilot.")).toBeVisible();
    }
    await expect(page.getByRole("list", { name: "Earned each day, the last 7 days" })).toBeVisible();
    const rog = page.getByRole("link", { name: /Nikki's ROG Strix/ });
    await expect(rog).toContainText(
      onPhone(info) ? "Running a session · earning ₹2.59" : "Running a session",
    );
    // On a laptop the earning is the foot of the machine's tile, beside the link into it.
    await expect(onPhone(info) ? rog : rog.locator("xpath=..")).toContainText("₹2.59");
    await expect(page.getByRole("link", { name: /Home desktop/ })).toContainText("Paused until 6 pm");
    await expect(page.getByRole("link", { name: "Add a machine" })).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "hosting");
  });

  test("with none of the states hidden: offline, update needed, idle and waiting for checks", async ({
    page,
  }, info) => {
    await open(page, "/hosting/", {
      "/v1/hosts": {
        json: {
          hosts: [
            hosting.hostIdle,
            hosting.hostOffline,
            hosting.hostUpdate,
            { ...hosting.hostNew, listing: hosting.hostIdle.listing },
          ],
        },
      },
    });
    await expect(page.getByRole("link", { name: /Studio PC/ })).toContainText(
      onPhone(info) ? "Idle · RTX 4080 Laptop · 12 GB" : "RTX 4080 Laptop · 12 GB",
    );
    await expect(page.getByRole("link", { name: /Studio PC/ })).toContainText("Idle");
    await expect(page.getByRole("link", { name: /Spare laptop/ })).toContainText("Offline");
    // On a laptop what each can't do yet is the foot of its tile, beside the link into it.
    const tileOf = (name: RegExp) => {
      const link = page.getByRole("link", { name });
      return onPhone(info) ? link : link.locator("xpath=..");
    };
    await expect(tileOf(/Office PC/)).toContainText("Update the host app to get work");
    await expect(tileOf(/My new machine/)).toContainText("waiting for our checks");
    await expectAccessible(page);
    await capture(page, info, "hosting-states");
  });

  test("with no machines: what hosting is, and the one step", async ({ page }, info) => {
    await open(page, "/hosting/", {
      "/v1/hosts": { json: hosting.noHosts },
      "/v1/earnings": { json: hosting.noEarnings },
    });
    await expect(page.getByText("Earn from the GPU you aren't using")).toBeVisible();
    await expect(page.getByRole("link", { name: "Add a machine" })).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "hosting-empty");
  });

  test("loading, then an error with a retry", async ({ page }, info) => {
    await open(page, "/hosting/", { "/v1/hosts": "hold", "/v1/earnings": "hold" });
    await expect(page.getByRole("status", { name: "Loading your machines" })).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "hosting-loading");
    await page.unrouteAll({ behavior: "ignoreErrors" });
    await fakeApi(page, api({ "/v1/hosts": { status: 503 }, "/v1/earnings": { status: 503 } }));
    await page.reload();
    await expect(page.getByRole("button", { name: "Try again" }).first()).toBeVisible({ timeout: 15_000 });
    await expectAccessible(page);
    await capture(page, info, "hosting-error");
  });

  test("with stale figures after a failed refresh", async ({ page }, info) => {
    let failures = 0;
    await open(page, "/hosting/", {
      "/v1/hosts": (call) => {
        if (call === 1) {
          return { json: hosting.hosts };
        }
        failures++;
        return { status: 503 };
      },
    });
    await expect.poll(() => failures, { timeout: 30_000 }).toBeGreaterThan(0);
    await expect(page.getByText(/Couldn't refresh/)).toHaveCount(1);
    await expect(page.getByRole("link", { name: /Nikki's ROG Strix/ })).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "hosting-stale");
  });
});

test.describe("one machine", () => {
  const at = (host: hosting.Host) => `/hosting/machine/?id=${host.id}`;

  test("running a session: what it's earned so far, its temperature against the limit", async ({
    page,
  }, info) => {
    await open(page, at(hosting.hostRunning));
    await expect(page.getByRole("heading", { level: 1, name: "Nikki's ROG Strix" })).toBeVisible();
    await expect(page.getByTestId("machine-chip")).toHaveText("Running a session");
    // What 23 renters rated it, as a figure and never who (ADR 0028).
    // A screen reader hears "Rated 4.6 out of 5 from 20+ ratings"; the screen shows the star, 4.6 and the count.
    await expect(page.getByText(/4\.6 out of 5 from 20\+ ratings/)).toBeVisible();
    await expect(page.getByText(/Ratings appear after/)).toHaveCount(0);
    await expect(page.getByText("RTX 4080 Laptop · 12 GB")).toBeVisible();
    await expect(page.getByText(/^69°C of 85°C limit · 93% busy/)).toBeVisible();
    await expect(page.getByText("earned so far · you keep 80%")).toBeVisible();
    await expect(
      page.getByText("Renters never see who you are, and their work never touches your files."),
    ).toBeVisible();
    // How much of the owner's upload a job may use (ADR 0038): half of what the host app measured.
    await expect(
      page.getByText("Jobs use at most 25 Mbit/s of your upload, so your own internet stays usable."),
    ).toBeVisible();
    await expect(page.getByRole("link", { name: /Price and limits/ })).toContainText(
      "₹10.80 an hour · stops above 85°C",
    );
    await expect(page.getByRole("button", { name: "Pause hosting" })).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "machine-running");
  });

  test("idle, with nothing to show under Right now", async ({ page }, info) => {
    await open(page, at(hosting.hostIdle));
    await expect(page.getByText(/Nothing is running/)).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "machine-idle");
  });

  test("paused: when it ends, and Resume", async ({ page }, info) => {
    await open(page, at(hosting.hostPaused));
    // Too few ratings to show an average.
    await expect(page.getByText("Ratings appear after 5 sessions.")).toBeVisible();
    // No speed check has reached the server yet for this one.
    await expect(page.getByText(/Jobs use only a little of your upload/)).toBeVisible();
    await expect(
      page.getByText(/Paused until 6 pm\. A session that's running finishes first\./),
    ).toBeVisible();
    await expect(page.getByRole("button", { name: "Resume" })).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "machine-paused");
  });

  test("offline, and one whose host app needs an update", async ({ page }, info) => {
    await open(page, at(hosting.hostOffline));
    await expect(page.getByText(/^Offline · last seen/)).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "machine-offline");
    await page.goto(at(hosting.hostUpdate));
    await expect(page.getByText(/host app needs an update/)).toBeVisible();
    await expect(page.getByTestId("machine-chip")).toHaveText("Update needed");
    await expectAccessible(page);
    await capture(page, info, "machine-update");
  });

  test("pausing: a choice of how long, then the machine is paused", async ({ page }, info) => {
    let body: unknown = null;
    // The server remembers: a read after the pause (a refetch, a refocus) says it's paused too.
    let current: hosting.Host = hosting.hostIdle;
    await open(page, at(hosting.hostIdle), {
      "/v1/hosts/[^/]+/pause": (_call, request) => {
        body = request.postDataJSON();
        current = { ...hosting.hostIdle, paused: true, pausedUntil: (body as { until: string }).until };
        return { json: current };
      },
      "/v1/hosts/[^/]+": () => ({ json: current }),
    });
    await page.getByRole("button", { name: "Pause hosting" }).click();
    await expect(page.getByRole("group", { name: "Pause for" })).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "machine-pause-choices");
    await page.getByLabel("Until tonight").check();
    await page.getByRole("button", { name: "Pause hosting" }).click();
    await expect(page.getByText(/Paused until 6 pm/)).toBeVisible();
    expect(body).toEqual({ until: "2026-10-07T12:30:00.000Z" });
  });

  test("resuming", async ({ page }) => {
    let current: hosting.Host = hosting.hostPaused;
    await open(page, at(hosting.hostPaused), {
      "/v1/hosts/[^/]+/resume": () => {
        current = { ...hosting.hostPaused, paused: false, pausedUntil: null };
        return { json: current };
      },
      "/v1/hosts/[^/]+": () => ({ json: current }),
    });
    await page.getByRole("button", { name: "Resume" }).click();
    await expect(page.getByRole("button", { name: "Pause hosting" })).toBeVisible();
  });

  test("renaming, and removing, which asks first in a sheet that names it", async ({ page }, info) => {
    let removed = false;
    await open(page, at(hosting.hostIdle), {
      "/v1/hosts": () => ({ json: removed ? hosting.noHosts : { hosts: [hosting.hostIdle] } }),
      "/v1/hosts/[^/]+": (_call, request) => {
        if (request.method() === "DELETE") {
          removed = true;
          return { json: {}, status: 204 };
        }
        if (request.method() === "PATCH") {
          return { json: { ...hosting.hostIdle, name: (request.postDataJSON() as { name: string }).name } };
        }
        return { json: hosting.hostIdle };
      },
    });
    await page.getByRole("button", { name: /Rename or remove/ }).click();
    await expect(page.getByLabel("Name", { exact: true })).toHaveValue("Studio PC");
    await expectAccessible(page);
    await capture(page, info, "machine-manage");
    await page.getByLabel("Name", { exact: true }).fill("Desk PC");
    await page.getByRole("button", { name: "Save name" }).click();
    await expect(page.getByRole("heading", { level: 1, name: "Desk PC" })).toBeVisible();
    await page.getByRole("button", { name: "Remove this machine" }).click();
    await expect(page.getByRole("dialog", { name: "Remove Desk PC?" })).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "machine-remove");
    await page.getByRole("button", { name: "Remove Desk PC" }).click();
    await expect(page).toHaveURL(/\/hosting\/$/);
  });

  test("removing one that's running a session is refused, in words", async ({ page }, info) => {
    await open(page, at(hosting.hostRunning), {
      "/v1/hosts/[^/]+": (_call, request) =>
        request.method() === "DELETE" ? { status: 409, problem: "host-busy" } : { json: hosting.hostRunning },
    });
    await page.getByRole("button", { name: /Rename or remove/ }).click();
    await page.getByRole("button", { name: "Remove this machine" }).click();
    await page.getByRole("button", { name: "Remove Nikki's ROG Strix" }).click();
    await expect(page.getByRole("alert")).toContainText("This machine is running a session.");
    await expectAccessible(page);
    await capture(page, info, "machine-remove-busy");
  });

  test("loading, an error with a retry, and one that was removed", async ({ page }, info) => {
    await open(page, at(hosting.hostIdle), { "/v1/hosts/[^/]+": "hold" });
    await expect(page.getByRole("status", { name: "Loading this machine" })).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "machine-loading");
    await page.unrouteAll({ behavior: "ignoreErrors" });
    await fakeApi(page, api({ "/v1/hosts/[^/]+": { status: 503 } }));
    await page.reload();
    await expect(page.getByRole("button", { name: "Try again" })).toBeVisible({ timeout: 15_000 });
    await expectAccessible(page);
    await capture(page, info, "machine-error");
    await page.unrouteAll({ behavior: "ignoreErrors" });
    await fakeApi(page, api({ "/v1/hosts/[^/]+": { status: 410, problem: "host-removed" } }));
    await page.reload();
    await expect(page.getByText("This machine isn't here")).toBeVisible({ timeout: 15_000 });
    await expectAccessible(page);
  });
});

test.describe("price and limits", () => {
  const at = (host: hosting.Host) => `/hosting/price/?id=${host.id}`;

  test("the price in ₹0.60 steps, what you keep, the range others charge, the limits", async ({
    page,
  }, info) => {
    await open(page, at(hosting.hostRunning));
    await expect(page.getByText("₹10.80")).toBeVisible();
    await expect(page.getByText(/^You keep₹8\.64 an hour/)).toBeVisible();
    await expect(page.getByText(/Platform fee \(20%\)₹2\.16/)).toBeVisible();
    await expect(page.getByText(/Other RTX 4080 Laptops charge ₹9\.00–12\.00 an hour/)).toBeVisible();
    await expect(page.getByLabel("Pause above this temperature")).toHaveValue("85");
    await expect(page.getByLabel("Keep free on my disk (GB)")).toHaveValue("20");
    await expect(page.getByText("One session at a time, so your laptop stays usable.")).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "price");
  });

  test("stepping the price, and saving it", async ({ page }) => {
    let body: unknown = null;
    await open(page, at(hosting.hostRunning), {
      "/v1/hosts/[^/]+/listing": (_call, request) => {
        body = request.postDataJSON();
        return { json: body };
      },
    });
    await page.getByRole("button", { name: "Raise the price by ₹0.60 an hour" }).click();
    await page.getByRole("button", { name: "Raise the price by ₹0.60 an hour" }).click();
    await expect(page.getByText("₹12.00")).toBeVisible();
    await expect(page.getByText(/^You keep₹9\.60 an hour/)).toBeVisible();
    await page.getByRole("button", { name: "Save" }).click();
    await expect(page).toHaveURL(/\/hosting\/machine\/\?id=/);
    expect(body).toEqual({
      batchPricePaisePerMin: 20,
      gpuTempCapC: 85,
      maxConcurrentSessions: 1,
      keepFreeGb: 20,
    });
  });

  test("a disk to keep free that's out of range is said in words", async ({ page }, info) => {
    await open(page, at(hosting.hostRunning));
    await page.getByLabel("Keep free on my disk (GB)").fill("5");
    await expect(page.getByText("Choose from 10 to 500 GB.")).toBeVisible();
    await expect(page.getByRole("button", { name: "Save" })).toBeDisabled();
    await expectAccessible(page);
    await capture(page, info, "price-invalid");
  });

  test("with too few others to compare, the usual range for its GPU", async ({ page }, info) => {
    await open(page, at(hosting.hostRunning), {
      "/v1/hosts/[^/]+/price-band": { json: hosting.priceBandDefault },
    });
    await expect(
      page.getByText(/Hosts with a GPU like yours usually charge ₹7\.20–12\.00 an hour/),
    ).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "price-default-band");
  });

  test("a refusal from the server is said in words", async ({ page }) => {
    await open(page, at(hosting.hostRunning), {
      "/v1/hosts/[^/]+/listing": { status: 410, problem: "host-removed" },
    });
    await page.getByRole("button", { name: "Save" }).click();
    await expect(page.getByRole("alert")).toContainText("This machine was removed.");
  });
});

test.describe("sessions it ran", () => {
  const at = (host: hosting.Host) => `/hosting/sessions/?id=${host.id}`;

  test("what ran, how long, what it earned and how it ended, never who for", async ({ page }, info) => {
    await open(page, at(hosting.hostRunning));
    const rows = page.getByRole("listitem");
    await expect(rows.first()).toContainText("Fine-tuning · 18 min");
    await expect(page.getByRole("main")).toContainText("Lost, ₹0.57 given back");
    await expect(page.getByRole("main")).toContainText("Failed");
    await expectAccessible(page);
    await capture(page, info, "host-sessions");
  });

  test("nothing yet, loading, and an error with a retry", async ({ page }, info) => {
    await open(page, at(hosting.hostIdle), {
      "/v1/hosts/[^/]+/sessions": { json: { sessions: [], nextCursor: null } },
    });
    await expect(page.getByText("Nothing has run here yet")).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "host-sessions-empty");
    await page.unrouteAll({ behavior: "ignoreErrors" });
    await fakeApi(page, api({ "/v1/hosts/[^/]+/sessions": "hold" }));
    await page.reload();
    await expect(page.getByRole("status", { name: "Loading the sessions it ran" })).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "host-sessions-loading");
    await page.unrouteAll({ behavior: "ignoreErrors" });
    await fakeApi(page, api({ "/v1/hosts/[^/]+/sessions": { status: 503 } }));
    await page.reload();
    await expect(page.getByRole("button", { name: "Try again" })).toBeVisible({ timeout: 15_000 });
    await expectAccessible(page);
    await capture(page, info, "host-sessions-error");
  });
});

test.describe("earnings", () => {
  test("the totals, then every credit and giveback, newest first", async ({ page }, info) => {
    await open(page, "/hosting/earnings/");
    await expect(page.getByText("During the free beta your earnings are recorded here.")).toBeVisible();
    await expect(page.getByText("Hours rented")).toBeVisible();
    await expect(page.getByText("Given back for lost time")).toBeVisible();
    await expect(page.getByRole("list", { name: "Each machine" })).toContainText("Old laptop (removed)");
    await expectAccessible(page);
    await capture(page, info, "earnings");
  });

  test("a refund to a renter is a line of its own, and a held session says so", async ({ page }, info) => {
    await open(page, "/hosting/earnings/", { "/v1/earnings": { json: hosting.withReportRefund() } });
    const refund = page.getByRole("listitem").filter({ hasText: "Refunded to renter" });
    await expect(refund).toHaveCount(1);
    // Only the host's part of the refund: the platform's commission was spent first.
    await expect(refund).toContainText(
      `Taken back −₹0.${String(trust.REFUND_SPLIT.fromHost).padStart(2, "0")}`,
    );
    await expect(refund).not.toContainText("Held from payout");
    const held = page.getByRole("listitem").filter({ hasText: "Held from payout while a report is open" });
    await expect(held.first()).toBeVisible();
    // Never who reported it, or why.
    await expect(page.locator("main")).not.toContainText(/Ravi|wrong output/i);
    await expectAccessible(page);
    await capture(page, info, "earnings-refund");
  });

  test("nothing earned yet, loading, and an error with a retry", async ({ page }, info) => {
    await open(page, "/hosting/earnings/", { "/v1/earnings": { json: hosting.noEarnings } });
    await expect(page.getByText("Nothing earned yet")).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "earnings-empty");
    await page.unrouteAll({ behavior: "ignoreErrors" });
    await fakeApi(page, api({ "/v1/earnings": "hold" }));
    await page.reload();
    await expect(page.getByRole("status", { name: "Loading your earnings" })).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "earnings-loading");
    await page.unrouteAll({ behavior: "ignoreErrors" });
    await fakeApi(page, api({ "/v1/earnings": { status: 503 } }));
    await page.reload();
    await expect(page.getByRole("button", { name: "Try again" })).toBeVisible({ timeout: 15_000 });
    await expectAccessible(page);
    await capture(page, info, "earnings-error");
  });
});

test.describe("adding a machine", () => {
  test("is a download and an approval, never a code", async ({ page }, info) => {
    await open(page, "/hosting/add/");
    await expect(page.getByRole("heading", { level: 1, name: "Earn with this laptop" })).toBeVisible();
    await expect(page.getByRole("listitem")).toHaveText([
      /Download the IdleO app for Windows/,
      /Open it: it sets everything up/,
      /Approve the link in your browser/,
    ]);
    // No release yet, so there's no download to offer.
    await expect(page.getByRole("button", { name: "Windows app: coming soon" })).toBeDisabled();
    await expect(page.getByRole("link", { name: /Download/ })).toHaveCount(0);
    await expect(page.getByText("Windows 10 or 11 · NVIDIA RTX graphics · 40 GB free")).toBeVisible();
    await expect(page.getByText("idleo-agent link")).toBeVisible();
    await expect(page.getByText(/enrolment code|one-time code|countdown/i)).toHaveCount(0);
    await expectAccessible(page);
    await capture(page, info, "add-download");
  });

  test("offers the Windows app with its version and size once it's released", async ({ page }, info) => {
    await open(page, "/hosting/add/", { "/v1/agent-releases/latest": { json: hosting.latestRelease } });
    const download = page.getByRole("link", { name: "Download for Windows" });
    await expect(download).toHaveAttribute("href", /\/v1\/agent-releases\/latest\/setup$/);
    await expect(page.getByText("v0.2.0 · 6.8 MB")).toBeVisible();
    await expect(page.getByRole("button", { name: "Windows app: coming soon" })).toHaveCount(0);
    await expectAccessible(page);
    await capture(page, info, "add-download-released");
  });
});

test.describe("linking a laptop", () => {
  const at = `/link/${hosting.LINK_ID}/`;

  test("asks which laptop, with the price already there", async ({ page }, info) => {
    await open(page, at);
    await expect(
      page.getByRole("heading", { level: 1, name: "Link this laptop to your account?" }),
    ).toBeVisible();
    await expect(page.getByText("ASUS ROG Strix", { exact: true }).first()).toBeVisible();
    await expect(page.getByText("RTX 4080 Laptop · 12 GB")).toBeVisible();
    await expect(page.getByText(/Expires in 09:/)).toBeVisible();
    await expect(page.getByLabel("Name", { exact: true })).toHaveValue("ASUS ROG Strix");
    // 16 paise a minute is ₹9.60 an hour; the owner keeps 80% of it.
    await expect(page.getByText(/You'll earn/)).toContainText("₹7.68 an hour (renters pay ₹9.60)");
    await expectAccessible(page);
    await capture(page, info, "link-pending");
  });

  test("one click approves with the name and the price, and says new laptops are checked", async ({
    page,
  }, info) => {
    let body: unknown = null;
    await open(page, at, {
      "/v1/host-links/[^/]+/approve": (_call, request) => {
        body = request.postDataJSON();
        return { json: hosting.hostLinkApproved };
      },
    });
    await page.getByRole("button", { name: "Approve and start earning" }).click();
    await expect(page.getByRole("heading", { name: "Linked" })).toBeVisible();
    expect(body).toEqual({
      name: "ASUS ROG Strix",
      batchPricePaisePerMin: 16,
      gpuTempCapC: 85,
      keepFreeGb: 20,
    });
    await expect(page.getByText(/We check every new laptop before it goes live/)).toBeVisible();
    await expect(page.getByRole("link", { name: "Open your laptop" })).toHaveAttribute(
      "href",
      `/hosting/machine/?id=${hosting.hostNew.id}`,
    );
    await expectAccessible(page);
    await capture(page, info, "link-approved");
  });

  test("the price can be changed first, in the form a machine has", async ({ page }, info) => {
    let body: { batchPricePaisePerMin?: number } = {};
    await open(page, at, {
      "/v1/host-links/[^/]+/approve": (_call, request) => {
        body = request.postDataJSON() as typeof body;
        return { json: hosting.hostLinkApproved };
      },
    });
    await page.getByRole("button", { name: "Change" }).click();
    await page.getByRole("button", { name: "Raise the price by ₹0.60 an hour" }).click();
    await expect(page.getByText(/You'll earn/)).toContainText("₹8.16 an hour (renters pay ₹10.20)");
    await expectAccessible(page);
    await capture(page, info, "link-change");
    await page.getByRole("button", { name: "Approve and start earning" }).click();
    await expect(page.getByRole("heading", { name: "Linked" })).toBeVisible();
    expect(body.batchPricePaisePerMin).toBe(17);
  });

  test("'This isn't my laptop' denies it", async ({ page }, info) => {
    await open(page, at, {
      "/v1/host-links/[^/]+/deny": { json: { ...hosting.hostLink, state: "denied" } },
    });
    await page.getByRole("button", { name: "This isn't my laptop" }).click();
    await expect(page.getByText("Nothing was linked")).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "link-denied");
  });

  test("an expired link says so and where to start again", async ({ page }, info) => {
    await open(page, at, { "/v1/host-links/[^/]+": { json: { ...hosting.hostLink, state: "expired" } } });
    await expect(page.getByText("This link has expired")).toBeVisible();
    await expect(page.getByText(/Start again from the app on your laptop/)).toBeVisible();
    await expect(page.getByRole("button", { name: "Approve and start earning" })).toHaveCount(0);
    await expectAccessible(page);
    await capture(page, info, "link-expired");
  });

  test("a link that isn't there is said plainly", async ({ page }, info) => {
    await open(page, at, { "/v1/host-links/[^/]+": { status: 404, problem: "not-found" } });
    await expect(page.getByText("We can't find that link")).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "link-missing");
  });

  test("while it loads, then an error with a retry", async ({ page }, info) => {
    await open(page, at, { "/v1/host-links/[^/]+": "hold" });
    await expect(page.getByRole("status", { name: "Loading this link" })).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "link-loading");
    await page.unrouteAll({ behavior: "ignoreErrors" });
    await fakeApi(page, api({ "/v1/host-links/[^/]+": { status: 503 } }));
    await page.reload();
    await expect(page.getByRole("button", { name: "Try again" })).toBeVisible({ timeout: 15_000 });
    await expectAccessible(page);
    await capture(page, info, "link-error");
  });
});
