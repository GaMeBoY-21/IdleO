import { expect, test } from "@playwright/test";
import { API, expectAccessible, signIn } from "./support";

/**
 * The renter's journey against the real local stack (scripts/e2e/agent-online.sh), with the real agent running
 * the session: sign in through the local sign-in adapter, see the credit the operator granted, book a session
 * through the API, and watch the home screen follow it live through RUNNING to COMPLETED, with the exact charge
 * and its output. The script creates E2E_WEB_USER and grants it ₹500 before this runs.
 */
const user = process.env.E2E_WEB_USER ?? "";
const signInServer = process.env.SIGN_IN_URL ?? "http://localhost:8099/idleo-local";

test.skip(!user, "needs the local stack and E2E_WEB_USER (scripts/e2e/agent-online.sh)");

test("a booked session shows live on the home screen, through to its charge and output", async ({
  page,
  request,
}) => {
  await signIn(page, user);
  if (new URL(page.url()).pathname === "/welcome/") {
    await expect(page.getByText("₹500.00")).toBeVisible();
    await page.getByRole("button", { name: "Go to your home" }).click();
  }
  const credit = page.getByRole("region", { name: "Credit" });
  await expect(credit.getByText("₹500.00").first()).toBeVisible();
  await expect(page.getByText("No sessions yet")).toBeVisible();
  await expectAccessible(page);

  // The same invented user, as the script's operator tooling signs in.
  const token = (await (
    await request.post(`${signInServer}/token`, {
      form: { grant_type: "client_credentials", client_id: user, client_secret: "unused", scope: "openid" },
    })
  ).json()) as { access_token: string };
  const booked = await request.post(`${API}/v1/sessions`, {
    headers: { Authorization: `Bearer ${token.access_token}`, "Idempotency-Key": `${user}-web-journey` },
    data: { templateId: "cuda-smoke-test", maxPricePaisePerMin: 5, maxSpendPaise: 10000 },
  });
  expect(booked.status()).toBe(201);
  const session = (await booked.json()) as { id: string };

  // The renter opens their home again (booking from the app itself comes next round), and it polls while the
  // session runs.
  await page.reload();
  const running = page.getByRole("region", { name: "Running now" });
  await expect(running.getByText("Running")).toBeVisible({ timeout: 90_000 });
  await expect(running.getByText("so far", { exact: true })).toBeVisible();
  await expect(page.getByText(/Live · updated/)).toBeVisible();
  await expectAccessible(page);

  // The smoke test runs 20 s: one started minute at 2 paise, charged once metering closes the session.
  const recent = page.getByRole("region", { name: "Recent" });
  await expect(recent.getByText("Completed")).toBeVisible({ timeout: 120_000 });
  await expect(recent.getByText("₹0.02")).toBeVisible({ timeout: 90_000 });
  await page.reload();
  await expect(credit.getByText("₹499.98").first()).toBeVisible({ timeout: 30_000 });
  await expect(credit.getByText("₹0.00").first()).toBeVisible();

  await recent.getByRole("link").first().click();
  await expect(page).toHaveURL(new RegExp(`/session/\\?id=${session.id}`));
  await expect(page.getByText("Charged", { exact: true })).toBeVisible();
  await expect(page.getByText("₹0.02").first()).toBeVisible();
  const output = page.getByRole("link", { name: /result\.json/ });
  await expect(output).toBeVisible();
  const download = await request.get((await output.getAttribute("href"))!);
  expect(download.status()).toBe(200);
  expect(((await download.json()) as { attempt: number }).attempt).toBe(1);
  await expectAccessible(page);
});
