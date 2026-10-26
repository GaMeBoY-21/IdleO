import { expect, test, type Page } from "@playwright/test";
import * as data from "./fixtures";
import * as images from "./fixtures-images";
import { capture, expectAccessible, fakeApi, signIn, watchConsole, type Answer } from "./support";

/**
 * A renter's own images (ADR 0042), every state, light and dark, phone and laptop: Account, My images (each check's
 * progress, what it found with its warnings, blocked and not checked, no verified PAN, nothing yet, an error), the
 * known-vulnerabilities list, Remove; and Book's "Use my own image" step (a checked image to choose, a new reference to
 * check, a private registry's token, the check's progress and warnings, and why it can't be used without a PAN).
 * Zero axe violations, no console errors. The API answers from fixtures.
 */
const OWN_IMAGE = "/book/?service=run-code&template=custom-image";
type QuoteBody = { maxPricePaisePerMin?: number | null; maxSpendPaise: number; vramNeededMb?: number };

const signedIn = (overrides: Record<string, Answer> = {}): Record<string, Answer> => ({
  "/v1/me": { json: data.me(), etag: '"3"' },
  "/v1/me/notifications": { json: data.notificationChoices() },
  "/v1/templates": { json: data.templates },
  "/v1/wallet": { json: data.asha.wallet },
  "/v1/sessions": { json: data.sessionsPage(data.asha.sessions) },
  "/v1/availability": { json: data.availability },
  "/v1/uploads": { json: data.uploadsPage() },
  "/v1/session-quotes": (_call, request) => data.quoteAnswer(request.postDataJSON() as QuoteBody),
  "/v1/images": { json: images.imageList() },
  ...overrides,
});

let finish: () => void;
test.beforeEach(async ({ page }) => {
  finish = watchConsole(page);
  await page.clock.setFixedTime(data.NOW);
});
test.afterEach(() => finish());

