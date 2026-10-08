import { expect, test, type Page } from "@playwright/test";
import * as data from "./fixtures";
import {
  capture,
  expectAccessible,
  fakeApi,
  fakeStorage,
  signIn,
  watchConsole,
  type Answer,
} from "./support";

/**
 * Book a GPU in every state (web/DESIGN.md "States"), light and dark, phone and laptop: the template gallery, the
 * form generated from each template's parameters, an upload on its way, the review with the quote, and the ways a
 * booking can't go ahead. The API and storage answer from fixtures.
 */
const MIB = 1024 * 1024;
const echo = data.templateList.find((t) => t.id === "echo-input")!;

function api(overrides: Record<string, Answer> = {}): Record<string, Answer> {
  return {
    "/v1/me": { json: data.me() },
    "/v1/templates": { json: data.templates },
    "/v1/wallet": { json: data.asha.wallet },
    "/v1/sessions": { json: data.sessionsPage(data.asha.sessions) },
    "/v1/availability": { json: data.availability },
    "/v1/uploads": { json: data.newUpload("greetings.bin", 12 * MIB), status: 201 },
    "/v1/uploads/[^/]+/complete": { json: { ...data.newUpload("greetings.bin", 12 * MIB), status: "ready" } },
    "/v1/uploads/[^/]+": { json: { ...data.newUpload("greetings.bin", 12 * MIB), status: "ready" } },
    "/v1/session-quotes": (_call, request) => {
      const body = request.postDataJSON() as { maxPricePaisePerMin: number; maxSpendPaise: number };
      return { json: data.quote(body.maxPricePaisePerMin, body.maxSpendPaise) };
    },
    ...overrides,
  };
}

let checkConsole: () => void;
test.beforeEach(async ({ page }) => {
  checkConsole = watchConsole(page);
  await page.clock.setFixedTime(data.NOW);
});
test.afterEach(() => checkConsole());

const file = (size: number) => ({
  name: "greetings.bin",
  mimeType: "application/octet-stream",
  buffer: Buffer.alloc(size, 7),
});

/** Echo an input, filled in, with its file uploaded: ready for review. */
async function fillEcho(page: Page) {
  await page.getByLabel("Greeting").fill("hello");
  await page.getByLabel("Repeat (optional)").fill("3");
  await page.getByTestId("input-file").setInputFiles(file(12 * MIB));
}

test("pick a template", async ({ page }, info) => {
  await fakeApi(page, api());
  await signIn(page, "asha", "/book/");
  await expect(page.getByRole("button", { name: /QLoRA fine-tune/ })).toBeVisible();
  await expect(page.getByText("From ₹7.20 an hour").first()).toBeVisible();
  await expect(page.getByText("At least 8 GB GPU memory")).toBeVisible();
  await expectAccessible(page);
  await capture(page, info, "book-pick");
});

test("pick a template when no host is free", async ({ page }, info) => {
  await fakeApi(page, api({ "/v1/availability": { json: data.noAvailability } }));
  await signIn(page, "asha", "/book/");
  await expect(page.getByText("No hosts free right now")).toHaveCount(3);
  await expectAccessible(page);
  await capture(page, info, "book-pick-none-free");
});

test("pick, while the templates load", async ({ page }, info) => {
  await fakeApi(page, api({ "/v1/templates": "hold" }));
  await signIn(page, "asha", "/book/");
  await expect(page.getByLabel("Loading the templates")).toBeVisible();
  await expectAccessible(page);
  await capture(page, info, "book-pick-loading");
});

test("configure: every kind of parameter, generated from the catalog", async ({ page }, info) => {
  await fakeApi(page, api());
  await signIn(page, "asha", "/book/?template=qlora-finetune&step=configure");
  await expect(page.getByLabel("Base model")).toHaveValue("qwen2.5-1.5b");
  await expect(page.getByLabel("Epochs")).toHaveValue("3");
  await expect(page.getByRole("radio", { name: "16" })).toBeChecked();
  await expect(page.getByRole("switch", { name: "Pack short examples together" })).toBeChecked();
  await expect(page.getByLabel("Prompt template (optional)")).toBeVisible();
  // The default most per hour is the highest price among hosts with enough memory that are free now.
  await expect(page.getByLabel("The most per hour, in ₹")).toHaveValue("10.80");
  await expect(page.getByLabel("Spending cap, in ₹")).toHaveValue("500.00");
  await expect(page.getByText("Don't put passwords or tokens here.", { exact: false })).toBeVisible();
  await expectAccessible(page);
  await capture(page, info, "book-configure");
});

test("configure: what's wrong, on each field", async ({ page }, info) => {
  await fakeApi(page, api());
  await signIn(page, "asha", "/book/?template=echo-input&step=configure");
  await page.getByLabel("Repeat (optional)").fill("9");
  await page.getByRole("button", { name: "Review" }).click();
  await expect(page.getByText("Enter a greeting.")).toBeVisible();
  await expect(page.getByText("Enter a number between 1 and 5.")).toBeVisible();
  await expect(page.getByText("Choose a file to upload.")).toBeVisible();
  await expect(page).toHaveURL(/step=configure/);
  await expectAccessible(page);
  await capture(page, info, "book-configure-errors");
});

