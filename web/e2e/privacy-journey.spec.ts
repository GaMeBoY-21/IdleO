import { expect, test, type APIRequestContext } from "@playwright/test";
import { writeFileSync } from "node:fs";
import { expectAccessible, signIn, watchConsole } from "./support";

/**
 * A person's own data, on the real stack (ADR 0037, scripts/e2e/agent-online.sh): Your data shows what we hold, the
 * file of everything is made by the job, emailed, and downloaded; a message to support gets its number by email; the
 * web journeys' renter, who by now owns a machine with unpaid earnings, can't delete their account and is told why;
 * and E2E_PRIVACY_USER, with nothing in the way, asks after a fresh sign-in, confirms from the emailed link, and
 * cancels with one click from the next email, which leaves the account as it was. The messages are read from
 * Mailpit's API. The ticket's number goes to E2E_PRIVACY_REPORT, for the admin journey to answer.
 */
const who = process.env.E2E_WEB_USER ?? "";
const deleting = process.env.E2E_PRIVACY_USER ?? "";
const mailApi = process.env.E2E_MAIL_API ?? "http://localhost:8025/api/v1";
const report = process.env.E2E_PRIVACY_REPORT ?? "";

test.describe.configure({ mode: "serial" });

interface Mail {
  subject: string;
  text: string;
}

/** The newest email to the person whose subject starts so, waiting up to a minute for it. */
async function email(
  request: APIRequestContext,
  subject: string,
  after: number,
  person = who,
): Promise<Mail> {
  const address = `${person}@example.com`;
  for (let tries = 0; tries < 60; tries++) {
    const list = (await (await request.get(`${mailApi}/messages?limit=200`)).json()) as {
      messages: { ID: string; Subject: string; Created: string; To: { Address: string }[] }[];
    };
    const found = list.messages.find(
      (message) =>
        message.To.some((to) => to.Address.toLowerCase() === address) &&
        message.Subject.startsWith(subject) &&
        new Date(message.Created).getTime() >= after,
    );
    if (found) {
      const full = (await (await request.get(`${mailApi}/message/${found.ID}`)).json()) as {
        Subject: string;
        Text: string;
      };
      return { subject: full.Subject, text: full.Text };
    }
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
  throw new Error(`no email to ${address} starting "${subject}"`);
}

/** The app's own path in an email's link. */
const pathIn = (text: string, part: string) => {
  const link = text.match(new RegExp(`https?://[^\\s]*${part}[^\\s]*`))?.[0];
  expect(link, `a link to ${part}`).toBeTruthy();
  const url = new URL(link!);
  return `${url.pathname}${url.search}`;
};

test("what we hold, and the file of all of it, made, emailed and downloaded", async ({ page, request }) => {
  expect(who, "E2E_WEB_USER").not.toBe("");
  const finish = watchConsole(page);
  const started = Date.now() - 1000;
  await signIn(page, who, "/account/data/");
  await expect(page.getByRole("heading", { level: 1, name: "Your data" })).toBeVisible();
  await expect(page.getByText(/\d+ sessions,/)).toBeVisible();
  await expectAccessible(page);

  await page.getByRole("button", { name: "Make my file" }).click();
  await expect(page.getByText("Being made. We'll email you when it's ready.")).toBeVisible();
  await expect(page.getByText(/^Ready, .* Kept until /)).toBeVisible({ timeout: 90_000 });
  const ready = await email(request, "Your data is ready to download", started);
  expect(ready.text).toContain("/account/data/");

  const download = page.waitForEvent("download");
  await page.getByRole("button", { name: "Download" }).click();
  const file = await download;
  expect(file.suggestedFilename()).toMatch(/^idleo-data-\d{4}-\d{2}-\d{2}\.zip$/);
  const saved = await file.path();
  const { readFileSync } = await import("node:fs");
  expect(readFileSync(saved).subarray(0, 2).toString("latin1")).toBe("PK");
  finish();
});

test("a message to support is acknowledged by email with its number", async ({ page, request }) => {
  const finish = watchConsole(page);
  const started = Date.now() - 1000;
  await signIn(page, who, "/account/help/");
  await page.getByLabel("What it's about").selectOption("privacy");
  await page.getByLabel("Your message", { exact: true }).fill("Please tell me what you hold about me.");
  await page.waitForTimeout(3500);
  await page.getByRole("button", { name: "Send", exact: true }).click();
  const sent = await page.getByText(/^Sent as #\d+\./).textContent();
  const number = sent!.match(/#(\d+)/)![1]!;
  const ack = await email(request, `We've got your message (#${number})`, started);
  expect(ack.text).toContain("Reply here, not by email");
  if (report !== "") {
    writeFileSync(report, JSON.stringify({ ticketNumber: Number(number) }));
  }
  await expect(page.getByText(`#${number} · A privacy request`)).toBeVisible();
  finish();
});

test("a host with earnings not paid out is told why their account can't be deleted yet", async ({ page }) => {
  const finish = watchConsole(page);
  await signIn(page, who, "/account/delete/");
  await expect(
    page.getByText(/Your machines have earned ₹[\d.,]+ that hasn't been paid out yet\./),
  ).toBeVisible();
  await expect(page.getByRole("button", { name: "Delete my account", exact: true })).toBeDisabled();
  await expectAccessible(page);
  finish();
});

test("deleting the account: a fresh sign-in, the emailed link, then one click from the next email cancels it", async ({
  page,
  request,
}) => {
  expect(deleting, "E2E_PRIVACY_USER").not.toBe("");
  const finish = watchConsole(page);
  const started = Date.now() - 1000;
  await signIn(page, deleting, "/account/delete/");
  await expect(page.getByText("We erase", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Delete my account", exact: true }).click();
  await expect(page.getByText("Check your email: we sent you a link to confirm.")).toBeVisible();

  const confirm = await email(request, "Confirm deleting your account", started, deleting);
  await page.goto(pathIn(confirm.text, "/account/delete/confirm/"));
  await page.getByRole("button", { name: "Confirm: delete my account" }).click();
  await expect(page.getByRole("heading", { name: "Your account will be deleted" })).toBeVisible();

  const scheduled = await email(request, "Your account will be deleted on", started, deleting);
  await page.goto(pathIn(scheduled.text, "/account/delete/cancel/"));
  await expect(page.getByRole("heading", { name: "Your account stays" })).toBeVisible();

  await page.goto("/account/delete/");
  await expect(page.getByRole("button", { name: "Delete my account", exact: true })).toBeVisible();
  finish();
});