test.describe("My images", () => {
  test("lists each image with where its check stands and what it found", async ({ page }, info) => {
    await fakeApi(page, signedIn());
    await signIn(page, "asha", "/account/images/");
    await expect(page.getByRole("heading", { level: 1, name: "My images" })).toBeVisible();
    const items = page.getByRole("listitem").filter({ hasText: /ghcr\.io|nvcr\.io|docker\.io|quay\.io/ });
    await expect(items).toHaveCount(4);
    await expect(items.nth(0)).toContainText("ghcr.io/asha/train:v3");
    await expect(items.nth(0)).toContainText("Ready");
    await expect(items.nth(0)).toContainText("8.4 GB");
    await expect(items.nth(0)).toContainText(
      "Your image seems to contain an AWS key in /app/.env. Anyone with this image can read it.",
    );
    await expect(items.nth(0)).toContainText("Your image runs as root.");
    await expect(items.nth(1)).toContainText("Downloading your image · 3.1 of 8.4 GB");
    await expect(items.nth(2)).toContainText("Blocked");
    await expect(items.nth(2)).toContainText("isn't allowed on IdleO");
    await expect(items.nth(3)).toContainText("Not checked");
    await expect(items.nth(3)).toContainText("can't be booked until a check completes");
    await expectAccessible(page);
    await capture(page, info, "my-images");
  });

  test("the known vulnerabilities of an image are listed, most severe first", async ({ page }, info) => {
    await fakeApi(
      page,
      signedIn({
        "/v1/images/[^/]+/vulnerabilities": { json: images.vulnerabilities },
      }),
    );
    await signIn(page, "asha", `/account/images/?id=${images.readyImage.id}`);
    await expect(page.getByRole("heading", { name: "Known vulnerabilities" })).toBeVisible();
    await expect(page.getByText("1 critical, 1 high, 0 medium, 0 low.", { exact: false })).toBeVisible();
    const found = page.getByRole("listitem").filter({ hasText: "CVE-" });
    await expect(found.nth(0)).toContainText("critical · CVE-2024-0001 · openssl 3.0.2");
    await expect(found.nth(0)).toContainText("Fixed in 3.0.13.");
    await expect(found.nth(1)).toContainText("high · CVE-2024-0042 · zlib 1.2.11");
    await expectAccessible(page);
    await capture(page, info, "my-images-vulnerabilities");
  });

  test("without a verified PAN it says why and where to give one", async ({ page }, info) => {
    await fakeApi(page, signedIn({ "/v1/images": { json: images.noPan } }));
    await signIn(page, "asha", "/account/images/");
    await expect(
      page.getByText("Your own images are for renters with a verified PAN.", { exact: false }),
    ).toBeVisible();
    await expect(page.getByRole("link", { name: "Give your PAN" })).toHaveAttribute(
      "href",
      "/hosting/payout-details/",
    );
    await expect(page.getByText("No images yet")).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "my-images-no-pan");
  });

  test("with nothing yet it says how to check one", async ({ page }, info) => {
    await fakeApi(page, signedIn({ "/v1/images": { json: images.imageList([]) } }));
    await signIn(page, "asha", "/account/images/");
    await expect(page.getByText("No images yet")).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "my-images-empty");
  });

  test("loading shows the rows' own height, and an error offers a retry", async ({ page }, info) => {
    await fakeApi(page, signedIn({ "/v1/images": "hold" }));
    await signIn(page, "asha", "/account/images/");
    await expect(page.getByRole("status", { name: "Loading your images" })).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "my-images-loading");

    await page.unroute(/.*/);
    await fakeApi(page, signedIn({ "/v1/images": { status: 500, problem: "server-error" } }));
    await page.goto("/account/images/");
    await expect(page.getByRole("button", { name: "Try again" })).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "my-images-error");
  });

  test("Remove asks twice, then the image is gone", async ({ page }) => {
    let removed = false;
    await fakeApi(
      page,
      signedIn({
        "/v1/images": () => ({
          json: images.imageList(
            removed ? [images.downloadingImage] : [images.readyImage, images.downloadingImage],
          ),
        }),
        "/v1/images/[^/]+": (_call, request) => {
          if (request.method() === "DELETE") {
            removed = true;
            return { json: {}, status: 204 };
          }
          return { json: images.readyImage };
        },
      }),
    );
    await signIn(page, "asha", "/account/images/");
    const row = page.getByRole("listitem").filter({ hasText: "ghcr.io/asha/train:v3" });
    await row.getByRole("button", { name: "Remove" }).click();
    await expect(row.getByRole("button", { name: "Remove it" })).toBeVisible();
    await row.getByRole("button", { name: "Remove it" }).click();
    await expect(page.getByText("ghcr.io/asha/train:v3")).toHaveCount(0);
  });

  test("is reached from Account", async ({ page }) => {
    await fakeApi(page, signedIn());
    await signIn(page, "asha", "/account/");
    await page
      .getByRole("link", { name: /My images/ })
      .first()
      .click();
    await expect(page).toHaveURL(/\/account\/images\/$/);
    await expect(page.getByRole("heading", { level: 1, name: "My images" })).toBeVisible();
  });
});

const imageStep = (page: Page) => page.getByRole("region", { name: "Your image" });

