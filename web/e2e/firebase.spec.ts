import { expect, test, type Page } from "@playwright/test";
import * as data from "./fixtures";
import { capture, expectAccessible, fakeApi } from "./support";

/**
 * The production sign-in adapter, Firebase (ADR 0018), in a real browser: a build with placeholder Firebase
 * settings (scripts/e2e/agent-online.sh serves it at FIREBASE_WEB_URL), with Google's Identity Toolkit answered
 * by fixtures. It covers the screens only that adapter has: the email sent, the link opened on another device,
 * links that have expired or been used, and a blocked popup; and a link that signs the person in.
 */
const firebaseWeb = process.env.FIREBASE_WEB_URL ?? "";
test.skip(!firebaseWeb, "needs the Firebase build served at FIREBASE_WEB_URL");
test.use({ baseURL: firebaseWeb });

const TOOLKIT = "https://identitytoolkit.googleapis.com/v1/accounts";
const LINK = "/sign-in/finish/?apiKey=placeholder-not-a-key&oobCode=fixture-code&mode=signIn&lang=en";
const EMAIL_KEY = "idleo.emailForSignIn";

/** Answers one Identity Toolkit call, as Google would. */
async function toolkit(page: Page, call: string, status: number, body: unknown): Promise<void> {
  await page.route(`${TOOLKIT}:${call}*`, (route) =>
    route.fulfill({ status, json: body, headers: { "Access-Control-Allow-Origin": "*" } }),
  );
}

const toolkitError = (message: string) => ({ error: { code: 400, message, errors: [{ message }] } });

/** An unsigned ID token shaped like Firebase's; the browser SDK reads its claims without checking a signature. */
function idToken(email: string): string {
  const encode = (value: object) => Buffer.from(JSON.stringify(value)).toString("base64url");
  const now = Math.floor(data.NOW.getTime() / 1000);
  return `${encode({ alg: "none" })}.${encode({
    iss: "https://securetoken.google.com/placeholder",
    aud: "placeholder",
    sub: "fixture-user",
    user_id: "fixture-user",
    email,
    email_verified: true,
    iat: now,
    exp: now + 3600,
    auth_time: now,
    firebase: { sign_in_provider: "password" },
  })}.`;
}

test.beforeEach(async ({ page }) => {
  await page.clock.setFixedTime(data.NOW);
});

test("the email is sent", async ({ page }, info) => {
  await toolkit(page, "sendOobCode", 200, {
    kind: "identitytoolkit#GetOobConfirmationCodeResponse",
    email: "asha@example.com",
  });
  await page.goto("/sign-in/");
  await page.getByLabel("Email", { exact: true }).fill("asha@example.com");
  await page.getByRole("button", { name: "Email me a sign-in link" }).click();
  await expect(page.getByRole("heading", { name: "Check your email" })).toBeVisible();
  await expect(page.getByText("asha@example.com")).toBeVisible();
  expect(await page.evaluate((key) => localStorage.getItem(key), EMAIL_KEY)).toBe("asha@example.com");
  await expectAccessible(page);
  await capture(page, info, "sign-in-email-sent");
});

test("a link opened on another device asks which address it was sent to", async ({ page }, info) => {
  await page.goto(LINK);
  await expect(page.getByRole("heading", { name: "Confirm your email" })).toBeVisible();
  await expectAccessible(page);
  await capture(page, info, "sign-in-confirm-email");
});

test("an expired link says so, and how to get a new one", async ({ page }, info) => {
  await page.addInitScript((key) => localStorage.setItem(key, "asha@example.com"), EMAIL_KEY);
  await toolkit(page, "signInWithEmailLink", 400, toolkitError("EXPIRED_OOB_CODE"));
  await page.goto(LINK);
  await expect(page.getByText("This sign-in link has expired.", { exact: false })).toBeVisible();
  await expect(page.getByRole("link", { name: "Send a new link" })).toBeVisible();
  await expectAccessible(page);
  await capture(page, info, "sign-in-link-expired");
});

test("a link that was already used says so", async ({ page }) => {
  await page.addInitScript((key) => localStorage.setItem(key, "asha@example.com"), EMAIL_KEY);
  await toolkit(page, "signInWithEmailLink", 400, toolkitError("INVALID_OOB_CODE"));
  await page.goto(LINK);
  await expect(page.getByText("already been used", { exact: false })).toBeVisible();
});

test("a blocked Google popup says what to do", async ({ page }, info) => {
  await page.addInitScript(() => {
    window.open = () => null;
  });
  await page.goto("/sign-in/");
  // A press before the page has taken over from its pre-rendered frame does nothing, so a busy machine can miss
  // it: press again until the message is there.
  await expect(async () => {
    await page.getByRole("button", { name: "Continue with Google" }).click();
    await expect(
      page.getByText("Your browser blocked the Google sign-in window.", { exact: false }),
    ).toBeVisible({ timeout: 1_000 });
  }).toPass({ timeout: 15_000 });
  await expectAccessible(page);
  await capture(page, info, "sign-in-popup-blocked");
});

test("a link opened on another device signs the person in once they confirm their address", async ({
  page,
}) => {
  await fakeApi(page, {
    "/v1/me": { json: data.me() },
    "/v1/templates": { json: data.templates },
    "/v1/wallet": { json: data.asha.wallet },
    "/v1/sessions": { json: { sessions: [] } },
  });
  await toolkit(page, "signInWithEmailLink", 200, {
    kind: "identitytoolkit#EmailLinkSigninResponse",
    idToken: idToken("asha@example.com"),
    refreshToken: "fixture-refresh-token",
    expiresIn: "3600",
    localId: "fixture-user",
    email: "asha@example.com",
    isNewUser: false,
  });
  await toolkit(page, "lookup", 200, {
    kind: "identitytoolkit#GetAccountInfoResponse",
    users: [
      { localId: "fixture-user", email: "asha@example.com", emailVerified: true, providerUserInfo: [] },
    ],
  });
  await page.goto(LINK);
  await page.getByLabel("Email", { exact: true }).fill("asha@example.com");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByRole("heading", { name: "What do you want to run?" })).toBeVisible();
  // The header's credit chip is hidden on a phone, so the first one that is shown.
  await expect(page.getByText("₹462.12").locator("visible=true").first()).toBeVisible();
});
