import { expect, test, type Locator, type Page } from "@playwright/test";
import * as data from "./fixtures";
import * as admin from "./fixtures-admin";
import { routes } from "./fixtures-admin-routes";
import * as images from "./fixtures-images";
import * as support from "./fixtures-support";
import { API, fakeApi, onPhone, signIn, typeToConfirm, watchConsole, type Answer } from "./support";
import { ADMIN_URL } from "./sites";

// The console is the admin site's alone (ADR 0058): every address here is on it.
test.use({ baseURL: ADMIN_URL });

/**
 * Every admin write has been pressed from a screen by some test (ADR 0057): what the other console specs and the role
 * journeys don't reach, here. Each opens the screen a person would use, does what they'd do, and checks the request went.
 * The API answers from fixtures; the journeys against the real stack are admin-roles.spec.ts.
 */

const USER = `/admin/users/?id=${admin.RENTER_ID}`;
const HOST = `/admin/hosts/?id=${admin.HOST_ID}`;

type Write = {
  write: string;
  screen: string;
  answers?: Record<string, Answer>;
  /** What a person does, up to and including the press that sends it. */
  does: (page: Page) => Promise<void>;
};

const press = (page: Page, name: string | RegExp) => page.getByRole("button", { name }).first().click();

/** Fills the sheet as a person would: its own fields first, a reason, what must be typed, then its confirm button. */
async function sheet(page: Page, fill?: (sheet: Locator) => Promise<void>): Promise<void> {
  const open = page.getByRole("dialog");
  await expect(open).toBeVisible();
  await fill?.(open);
  const reason = open.getByLabel("Reason");
  if ((await reason.count()) > 0) {
    await reason.fill("A reason a person would give");
  }
  if ((await open.getByLabel(/to confirm$/).count()) > 0) {
    await typeToConfirm(open);
  }
  await open.locator('button[type="submit"]').click();
}

const pressAndConfirm =
  (name: string | RegExp, fill?: (sheet: Locator) => Promise<void>) => async (page: Page) => {
    await press(page, name);
    await sheet(page, fill);
  };

const MINE = {
  id: "01a1a7bf-0000-7000-8000-00000000f001",
  name: "Mine",
  shared: false,
  mine: true,
  query: "",
};
const MACRO = {
  id: "01a1a7bf-0000-7000-8000-00000000f002",
  type: "ticket",
  name: "Fixed",
  number: 1,
  body: "Hello, it's fixed.",
  setStatus: null,
  addTag: null,
  setPriority: null,
  version: 1,
};
const NOTE = {
  id: "01a1a7bf-0000-7000-8000-00000000f003",
  body: "They called about this.",
  authorName: "Asha",
  authorId: data.me().id,
  pinned: false,
  mine: true,
  createdAt: data.NOW.toISOString(),
  editedAt: null,
  editable: true,
  mentions: [],
  version: 1,
};
const STEPS = { enforcements: [admin.enforcement], renterStrikes: 1, hostStrikes: 0, nextCursor: null };
const APPROVAL = admin.approvals.requests[0]!;