test("configure: a file too large is refused before anything is sent", async ({ page }, info) => {
  const small = { ...echo, input: { ...echo.input, maxSizeMb: 1 } };
  await fakeApi(page, api({ "/v1/templates": { json: { templates: [small] } } }));
  await signIn(page, "asha", "/book/?template=echo-input&step=configure");
  const created = page.waitForRequest("**/v1/uploads", { timeout: 1_000 }).catch(() => null);
  await page.getByTestId("input-file").setInputFiles(file(2 * MIB));
  await expect(
    page.getByText("greetings.bin is 2.0 MB. Echo an input takes files up to 1.0 MB."),
  ).toBeVisible();
  expect(await created).toBeNull();
  await expectAccessible(page);
  await capture(page, info, "book-configure-too-large");
});

test("configure: the file uploading as soon as it's chosen", async ({ page }, info) => {
  await fakeApi(page, api());
  await fakeStorage(page, { hold: true });
  await signIn(page, "asha", "/book/?template=echo-input&step=configure");
  await fillEcho(page);
  await expect(page.getByRole("progressbar", { name: "Uploading greetings.bin" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Cancel the upload of greetings.bin" })).toBeVisible();
  await expectAccessible(page);
  await capture(page, info, "book-configure-uploading");
});

test("review: the quote's numbers, exactly, and the one button", async ({ page }, info) => {
  await fakeApi(page, api());
  await fakeStorage(page);
  await signIn(page, "asha", "/book/?template=echo-input&step=configure");
  await fillEcho(page);
  await expect(page.getByText("12 MB · uploaded")).toBeVisible();
  await page.getByRole("button", { name: "Review" }).click();
  const quote = data.quote(18, 50_000);
  expect(quote.holdPaise).toBe(540);
  await expect(page.getByText("₹5.40 is held now")).toBeVisible();
  await expect(page.getByText("You'll pay at most ₹500.00, your spending cap.")).toBeVisible();
  await expect(page.getByText("Hosts free now: 3, ₹3.60–₹10.80 an hour")).toBeVisible();
  await expect(page.getByText("Your available credit after the hold: ₹456.72")).toBeVisible();
  await expect(page.getByRole("button", { name: "Book for up to ₹500.00" })).toBeEnabled();
  await expectAccessible(page);
  await capture(page, info, "book-review");
});

test("review: waiting for the upload", async ({ page }, info) => {
  await fakeApi(page, api());
  await fakeStorage(page, { hold: true });
  await signIn(page, "asha", "/book/?template=echo-input&step=configure");
  await fillEcho(page);
  await page.getByRole("button", { name: "Review" }).click();
  await expect(page.getByRole("button", { name: /Waiting for your upload… \d+%/ })).toBeDisabled();
  await expect(page.getByText("The price shows as soon as your upload has finished.")).toBeVisible();
  await expectAccessible(page);
  await capture(page, info, "book-review-uploading");
});

test("review: not enough credit, with both numbers", async ({ page }, info) => {
  await fakeApi(
    page,
    api({
      "/v1/wallet": { json: data.low.wallet },
      "/v1/session-quotes": (_call, request) => {
        const body = request.postDataJSON() as { maxPricePaisePerMin: number; maxSpendPaise: number };
        return { json: data.quote(body.maxPricePaisePerMin, body.maxSpendPaise, data.low.wallet) };
      },
    }),
  );
  await fakeStorage(page);
  await signIn(page, "asha", "/book/?template=echo-input&step=configure");
  await fillEcho(page);
  await expect(page.getByText("12 MB · uploaded")).toBeVisible();
  await page.getByRole("button", { name: "Review" }).click();
  await expect(
    page.getByText("Booking holds ₹5.40; you have ₹4.60 available.", { exact: false }),
  ).toBeVisible();
  await expect(page.getByRole("button", { name: "Book for up to ₹500.00" })).toBeDisabled();
  await expectAccessible(page);
  await capture(page, info, "book-review-short");
});

test("review: no host fits now, booking still allowed", async ({ page }, info) => {
  await fakeApi(
    page,
    api({
      "/v1/session-quotes": (_call, request) => {
        const body = request.postDataJSON() as { maxPricePaisePerMin: number; maxSpendPaise: number };
        return {
          json: data.quote(
            body.maxPricePaisePerMin,
            body.maxSpendPaise,
            data.asha.wallet,
            data.noAvailability,
          ),
        };
      },
    }),
  );
  await fakeStorage(page);
  await signIn(page, "asha", "/book/?template=echo-input&step=configure");
  await fillEcho(page);
  await expect(page.getByText("12 MB · uploaded")).toBeVisible();
  await page.getByRole("button", { name: "Review" }).click();
  await expect(
    page.getByText("No host fits right now. If none frees up within a minute, nothing is charged."),
  ).toBeVisible();
  await expect(page.getByRole("button", { name: "Book for up to ₹500.00" })).toBeEnabled();
  await expectAccessible(page);
  await capture(page, info, "book-review-no-host");
});

test("a field the server refuses comes back on that field", async ({ page }, info) => {
  await fakeApi(
    page,
    api({
      "/v1/sessions": (_call, request) =>
        request.method() === "POST"
          ? {
              status: 400,
              problem: "validation-failed",
              errors: [{ field: "params.greeting", detail: "must not contain a URL" }],
            }
          : { json: data.sessionsPage(data.asha.sessions) },
    }),
  );
  await fakeStorage(page);
  await signIn(page, "asha", "/book/?template=echo-input&step=configure");
  await fillEcho(page);
  await expect(page.getByText("12 MB · uploaded")).toBeVisible();
  await page.getByRole("button", { name: "Review" }).click();
  await page.getByRole("button", { name: "Book for up to ₹500.00" }).click();
  await expect(page).toHaveURL(/step=configure/);
  await expect(page.getByText("Must not contain a URL.")).toBeVisible();
  await expectAccessible(page);
  await capture(page, info, "book-refused-field");
});
