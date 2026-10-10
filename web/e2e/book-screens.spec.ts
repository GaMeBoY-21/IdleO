import { expect, test, type Page } from "@playwright/test";
import { mkdirSync, mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
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
 * Book a GPU in every state (web/DESIGN.md "Book", docs/design/redesign/book-finetune-laptop.html), light and dark,
 * phone and laptop: the services; a service's screen built from its template (inputs, plain questions, budget,
 * Advanced, and "Your request" with the best free GPU and its price); a model folder uploading; My files; a model too
 * big for any GPU here; and the ways a request can't go ahead. The API and storage answer from fixtures; the quote
 * follows the server-set ceiling (ADR 0021) and the memory the form asks for.
 */
const MIB = 1024 * 1024;
const fineTune = data.templateList.find((t) => t.id === "qlora-finetune")!;

type QuoteBody = { maxPricePaisePerMin?: number | null; maxSpendPaise: number; vramNeededMb?: number };
type UploadBody = { name: string; files: { path: string; sizeBytes: number }[] };

function api(overrides: Record<string, Answer> = {}): Record<string, Answer> {
  return {
    "/v1/me": { json: data.me() },
    "/v1/templates": { json: data.templates },
    "/v1/wallet": { json: data.asha.wallet },
    "/v1/sessions": { json: data.sessionsPage(data.asha.sessions) },
    "/v1/availability": { json: data.availability },
    "/v1/uploads": (_call, request) =>
      request.method() === "POST"
        ? { json: data.uploadOf(request.postDataJSON() as UploadBody), status: 201 }
        : { json: data.uploadsPage() },
    "/v1/uploads/[^/]+/part-urls": (_call, request) => ({
      json: data.partLinks((request.postDataJSON() as { partNumbers: number[] }).partNumbers),
    }),
    "/v1/uploads/[^/]+/complete": { json: data.newUpload("my-llm", 24 * MIB, "ready") },
    "/v1/uploads/[^/]+": { json: data.sessionUpload },
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

const FINE_TUNE = "/book/?service=fine-tune";
const request = (page: Page) => page.getByRole("button", { name: "Request a GPU" });
const yourRequest = (page: Page) => page.getByRole("region", { name: "Your request" });

/** A generated model folder on disk, as "Choose folder" reads one: a config, a tokenizer and the weights. */
function modelFolder(weightsMib = 24): string {
  const folder = join(mkdtempSync(join(tmpdir(), "idleo-model-")), "my-llm");
  mkdirSync(folder);
  writeFileSync(join(folder, "config.json"), '{"model_type": "llama"}');
  writeFileSync(join(folder, "tokenizer.json"), '{"version": "1.0"}');
  writeFileSync(join(folder, "model.safetensors"), Buffer.alloc(weightsMib * MIB, 7));
  return folder;
}

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

test("Fine-tune a model: three plain questions, the best free GPU and its price", async ({ page }, info) => {
  await fakeApi(page, api());
  await signIn(page, "asha", FINE_TUNE);
  await expect(page.getByRole("heading", { level: 1, name: "Fine-tune a model" })).toBeVisible();
  await expect(page.getByRole("link", { name: "All services" })).toBeVisible();
  for (const step of ["1. Your model", "2. Your training data", "3. How thorough?", "Budget"]) {
    await expect(page.getByRole("heading", { name: step })).toBeVisible();
  }
  // Without a model yet, the template's least memory: the 6 GB RTX 3060 and the 12 GB RTX 4080s fit, 6 to 18 paise a
  // minute; the server's ceiling is 18, held 30 minutes within the ₹100 budget.
  const quote = data.quote(undefined, 10_000, data.asha.wallet, data.availability, 6_144)!;
  expect([quote.hostsFitNow, quote.holdPaise]).toEqual([3, 540]);
  await expect(page.getByRole("button", { name: /^Standard/ })).toHaveAttribute("aria-pressed", "true");
  // 45 minutes at 6 to 18 paise: ₹2.70 to ₹8.10.
  await expect(page.getByRole("button", { name: /^Standard/ })).toContainText("≈ ₹3–8");
  await expect(page.getByRole("button", { name: "Stop at ₹100" })).toHaveAttribute("aria-pressed", "true");
  await expect(yourRequest(page).getByText("Best free 6 GB GPU")).toBeVisible();
  await expect(yourRequest(page).getByText("₹3.60–10.80/h")).toBeVisible();
  await expect(page.getByText("₹5.40 is held while it runs. You have ₹462.12.")).toBeVisible();
  await expectAccessible(page);
  await capture(page, info, "book-fine-tune");
  // The model and the data are still to give: pressing points at them.
  await request(page).click();
  await expect(page.getByText("Choose your folder to upload.")).toBeVisible();
  await expect(page.getByText("Choose a file to upload.")).toBeVisible();
});

test("a model folder uploading, sized for its GPU while it goes", async ({ page }, info) => {
  await fakeApi(page, api());
  await fakeStorage(page, { hold: true });
  await signIn(page, "asha", FINE_TUNE);
  const opened = page.waitForRequest((r) => r.url().endsWith("/v1/uploads") && r.method() === "POST");
  await page.getByTestId("model-folder").setInputFiles(modelFolder());
  const body = (await opened).postDataJSON() as UploadBody;
  expect(body.name).toBe("my-llm");
  expect(body.files.map((file) => file.path).sort()).toEqual([
    "config.json",
    "model.safetensors",
    "tokenizer.json",
  ]);
  await expect(page.getByRole("progressbar", { name: "Uploading my-llm" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Cancel the upload of my-llm" })).toBeVisible();
  await expect(page.getByRole("button", { name: /Waiting for your upload… \d+%/ })).toBeDisabled();
  // The quote goes on while it uploads, with the memory its size needs: 2,048 + 700 x 0.02 GiB, the 8 GB class.
  await expect(yourRequest(page).getByText("Best free 8 GB GPU")).toBeVisible();
  await expect(page.getByText("Your 24 MB model needs an 8 GB GPU.")).toBeVisible();
  if (info.project.name.includes("laptop")) {
    // The laptop's request card names it; a phone's sheet keeps to the GPU and the button.
    await expect(yourRequest(page).getByText("my-llm/")).toBeVisible();
  }
  await expectAccessible(page);
  await capture(page, info, "book-uploading");
});

test("from My files, and a model too big for any GPU here", async ({ page }, info) => {
  await fakeApi(page, api());
  await signIn(page, "asha", FINE_TUNE);
  const model = page.getByRole("region", { name: "1. Your model" });
  await model.getByRole("radio", { name: "From My files" }).check();
  const choices = model.getByRole("group", { name: "Your model from My files" });
  await expect(choices.getByRole("button")).toHaveCount(4);
  await expect(choices.getByRole("button", { name: /my-llm-7b\// })).toContainText(
    "4.6 GB · 4 files · last used 22 min ago",
  );
  await choices.getByRole("button", { name: /llama-3-70b/ }).click();
  // 2,048 + 700 x 38 = 28,648 MB: more than the biggest GPU here, so the card says so and the request can't be made.
  await expect(page.getByTestId("gpu-card")).toContainText(
    "Your model needs about 29 GB of GPU memory.The biggest GPUs on IdleO have 24 GB.",
  );
  await expect(page.getByText("No GPU is free right now")).toHaveCount(0);
  await expect(request(page)).toBeDisabled();
  await expect(model.getByRole("link", { name: "Manage My files" })).toBeVisible();
  await expectAccessible(page);
  await capture(page, info, "book-too-big");
  await choices.getByRole("button", { name: /my-llm-7b/ }).click();
  await expect(page.getByText("Your 4.6 GB model needs an 8 GB GPU.")).toBeVisible();
});

test("a model as a link, and Advanced opened", async ({ page }, info) => {
  await fakeApi(page, api());
  await signIn(page, "asha", FINE_TUNE);
  const model = page.getByRole("region", { name: "1. Your model" });
  await model.getByRole("radio", { name: "Link" }).check();
  await model.getByLabel("Link to it").fill("https://huggingface.co/asha/my-llm-7b");
  await expect(
    page.getByText("We can't see a link's size; set GPU memory in Advanced if it's large."),
  ).toBeVisible();
  await page.getByRole("button", { name: /^Advanced/ }).click();
  await expect(page.getByLabel("Epochs (optional)")).toHaveValue("3");
  await expect(page.getByRole("radio", { name: "16" })).toBeChecked();
  await expect(page.getByLabel("GPU memory")).toHaveValue("");
  await expect(page.getByLabel("The most per hour, in ₹ (optional)")).toHaveValue("");
  await page.getByLabel("GPU memory").selectOption({ label: "At least 16 GB" });
  // Only the 24 GB RTX 4090 has 16 GB, and none of those is free.
  await expect(yourRequest(page).getByText("No GPU is free right now", { exact: true })).toBeVisible();
  await expectAccessible(page);
  await capture(page, info, "book-advanced");
});

test("Run your code: a folder, a command and the GPU memory it needs", async ({ page }, info) => {
  await fakeApi(page, api());
  await signIn(page, "asha", "/book/?service=run-code");
  await expect(page.getByRole("heading", { level: 1, name: "Run your code" })).toBeVisible();
  for (const step of ["1. Your code", "2. Your data (optional)", "3. Command", "4. GPU memory you need"]) {
    await expect(page.getByRole("heading", { name: step })).toBeVisible();
  }
  await expect(page.getByRole("textbox", { name: "Command" })).toHaveValue("python main.py");
  await expect(page.getByRole("radio", { name: "8 GB" })).toBeChecked();
  await expect(yourRequest(page).getByText("Best free 8 GB GPU")).toBeVisible();
  await page.getByRole("radio", { name: "12 GB" }).check();
  await expect(yourRequest(page).getByText("Best free 12 GB GPU")).toBeVisible();
  await expectAccessible(page);
  await capture(page, info, "book-run-code");
});

test("a file too large is refused before anything is sent", async ({ page }, info) => {
  const small = {
    ...fineTune,
    inputs: fineTune.inputs.map((input) => (input.name === "data" ? { ...input, maxSizeBytes: MIB } : input)),
  };
  await fakeApi(page, api({ "/v1/templates": { json: { ...data.templates, templates: [small] } } }));
  await signIn(page, "asha", FINE_TUNE);
  const created = page.waitForRequest("**/v1/uploads", { timeout: 1_000 }).catch(() => null);
  await page.getByTestId("data-file").setInputFiles({
    name: "chats.jsonl",
    mimeType: "application/jsonl",
    buffer: Buffer.alloc(2 * MIB, 7),
  });
  await expect(page.getByText("chats.jsonl is 2.0 MB. Your training data takes up to 1.0 MB.")).toBeVisible();
  expect(await created).toBeNull();
  await expectAccessible(page);
  await capture(page, info, "book-too-large");
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
  await signIn(page, "asha", FINE_TUNE);
  await expect(
    page.getByText("₹5.40 is held while it runs, and you have ₹4.60. Choose a lower budget.", {
      exact: false,
    }),
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
  await signIn(page, "asha", FINE_TUNE);
  await expect(yourRequest(page).getByText("No GPU is free right now", { exact: true })).toBeVisible();
  await expect(page.getByText("No GPU is free right now. We look again every 15 seconds.")).toBeVisible();
  await expect(request(page)).toBeDisabled();
  await expectAccessible(page);
  await capture(page, info, "book-none-free");
});

test("an answer the server refuses comes back on its field, in Advanced", async ({ page }, info) => {
  await fakeApi(
    page,
    api({
      "/v1/session-quotes": {
        status: 400,
        problem: "validation-failed",
        errors: [{ field: "params.learning_rate", detail: "must be at most 0.01" }],
      },
    }),
  );
  await signIn(page, "asha", FINE_TUNE);
  await expect(page.getByRole("button", { name: /^Advanced/ })).toHaveAttribute("aria-expanded", "true");
  await expect(page.getByText("Must be at most 0.01.")).toBeVisible();
  await expectAccessible(page);
  await capture(page, info, "book-refused-field");
});