const WRITES: Write[] = [
  { write: "DELETE /session", screen: "/admin/", does: (page) => press(page, "Leave the console") },
  {
    write: "POST /views",
    screen: "/admin/users/",
    does: async (page) => {
      await page.getByLabel("Save this view as").fill("Mine");
      await page.getByRole("button", { name: "Save", exact: true }).click();
    },
  },
  {
    write: "DELETE /views/{id}",
    screen: "/admin/users/",
    answers: { "/admin/v1/views": { json: { views: [MINE] } } },
    does: (page) => press(page, "Delete “Mine”"),
  },
  { write: "POST /exports", screen: "/admin/users/", does: pressAndConfirm("Export CSV") },
  {
    write: "POST /adjustments/{id}/reverse",
    screen: "/admin/adjustments/",
    answers: { "/admin/v1/adjustments": { json: admin.adjustments } },
    does: pressAndConfirm(/^Reverse/, (open) => open.getByLabel("Reference").fill("Ticket 1042")),
  },
  {
    write: "POST /admins/sessions/{id}/end",
    screen: "/admin/security/",
    does: (page) =>
      page
        .getByRole("region", { name: "Everyone's sessions" })
        .getByRole("button", { name: /^End/ })
        .first()
        .click(),
  },
  {
    write: "POST /admins/{id}/roles/{role}/grant",
    screen: "/admin/security/",
    does: pressAndConfirm(/^Give /),
  },
  {
    write: "POST /admins/{id}/roles/{role}/remove",
    screen: "/admin/security/",
    does: async (page) => {
      await page
        .getByRole("region", { name: "Admins and their roles" })
        .getByRole("button", { name: /^Remove/ })
        .first()
        .click();
      await sheet(page);
    },
  },
  {
    write: "POST /appeals/{id}/attachments/{upload}/open",
    screen: `/admin/appeals/?id=${admin.appeal.id}`,
    answers: {
      [`/admin/v1/appeals/${admin.appeal.id}`]: {
        json: {
          appeal: {
            ...admin.appeal,
            attachments: [{ id: "01a1a7bf-0000-7000-8000-00000000f004", name: "receipt.pdf", size: 1024 }],
          },
          enforcement: admin.enforcement,
          history: [admin.enforcement],
        },
      },
    },
    does: pressAndConfirm(/^Open file/),
  },
  {
    write: "POST /appeals/{id}/decide",
    screen: `/admin/appeals/?id=${admin.appeal.id}`,
    does: pressAndConfirm("Keep the decision"),
  },
  { write: "POST /approvals/{id}/approve", screen: "/admin/approvals/", does: pressAndConfirm(/^Approve/) },
  { write: "POST /approvals/{id}/reject", screen: "/admin/approvals/", does: pressAndConfirm(/^Reject/) },
  {
    write: "POST /approvals/{id}/cancel",
    screen: "/admin/approvals/",
    answers: {
      "/admin/v1/approvals": {
        json: { requests: [{ ...APPROVAL, requestedBy: data.me().id, mine: true }], nextCursor: null },
      },
    },
    does: pressAndConfirm(/^Cancel/),
  },
  {
    write: "POST /approvals/{id}/download",
    screen: "/admin/approvals/",
    answers: {
      "/admin/v1/approvals": {
        json: { requests: [{ ...APPROVAL, state: "done", downloadable: true }], nextCursor: null },
      },
    },
    does: (page) => press(page, /^Fetch the file/),
  },
  {
    write: "POST /broadcasts/{id}/cancel",
    screen: "/admin/notices/",
    answers: { "/admin/v1/broadcasts": { json: admin.broadcasts } },
    does: pressAndConfirm(/^Cancel/),
  },
  {
    write: "POST /credit-grants",
    screen: USER,
    does: pressAndConfirm("Grant credit", (open) => open.getByLabel("Amount in ₹").fill("500")),
  },
  { write: "POST /credit-grants/{id}/reverse", screen: USER, does: pressAndConfirm(/^Reverse/) },
  {
    write: "POST /users/{id}/unsuspend",
    screen: USER,
    answers: { [`/admin/v1/users/${admin.RENTER_ID}`]: { json: { ...admin.renter, status: "suspended" } } },
    does: pressAndConfirm("Reinstate"),
  },
  {
    write: "POST /users/{id}/deletion/legal-order",
    screen: USER,
    does: pressAndConfirm("Erase under a legal order"),
  },
  {
    write: "POST /enforcements",
    screen: `${USER}&tab=enforcement`,
    answers: { "/admin/v1/enforcements": { json: STEPS } },
    does: pressAndConfirm("Take a step"),
  },
  {
    write: "POST /enforcements/{id}/lift",
    screen: `${USER}&tab=enforcement`,
    answers: { "/admin/v1/enforcements": { json: STEPS } },
    does: pressAndConfirm(/^Lift/),
  },
  { write: "POST /hosts/{id}/suspend", screen: HOST, does: pressAndConfirm(/^Suspend/) },
  { write: "POST /hosts/{id}/revoke-certificates", screen: HOST, does: pressAndConfirm(/^Revoke/) },
  {
    write: "POST /images/{id}/unblock",
    screen: "/admin/images/",
    answers: {
      "/admin/v1/images": {
        json: {
          items: [{ ...images.adminImages().items[0]!, state: "blocked", reason: "admin" }],
          nextCursor: null,
        },
      },
    },
    does: pressAndConfirm(/^Unblock/),
  },
  {
    write: "POST /notes",
    screen: `${USER}&tab=notes`,
    does: async (page) => {
      await page.getByRole("textbox", { name: "The note" }).fill("They called about this.");
      await press(page, "Add the note");
    },
  },
  {
    write: "POST /notes/{id}/edit",
    screen: `${USER}&tab=notes`,
    answers: { "/admin/v1/notes": { json: { notes: [NOTE] } } },
    does: async (page) => {
      await page.getByRole("list", { name: "Notes" }).getByRole("button", { name: "Change" }).click();
      await page
        .getByRole("list", { name: "Notes" })
        .getByRole("textbox", { name: "The note" })
        .fill("They called, twice.");
      await press(page, /^Save/);
    },
  },
  {
    write: "POST /notes/{id}/change",
    screen: `${USER}&tab=notes`,
    answers: { "/admin/v1/notes": { json: { notes: [NOTE] } } },
    does: (page) => press(page, /^Pin/),
  },
  {
    write: "POST /records/{subject}/{id}/tags/{tag}",
    screen: USER,
    does: async (page) => {
      await page.getByLabel(/add a tag/i).selectOption("vip");
    },
  },
  {
    write: "POST /records/{subject}/{id}/tags/{tag}/remove",
    screen: USER,
    answers: {
      "/admin/v1/records/[^/]+/[^/]+/tags": {
        json: { tags: [{ id: "vip", label: "VIP", colour: "running" }] },
      },
    },
    does: (page) => press(page, "Remove the tag VIP"),
  },
  {
    write: "POST /personal-data/reveal",
    screen: "/admin/verification/",
    does: pressAndConfirm(/^Reveal/),
  },
  {
    write: "POST /session/step-up",
    screen: "/admin/verification/",
    answers: { "/admin/v1/personal-data/reveal": { status: 403, problem: "step-up-required" } },
    does: async (page) => {
      await pressAndConfirm(/^Reveal/)(page);
      const ask = page.getByRole("dialog", { name: "Confirm it's you" });
      await ask.getByLabel("Code from your authenticator app").fill("123456");
      await ask.getByRole("button", { name: "Check the code" }).click();
    },
  },
  {
    write: "POST /passkeys/options",
    screen: "/admin/security/",
    does: async (page) => {
      await page.getByRole("region", { name: "Passkeys" }).getByRole("textbox").fill("My key");
      await press(page, "Add a passkey");
    },
  },
  {
    write: "POST /passkeys",
    screen: "/admin/security/",
    answers: {
      "/admin/v1/passkeys/options": {
        json: {
          challengeId: "01a1a7bf-0000-7000-8000-00000000f005",
          challenge: "AAAAAAAAAAAAAAAAAAAAAA",
          rpId: "localhost",
          rpName: "IdleO",
          userId: "AAAAAAAAAAAAAAAAAAAAAA",
          userName: "asha",
          userDisplayName: "Asha",
          algorithms: [-7],
          exclude: [],
          timeoutMs: 60_000,
        },
      },
    },
    does: async (page) => {
      const client = await page.context().newCDPSession(page);
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
      await page.getByRole("region", { name: "Passkeys" }).getByRole("textbox").fill("My key");
      await press(page, "Add a passkey");
    },
  },
  {
    write: "POST /session/passkey-options",
    screen: "/admin/",
    answers: { "/admin/v1/session": { json: { ...admin.enrolled, passkeys: true } } },
    does: (page) => press(page, /passkey/i),
  },
  {
    write: "POST /payments/reconciliation",
    screen: "/admin/payments/",
    does: pressAndConfirm("Run a day now"),
  },
  {
    write: "POST /payouts/reconciliation",
    screen: "/admin/payouts/",
    does: pressAndConfirm("Run a day now"),
  },
  { write: "POST /payouts/runs", screen: "/admin/payouts/", does: pressAndConfirm("Prepare a batch now") },
  {
    write: "POST /payouts/mismatches/{id}/accept",
    screen: "/admin/payouts/",
    does: pressAndConfirm(/^Accept/),
  },
  {
    write: "POST /payouts/batches/{id}/refresh",
    screen: `/admin/payouts/?batch=${admin.BATCH_ID}`,
    does: pressAndConfirm(/^Refresh the preview/),
  },
  {
    write: "POST /payouts/verifications/pans/{userId}/reject",
    screen: "/admin/verification/",
    does: pressAndConfirm(/^Reject/),
  },
  {
    write: "POST /payouts/verifications/upi-ids/{id}/approve",
    screen: "/admin/verification/",
    does: async (page) => {
      await page
        .getByRole("region", { name: /UPI/ })
        .getByRole("button", { name: /^(Approve|Verify)/ })
        .first()
        .click();
      await sheet(page);
    },
  },
  {
    write: "POST /payouts/verifications/upi-ids/{id}/reject",
    screen: "/admin/verification/",
    does: async (page) => {
      await page
        .getByRole("region", { name: /UPI/ })
        .getByRole("button", { name: /^Reject/ })
        .first()
        .click();
      await sheet(page);
    },
  },
  {
    write: "POST /periods/{month}/reopen",
    screen: "/admin/months/",
    answers: {
      "/admin/v1/periods": {
        json: {
          ...admin.periods,
          months: admin.periods.months.map((period) => ({
            ...period,
            state: "closed",
            closedAt: data.NOW.toISOString(),
          })),
        },
      },
    },
    does: pressAndConfirm(/^Reopen/),
  },
  {
    write: "POST /queue/macros",
    screen: "/admin/inbox/",
    does: async (page) => {
      const macros = page.getByRole("region", { name: "Macros" });
      await macros.getByLabel("Name", { exact: true }).first().fill("Fixed");
      await macros.getByRole("textbox").last().fill("Hello, it's fixed.");
      await macros.getByRole("button", { name: "Add" }).click();
    },
  },
  {
    write: "POST /queue/macros/{id}",
    screen: "/admin/inbox/",
    answers: { "/admin/v1/queue/macros": { json: { macros: [MACRO], nextCursor: null } } },
    does: pressAndConfirm(/^Change Fixed/),
  },
  {
    write: "POST /queue/macros/{id}/retire",
    screen: "/admin/inbox/",
    answers: { "/admin/v1/queue/macros": { json: { macros: [MACRO], nextCursor: null } } },
    does: pressAndConfirm(/^Retire/),
  },
  {
    write: "POST /queue/sla/{sla}",
    screen: "/admin/inbox/",
    does: async (page) => {
      await page
        .getByRole("region", { name: "Targets" })
        .getByRole("button", { name: /^Change/ })
        .first()
        .click();
      await sheet(page);
    },
  },
  {
    write: "POST /queue/{id}/assign",
    screen: "/admin/inbox/",
    answers: {
      "/admin/v1/notes/mentionable": { json: { admins: [{ id: data.me().id, name: "Asha" }] } },
    },
    does: pressAndConfirm(/^Assign/),
  },
  {
    write: "POST /queue/{id}/macro",
    screen: "/admin/inbox/",
    answers: {
      "/admin/v1/queue/macros": { json: { macros: [MACRO], nextCursor: null } },
      "/admin/v1/queue/[^/]+/macros/[^/]+": {
        json: { body: MACRO.body, setStatus: null, addTag: null, setPriority: null },
      },
    },
    does: pressAndConfirm(/^Macro/),
  },
  { write: "POST /queue/{id}/priority", screen: "/admin/inbox/", does: pressAndConfirm(/^Priority of/) },
  {
    write: "POST /queue/bulk/{id}/undo",
    screen: "/admin/users/",
    answers: {
      "/admin/v1/queue/bulk/preview": { json: admin.bulkPreviewOfAList },
      "/admin/v1/queue/bulk": { json: admin.bulkRunning },
      [`/admin/v1/queue/bulk/${admin.bulkRunning.id}`]: { json: admin.bulkRunning },
    },
    does: async (page) => {
      await page
        .getByRole("checkbox", { name: /^Select this row/ })
        .first()
        .check();
      await page.getByRole("region", { name: "1 selected" }).getByLabel("Tag").fill("vip");
      await press(page, "Preview");
      await sheet(page);
      await pressAndConfirm("Undo")(page);
    },
  },
  { write: "POST /reconciliation", screen: "/admin/money/", does: pressAndConfirm("Run the check now") },
  { write: "POST /refunds/{id}/decline", screen: "/admin/refunds/", does: pressAndConfirm(/^Decline/) },
  {
    write: "POST /review-minutes/{sessionId}/{minuteIndex}/charge",
    screen: "/admin/review/",
    does: pressAndConfirm(/^Charge/),
  },
  {
    write: "POST /review-minutes/{sessionId}/{minuteIndex}/waive",
    screen: "/admin/review/",
    does: pressAndConfirm(/^Waive/),
  },
  {
    write: "POST /sessions/{id}/stop",
    screen: `/admin/sessions/?id=${admin.SESSION_ID}`,
    does: pressAndConfirm(/^Force/),
  },
  {
    write: "POST /settlements/fetch",
    screen: "/admin/settlements/",
    does: pressAndConfirm("Fetch a day now"),
  },
  {
    write: "POST /tags",
    screen: "/admin/security/",
    does: async (page) => {
      const tags = page.getByRole("region", { name: "Tags" });
      await tags.getByLabel("Its short name").fill("partner");
      await tags.getByLabel("What it says").fill("Partner");
      await press(page, "Add or change the tag");
    },
  },
  {
    write: "POST /tickets/{id}/status",
    screen: `/admin/tickets/?id=${support.PRIVACY_TICKET}`,
    does: pressAndConfirm(/^Mark /),
  },
];

