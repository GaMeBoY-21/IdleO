import { createHmac } from "node:crypto";
import { expect, test, type CDPSession, type Page } from "@playwright/test";
import { signIn } from "./support";

/**
 * Who sees what in the console, against the real stack (ADR 0051), run with the admin journey by
 * scripts/e2e/agent-online.sh: an Owner, a Support admin and an Auditor each go through their screens and find only what
 * they may. The Owner then adds a passkey on a virtual authenticator and signs in with it.
 */
const owner = process.env.E2E_PASSKEY_ADMIN;
const support = process.env.E2E_SUPPORT_ADMIN;
const auditor = process.env.E2E_AUDITOR_ADMIN;

function codeNow(secret: string, ahead = 0): string {
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  let bits = "";
  for (const char of secret) {
    bits += alphabet.indexOf(char).toString(2).padStart(5, "0");
  }
  const key = Buffer.from(bits.match(/.{8}/g)!.map((byte) => Number.parseInt(byte, 2)));
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(Math.floor(Date.now() / 1000 / 30) + ahead));
  const hash = createHmac("sha1", key).update(counter).digest();
  const offset = hash[19]! & 0x0f;
  return ((hash.readUInt32BE(offset) & 0x7fffffff) % 1_000_000).toString().padStart(6, "0");
}

async function enrol(page: Page): Promise<string> {
  await expect(page.getByRole("heading", { level: 1, name: "Set up your authenticator" })).toBeVisible();
  const secret = (await page.getByText(/^[A-Z2-7]{32}$/).textContent())!;
  await page.getByLabel("Code from your authenticator app").fill(codeNow(secret));
  await page.getByRole("button", { name: "Confirm" }).click();
  await page.getByRole("button", { name: "I've saved them" }).click();
  return secret;
}

/** The console's items, once they're drawn. */
async function sections(page: Page): Promise<string[]> {
  await expect(page.getByRole("navigation", { name: "Admin" }).getByRole("link").first()).toBeVisible();
  return page.getByRole("navigation", { name: "Admin" }).getByRole("link").allInnerTexts();
}

test.describe("each admin sees only what they may", () => {
  test.skip(!owner || !support || !auditor, "run by scripts/e2e/agent-online.sh");

  test("Support has their sections, and an action they can't take says why", async ({ page }) => {
    await signIn(page, support!, "/admin/");
    const secret = await enrol(page);
    await expect(page.getByRole("navigation", { name: "Admin" })).toBeVisible();
    const shown = await sections(page);
    expect(shown).toContain("Users");
    expect(shown).toContain("Tickets");
    expect(shown).toContain("Your access");
    expect(shown).not.toContain("Audit log");
    expect(shown).not.toContain("Money");
    expect(shown).not.toContain("Payouts");

    // An address typed by hand says which permission is missing, and asks the server for nothing.
    await page.goto("/admin/audit/");
    await expect(page.getByRole("heading", { level: 1, name: "Admin sign-in" })).toBeVisible();
    await page.getByLabel("Code from your authenticator app").fill(codeNow(secret, 1));
    await page.getByRole("button", { name: "Continue" }).click();
    await expect(page.getByRole("heading", { level: 1, name: "You can't open this section" })).toBeVisible();
    await expect(page.getByText("audit.read")).toBeVisible();
  });

  test("the Auditor reads the audit log and can change nothing", async ({ page }) => {
    await signIn(page, auditor!, "/admin/");
    await enrol(page);
    await expect(page.getByRole("navigation", { name: "Admin" })).toBeVisible();
    const shown = await sections(page);
    expect(shown).toContain("Audit log");
    expect(shown).toContain("Money");
    await page.getByRole("navigation", { name: "Admin" }).getByRole("link", { name: "Audit log" }).click();
    await expect(page.getByRole("table", { name: "Audit log" })).toBeVisible();
    await page.getByRole("navigation", { name: "Admin" }).getByRole("link", { name: "Users" }).click();
    await page.getByRole("link", { name: /^Open/ }).first().click();
    const suspend = page.getByRole("button", { name: /^(Suspend|Reinstate)$/ });
    await expect(suspend).toBeDisabled();
    await expect(suspend).toHaveAttribute("title", /permission/);
  });

  test("the Owner sees everything, makes a passkey on a virtual authenticator, and signs in with it", async ({
    page,
    context,
  }) => {
    test.setTimeout(180_000);
    const client: CDPSession = await context.newCDPSession(page);
    await client.send("WebAuthn.enable");
    await client.send("WebAuthn.addVirtualAuthenticator", {
      options: {
        protocol: "ctap2",
        transport: "internal",
        hasResidentKey: true,
        hasUserVerification: true,
        isUserVerified: true,
      },
    });

    await signIn(page, owner!, "/admin/");
    await enrol(page);
    await expect(page.getByRole("navigation", { name: "Admin" })).toBeVisible();
    expect((await sections(page)).length).toBeGreaterThan(20);

    await page.getByRole("navigation", { name: "Admin" }).getByRole("link", { name: "Your access" }).click();
    await page.getByLabel("Name this passkey").fill("Virtual key");
    await page.getByRole("button", { name: "Add a passkey" }).click();
    await expect(page.getByRole("table", { name: "Your passkeys" })).toContainText("Virtual key");

    await page.reload();
    await expect(page.getByRole("button", { name: "Use my passkey" })).toBeVisible();
    await page.getByRole("button", { name: "Use my passkey" }).click();
    await expect(page.getByRole("navigation", { name: "Admin" })).toBeVisible();

    // The code stays as the fallback.
    await page.reload();
    await page.getByRole("button", { name: "Use a code instead" }).click();
    await expect(page.getByLabel("Code from your authenticator app")).toBeVisible();
  });
});