test.describe("Book: Use my own image", () => {
  test("offers the checked images, and choosing one fills the request", async ({ page }, info) => {
    await fakeApi(page, signedIn());
    await signIn(page, "asha", OWN_IMAGE);
    await expect(page.getByRole("heading", { name: "Your image" })).toBeVisible();
    const ready = imageStep(page).getByRole("button", { name: /ghcr\.io\/asha\/train:v3/ });
    await expect(ready).toContainText("CUDA 12.4");
    await expect(imageStep(page).getByRole("button", { name: /nvcr\.io/ })).toHaveCount(0);
    await expectAccessible(page);
    await capture(page, info, "book-own-image");
    await ready.click();
    await expect(ready).toHaveAttribute("aria-pressed", "true");
    await expectAccessible(page);
    await capture(page, info, "book-own-image-chosen");
  });

  test("a new reference is checked, shows its progress, and is chosen once it's ready", async ({
    page,
  }, info) => {
    let polls = 0;
    await fakeApi(
      page,
      signedIn({
        "/v1/images": (_call, request) =>
          request.method() === "POST"
            ? { json: { ...images.downloadingImage, id: images.readyImage.id }, status: 201 }
            : { json: images.imageList([]) },
        "/v1/images/[^/]+": () => {
          polls += 1;
          return {
            json:
              polls < 2
                ? images.downloadingImage
                : { ...images.readyImage, reference: images.downloadingImage.reference },
          };
        },
      }),
    );
    await signIn(page, "asha", OWN_IMAGE);
    await imageStep(page).getByLabel("Your image").fill("nvcr.io/nvidia/pytorch:24.10-py3");
    await imageStep(page).getByRole("button", { name: "Check it" }).click();
    await expect(imageStep(page).getByText("Downloading your image · 3.1 of 8.4 GB")).toBeVisible();
    await capture(page, info, "book-own-image-checking");
    await expect(imageStep(page).getByText("Ready", { exact: true })).toBeVisible();
    await expect(imageStep(page).getByText("Your image runs as root.", { exact: false })).toBeVisible();
    await expectAccessible(page);
    await capture(page, info, "book-own-image-ready");
  });

  test("a private registry takes a user name and a read-only token, which is never shown back", async ({
    page,
  }) => {
    let sent: unknown = null;
    await fakeApi(
      page,
      signedIn({
        "/v1/images": (_call, request) => {
          if (request.method() === "POST") {
            sent = request.postDataJSON();
            return { json: { ...images.downloadingImage, privateRegistry: true }, status: 201 };
          }
          return { json: images.imageList([]) };
        },
        "/v1/images/[^/]+": { json: { ...images.downloadingImage, privateRegistry: true } },
      }),
    );
    await signIn(page, "asha", OWN_IMAGE);
    const step = imageStep(page);
    await step.getByLabel("Your image").fill("ghcr.io/asha/private:v1");
    await step.getByLabel("It's in a private registry").check();
    await step.getByLabel("User name").fill("asha");
    await step.getByLabel("Read-only token").fill("ghp_readonly_secret");
    await step.getByRole("button", { name: "Check it" }).click();
    await expect(step.getByText("Downloading your image", { exact: false })).toBeVisible();
    expect(sent).toEqual({
      reference: "ghcr.io/asha/private:v1",
      registryUsername: "asha",
      registryToken: "ghp_readonly_secret",
    });
    await expect(page.getByText("ghp_readonly_secret")).toHaveCount(0);
    await expect(step.getByLabel("Read-only token")).toHaveValue("");
  });

  test("without a verified PAN it says why instead of offering the form", async ({ page }, info) => {
    await fakeApi(page, signedIn({ "/v1/images": { json: images.noPan } }));
    await signIn(page, "asha", OWN_IMAGE);
    await expect(
      imageStep(page).getByText("Your own images are for renters with a verified PAN.", { exact: false }),
    ).toBeVisible();
    await expect(imageStep(page).getByRole("button", { name: "Check it" })).toHaveCount(0);
    await expectAccessible(page);
    await capture(page, info, "book-own-image-no-pan");
  });

  test("Run your code has the choice between its own template and your image", async ({ page }) => {
    await fakeApi(page, signedIn());
    await signIn(page, "asha", "/book/?service=run-code");
    const which = page.getByRole("group", { name: "Which one" });
    await expect(which.getByRole("button", { name: /Use my own image/ })).toContainText(
      "Your own container image",
    );
    await which.getByRole("button", { name: /Use my own image/ }).click();
    await expect(page).toHaveURL(/template=custom-image/);
    await expect(page.getByRole("heading", { name: "Your image" })).toBeVisible();
  });
});