test.describe("every admin write from its screen", () => {
  test.beforeEach(async ({ page }, info) => {
    test.skip(
      onPhone(info) || info.project.name.includes("dark"),
      "the action is the same in each: once, on a laptop",
    );
    await page.clock.setFixedTime(data.NOW);
  });

  for (const { write, screen, answers, does } of WRITES) {
    test(write, async ({ page }) => {
      const finish = watchConsole(page);
      const [method, template] = write.split(" ") as [string, string];
      const path = new RegExp(`^/admin/v1${template.replace(/\{[^}]+\}/g, "[^/]+")}$`);
      const sent = page.waitForRequest(
        (request) => request.method() === method && path.test(new URL(request.url()).pathname),
      );
      await fakeApi(page, routes(answers));
      // Whatever a write isn't told to answer succeeds with nothing: this is about the press, the sheets are tested
      // by admin-screens.spec.ts.
      await page.route(`${API}/admin/v1/**`, async (route) => {
        const asked = route.request();
        const known =
          answers &&
          Object.keys(answers).some((key) => new RegExp(`^${key}$`).test(new URL(asked.url()).pathname));
        if (["POST", "PUT", "DELETE"].includes(asked.method()) && !known) {
          await route.fulfill({
            status: 200,
            json: {},
            headers: { "Access-Control-Allow-Origin": asked.headers().origin ?? "*" },
          });
          return;
        }
        await route.fallback();
      });
      await signIn(page, "asha", screen);
      await does(page);
      await sent;
      finish();
    });
  }
});
