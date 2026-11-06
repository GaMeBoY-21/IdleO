import { createHmac } from "node:crypto";
import { expect, test } from "@playwright/test";
import { signIn } from "./support";

/**
 * The inbox against the real stack (ADR 0053), run with the admin journey by scripts/e2e/agent-online.sh, which has put a
 * ticket in it and written a macro: an admin finds it, claims it, answers it with the macro, and it's resolved.
 */
const admin = process.env.E2E_QUEUES_ADMIN;
const ticket = process.env.E2E_QUEUES_TICKET;
const macro = process.env.E2E_QUEUES_MACRO;

function codeNow(secret: string): string {
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  let bits = "";
  for (const char of secret) {
    bits += alphabet.indexOf(char).toString(2).padStart(5, "0");
  }
  const key = Buffer.from(bits.match(/.{8}/g)!.map((byte) => Number.parseInt(byte, 2)));
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(Math.floor(Date.now() / 1000 / 30)));
  const hash = createHmac("sha1", key).update(counter).digest();
  const offset = hash[19]! & 0x0f;
  return ((hash.readUInt32BE(offset) & 0x7fffffff) % 1_000_000).toString().padStart(6, "0");
}

test("inbox, claim, a macro's reply, resolved", async ({ page }) => {
  test.skip(!admin || !ticket || !macro, "run by scripts/e2e/agent-online.sh");
  await signIn(page, admin!, "/admin/inbox/");
  await expect(page.getByRole("heading", { level: 1, name: "Set up your authenticator" })).toBeVisible();
  const secret = (await page.getByText(/^[A-Z2-7]{32}$/).textContent())!;
  await page.getByLabel("Code from your authenticator app").fill(codeNow(secret));
  await page.getByRole("button", { name: "Confirm" }).click();
  await page.getByRole("button", { name: "I've saved them" }).click();

  // In the page, not by loading it again: a reload asks for a code again (the admin session is held in memory only).
  await expect(page.getByRole("heading", { level: 1, name: "Inbox" })).toBeVisible();
  await page.getByLabel("Kind of work").selectOption("ticket");
  const row = page.getByRole("row").filter({ hasText: ticket! });
  await expect(row).toBeVisible({ timeout: 30_000 });
  await row.getByRole("button", { name: /^Claim/ }).click();
  await expect(row).toContainText("is on it");

  await row.getByRole("button", { name: /^Macro/ }).click();
  const sheet = page.getByRole("dialog");
  await sheet.getByLabel("Macro", { exact: true }).selectOption({ label: macro! });
  await expect(sheet).toContainText("it's fixed");
  await sheet.getByRole("textbox", { name: "Reason" }).fill("answering with the macro");
  await sheet.getByRole("button", { name: "Apply it" }).click();
  await expect(sheet).toBeHidden();
  await expect(page.getByRole("row").filter({ hasText: ticket! })).toHaveCount(0, { timeout: 30_000 });
});
