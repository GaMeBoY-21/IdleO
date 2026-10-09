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
 * Book a GPU in every state (web/DESIGN.md "Book"), light and dark, phone and laptop: what to run, a service with
 * the best free GPU and its price, the settings sheet generated from each template's parameters, an upload on its
 * way, and the ways a request can't go ahead. The API and storage answer from fixtures; the quote follows the
 * server-set ceiling (ADR 0021).
 */
const MIB = 1024 * 1024;
const echo = data.templateList.find((t) => t.id === "echo-input")!;

type QuoteBody = { maxPricePaisePerMin?: number | null; maxSpendPaise: number; vramNeededMb?: number };

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
    "/v1/session-quotes": (_call, request) => data.quoteAnswer(request.postDataJSON() as QuoteBody),
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

const ECHO = "/book/?service=run-code";

/** Echo an input, its settings filled in the sheet and its file chosen. */
async function fillEcho(page: Page) {
  await page.getByRole("button", { name: /^Settings/ }).click();
  const sheet = page.getByRole("dialog", { name: "Settings" });
  await sheet.getByLabel("Greeting", { exact: true }).fill("hello");
  await sheet.getByLabel("Repeat (optional)").fill("3");
  await sheet.getByRole("button", { name: "Done" }).click();
  await page.getByTestId("input-file").setInputFiles(file(12 * MIB));
}

const request = (page: Page) => page.getByRole("button", { name: "Request a GPU" });

test("what to run", async ({ page }, info) => {
  await fakeApi(page, api());
  await signIn(page, "asha", "/book/");
  await expect(page.getByRole("heading", { name: "What do you want to run?" })).toBeVisible();
  await expect(page.getByRole("group", { name: "What to run" }).getByRole("button")).toHaveCount(2);
  await expectAccessible(page);
  await capture(page, info, "book-pick");
});

test("what to run, while the catalog loads", async ({ page }, info) => {
  await fakeApi(page, api({ "/v1/templates": "hold" }));
  await signIn(page, "asha", "/book/");
  await expect(page.getByRole("status", { name: "Loading" })).toBeVisible();
  await expectAccessible(page);
  await capture(page, info, "book-pick-loading");
});

test("a service: the best free GPU for it, its price, and the one button", async ({ page }, info) => {
  await fakeApi(page, api());
  await signIn(page, "asha", "/book/?service=fine-tune");
  // QLoRA needs 8 GB: the RTX 4080s fit, at 12–18 paise a minute; the server's ceiling is 18, held 30 minutes.
  const quote = data.quote(undefined, 50_000, data.asha.wallet, data.availability, 8_192)!;
  expect(quote.holdPaise).toBe(540);
  await expect(page.getByText("At least 8 GB · 2 free", { exact: false })).toBeVisible();
  await expect(page.getByText("≈ ₹5–8")).toBeVisible();
  await expect(page.getByText("₹7.20–10.80/h")).toBeVisible();
  await expect(page.getByText("₹5.40 is held while it runs. You have ₹462.12.")).toBeVisible();
  await expect(request(page)).toBeEnabled();
  await expectAccessible(page);
  await capture(page, info, "book-service");
});

test("settings: every kind of parameter, generated from the catalog", async ({ page }, info) => {
  await fakeApi(page, api());
  await signIn(page, "asha", "/book/?service=fine-tune");
  await page.getByRole("button", { name: /^Settings/ }).click();
  const sheet = page.getByRole("dialog", { name: "Settings" });
  await expect(sheet.getByLabel("Epochs")).toHaveValue("3");
  await expect(sheet.getByRole("radio", { name: "16" })).toBeChecked();
  await expect(sheet.getByRole("switch", { name: "Pack short examples together" })).toBeChecked();
  await expect(sheet.getByLabel("Prompt template (optional)")).toBeVisible();
  // Empty: the server picks the most per hour (ADR 0021).
  await expect(sheet.getByLabel("The most per hour, in ₹ (optional)")).toHaveValue("");
  await expect(sheet.getByLabel("Spending cap, in ₹")).toHaveValue("500.00");
  await expect(sheet.getByText("Don't put passwords or tokens here", { exact: false })).toBeVisible();
  await expectAccessible(page);
  await capture(page, info, "book-settings");
});

