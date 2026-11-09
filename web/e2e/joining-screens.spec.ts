import { expect, test, type Page } from "@playwright/test";
import * as data from "./fixtures";
import * as hosting from "./fixtures-hosting";
import * as payouts from "./fixtures-payouts";
import { hostingApi } from "./hosting-api";
import { HOST_URL } from "./sites";
import { capture, expectAccessible, fakeApi, signIn, watchConsole, type Answer } from "./support";

/**
 * One account, two sides (ADR 0058): a site whose side the account hasn't unlocked says so, takes a code, and leads to
 * the other site; never a dead end. The API answers from fixtures.
 */
let checkConsole: () => void;
test.beforeEach(async ({ page }) => {
  checkConsole = watchConsole(page);
  await page.clock.setFixedTime(data.NOW);
});
test.afterEach(() => checkConsole());

function api(
  unlocked: ("renting" | "hosting")[],
  overrides: Record<string, Answer> = {},
): Record<string, Answer> {
  // The account as the server has it: both sides once a code has unlocked the other.
  let now = unlocked;
  return {
    "/v1/me/activate": () => {
      now = ["renting", "hosting"];
      return { json: data.me(undefined, undefined, now) };
    },
    "/v1/me": () => ({ json: data.me(undefined, undefined, now) }),
    "/v1/templates": { json: data.templates },
    "/v1/wallet": { json: data.asha.wallet },
    "/v1/sessions": { json: data.sessionsPage(data.asha.sessions) },
    ...hostingApi(),
    ...overrides,
  };
}

const unlockOn = async (page: Page, site: string, unlocked: ("renting" | "hosting")[]) => {
  await fakeApi(page, api(unlocked));
  await signIn(page, "asha", "/", site);
};

test("the host site, for an account that can only rent, says so and takes a code", async ({ page }, info) => {
  await unlockOn(page, HOST_URL, ["renting"]);
  await expect(
    page.getByRole("heading", { level: 1, name: "Hosting isn't open for your account yet" }),
  ).toBeVisible();
  await expect(page.getByLabel("Invite code")).toBeVisible();
  await expect(page.getByRole("link", { name: "rent a gpu" })).toHaveAttribute("href", /\/$/);
  await expectAccessible(page);
  await capture(page, info, "hosting-locked");

  await page.getByLabel("Invite code").fill("IDLE7K2Q9XMD");
  await page.getByRole("button", { name: "Unlock hosting" }).click();
  await expect(page.getByRole("heading", { name: "Hosting isn't open for your account yet" })).toHaveCount(0);
});

test("the renter app, for an account that can only host, says so the other way round", async ({
  page,
}, info) => {
  await unlockOn(page, "", ["hosting"]);
  await expect(
    page.getByRole("heading", { level: 1, name: "Renting isn't open for your account yet" }),
  ).toBeVisible();
  await expect(page.getByRole("button", { name: "Unlock renting" })).toBeVisible();
  await expect(page.getByRole("link", { name: "earn with your laptop" })).toHaveAttribute(
    "href",
    `${HOST_URL}/`,
  );
  await expectAccessible(page);
  await capture(page, info, "renting-locked");
});

test("a short code is caught before it's sent", async ({ page }) => {
  await unlockOn(page, HOST_URL, ["renting"]);
  await page.getByLabel("Invite code").fill("IDLE7K");
  await page.getByRole("button", { name: "Unlock hosting" }).click();
  await expect(page.getByText("Enter all 8 letters and numbers of your code.")).toBeVisible();
});

test("a new renter's Home has the getting-started checklist, the test job next", async ({ page }, info) => {
  await fakeApi(page, api(["renting", "hosting"], { "/v1/sessions": { json: data.sessionsPage([]) } }));
  await signIn(page, "asha");
  const checklist = page.getByRole("region", { name: "Get started" });
  await expect(checklist).toContainText("2 of 3 done");
  await expect(checklist.getByRole("link", { name: "Run the test" })).toHaveAttribute(
    "href",
    "/book/?service=diagnostics&template=cuda-smoke-test",
  );
  await expectAccessible(page);
  await capture(page, info, "home-first-run");
});

test("a new host's home has the six setup steps, the Windows app first", async ({ page }, info) => {
  await fakeApi(
    page,
    api(["renting", "hosting"], {
      "/v1/hosts": { json: hosting.noHosts },
      "/v1/payout-details": { json: payouts.detailsNew },
    }),
  );
  await signIn(page, "asha", "/", HOST_URL);
  const checklist = page.getByRole("region", { name: "Start hosting" });
  await expect(checklist).toContainText("1 of 6 done");
  await expect(checklist.getByRole("listitem")).toHaveCount(6);
  await expect(checklist.getByRole("link", { name: "Download" })).toHaveAttribute("href", "/add/");
  await expectAccessible(page);
  await capture(page, info, "hosting-first-run");
});