test.describe("finding anything", () => {
  const finder = process.env.E2E_FIND_ADMIN;
  const person = process.env.E2E_WEB_USER;
  test.skip(!finder || !person, "run by scripts/e2e/agent-online.sh");

  test("Ctrl-K finds a person by their whole email, whose page has a timeline, a note, a tag and linked accounts", async ({
    page,
  }) => {
    test.setTimeout(180_000);
    await signIn(page, finder!, "/admin/");
    await enrol(page);
    await expect(page.getByRole("navigation", { name: "Admin" })).toBeVisible();

    await page.keyboard.press("Control+k");
    await page.getByRole("combobox", { name: "Search the console" }).fill(`${person}@example.com`);
    const found = page.getByRole("group", { name: "Users" }).getByRole("option").first();
    await expect(found).toBeVisible();
    // Masked in the results as everywhere else.
    await expect(found).not.toContainText(`${person}@example.com`);
    await page.keyboard.press("Enter");
    await expect(page.getByRole("heading", { level: 1, name: "User" })).toBeVisible();

    // The one timeline, by kind.
    await expect(page.getByRole("list", { name: "Timeline" })).toBeVisible();
    await page.getByRole("button", { name: "security", exact: true }).click();
    await expect(page.getByRole("list", { name: "Timeline" }).getByText("sign in").first()).toBeVisible();

    // A note, pinned, shows at the top; a tag goes on and stays.
    await page.getByRole("tab", { name: "Notes" }).click();
    await page.getByLabel("The note").first().fill("Called about a **refund**");
    await page.getByLabel("Pin it to the top").check();
    await page.getByRole("button", { name: "Add the note" }).click();
    await expect(page.getByRole("list", { name: "Notes" }).getByText("refund")).toBeVisible();
    await expect(page.getByRole("alert").or(page.getByRole("status")).getByText("refund").first()).toBeVisible();
    await page.getByLabel("Add a tag").selectOption("vip");
    await expect(page.getByRole("group", { name: "Tags" }).getByText("VIP")).toBeVisible();

    // Trust & Safety's lead: this browser and address signed in as both of them.
    await page.getByRole("tab", { name: "Linked accounts" }).click();
    await expect(page.getByRole("list", { name: "Linked accounts" }).getByText("same browser").first()).toBeVisible();

    // The address says which tab: it can be linked.
    await expect(page).toHaveURL(/tab=linked/);
  });
});