test("what's wrong, on each field", async ({ page }, info) => {
  await fakeApi(page, api());
  await signIn(page, "asha", ECHO);
  await page.getByRole("button", { name: /^Settings/ }).click();
  const sheet = page.getByRole("dialog", { name: "Settings" });
  await sheet.getByLabel("Repeat (optional)").fill("9");
  await sheet.getByRole("button", { name: "Done" }).click();
  await request(page).click();
  await expect(sheet.getByText("Enter a greeting.")).toBeVisible();
  await expect(sheet.getByText("Enter a number between 1 and 5.")).toBeVisible();
  await expectAccessible(page);
  await capture(page, info, "book-errors");
  await sheet.getByRole("button", { name: "Done" }).click();
  await expect(page.getByText("Choose a file to upload.")).toBeVisible();
});

test("a file too large is refused before anything is sent", async ({ page }, info) => {
  const small = { ...echo, inputs: [{ ...echo.inputs[0]!, maxSizeBytes: MIB }] };
  await fakeApi(page, api({ "/v1/templates": { json: { ...data.templates, templates: [small] } } }));
  await signIn(page, "asha", ECHO);
  const created = page.waitForRequest("**/v1/uploads", { timeout: 1_000 }).catch(() => null);
  await page.getByTestId("input-file").setInputFiles(file(2 * MIB));
  await expect(
    page.getByText("greetings.bin is 2.0 MB. Echo an input takes files up to 1.0 MB."),
  ).toBeVisible();
  expect(await created).toBeNull();
  await expectAccessible(page);
  await capture(page, info, "book-too-large");
});

test("waiting for the upload, which starts as soon as the file is chosen", async ({ page }, info) => {
  await fakeApi(page, api());
  await fakeStorage(page, { hold: true });
  await signIn(page, "asha", ECHO);
  await fillEcho(page);
  await expect(page.getByRole("progressbar", { name: "Uploading greetings.bin" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Cancel the upload of greetings.bin" })).toBeVisible();
  await expect(page.getByRole("button", { name: /Waiting for your upload… \d+%/ })).toBeDisabled();
  // The price shows while the upload is still going: the quote leaves the input out.
  await expect(page.getByText("is held while it runs", { exact: false })).toBeVisible();
  await expectAccessible(page);
  await capture(page, info, "book-uploading");
});

test("not enough credit, with both numbers", async ({ page }, info) => {
  await fakeApi(
    page,
    api({
      "/v1/wallet": { json: data.low.wallet },
      "/v1/session-quotes": (_call, request) =>
        data.quoteAnswer(request.postDataJSON() as QuoteBody, data.low.wallet),
    }),
  );
  await fakeStorage(page);
  await signIn(page, "asha", ECHO);
  await fillEcho(page);
  await expect(page.getByText("12 MB · uploaded", { exact: true })).toBeVisible();
  await expect(
    page.getByText("₹5.40 is held while it runs, and you have ₹4.60.", { exact: false }),
  ).toBeVisible();
  await expect(request(page)).toBeDisabled();
  await expectAccessible(page);
  await capture(page, info, "book-short");
});

test("no GPU is free right now: the request waits", async ({ page }, info) => {
  await fakeApi(
    page,
    api({
      "/v1/availability": { json: data.noAvailability },
      "/v1/session-quotes": (_call, request) =>
        data.quoteAnswer(request.postDataJSON() as QuoteBody, data.asha.wallet, data.noAvailability),
    }),
  );
  await signIn(page, "asha", "/book/?service=fine-tune");
  await expect(page.getByText("No GPU is free right now", { exact: true })).toBeVisible();
  await expect(page.getByText("No GPU is free right now. We look again every 15 seconds.")).toBeVisible();
  await expect(request(page)).toBeDisabled();
  await expectAccessible(page);
  await capture(page, info, "book-none-free");
});

test("a field the server refuses comes back on that field, in Settings", async ({ page }, info) => {
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
  await signIn(page, "asha", ECHO);
  await fillEcho(page);
  await expect(page.getByText("12 MB · uploaded", { exact: true })).toBeVisible();
  await request(page).click();
  const sheet = page.getByRole("dialog", { name: "Settings" });
  await expect(sheet.getByText("Must not contain a URL.")).toBeVisible();
  await expectAccessible(page);
  await capture(page, info, "book-refused-field");
});
