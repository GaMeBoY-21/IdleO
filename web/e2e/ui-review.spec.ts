import { expect, test, type Page, type TestInfo } from "@playwright/test";
import { readdirSync, readFileSync } from "node:fs";
import { SCREENS as ADMIN_SCREENS } from "../src/sites/admin/screens";
import { SCREENS as APP_SCREENS } from "../src/sites/app/screens";
import { HOST_PATHS, laptopPath, laptopSessionsPath, pricePath } from "../src/sites/host/paths";
import { SCREENS as HOST_SCREENS } from "../src/sites/host/screens";
import { SHARED_SCREEN_MODULES } from "../src/sites/screen-modules";
import * as data from "./fixtures";
import * as admin from "./fixtures-admin";
import { routes as adminApi } from "./fixtures-admin-routes";
import * as hosting from "./fixtures-hosting";
import { hostApi, renterApi, RUNNING, websiteApi } from "./fixtures-sites";
import * as support from "./fixtures-support";
import { ADMIN_URL, APP_URL, HOST_URL } from "./sites";
import { fakeApi, recordLayoutShifts, signIn, type Answer } from "./support";
import { review, type ReviewSite } from "./ui-review-capture";

/**
 * The UI review's sweep (web/e2e/ui-review.ts): every page of every site, in each state its data can be in, at the
 * project's width and theme, pictured and checked by e2e/ui-audit.ts. Only when UI_REVIEW_DIR is set; it gates nothing.
 *
 * The states come from the sites' fixtures (e2e/fixtures-sites.ts, the console's routes()), changed in one place:
 * - full: as they are;
 * - loading: every request but the frame's held, never answered;
 * - error: every request but the frame's answered 503, so each screen's error state shows once its retries are spent;
 * - empty: every list in every answer emptied;
 * - long: every name, title and description made long, with one long unbroken word, and every email long;
 * - many: every list in every answer made 60 long.
 */
test.skip(!process.env.UI_REVIEW_DIR, "only for the UI review (UI_REVIEW_DIR)");

const SITE_URL = process.env.SITE_URL ?? "http://localhost:3002";
const STATES = (process.env.UI_REVIEW_STATES ?? "full,loading,empty,error,long,many").split(",") as State[];
type State = "full" | "loading" | "empty" | "error" | "long" | "many";

/** What the frame itself asks for, left as it is so a screen's own states are what shows. */
const FRAME =
  /^\/(v1\/me|v1\/public\/status|v1\/notifications|admin\/v1\/(session|table-settings.*|queue\/bulk\/lists|queue\/counts|approvals\/count|views))$/;
/** Fields that hold words a person reads, which `long` lengthens. */
const WORDY =
  /^(name|displayName|title|label|description|summary|reason|detail|note|body|subject|message|text|serviceName|templateName|gpuName|gpu|model|fileName|filename|hostName|company|city|address|legalName|tagline|what|why)$/;
const LONG = " with a much longer name than anyone expects, to see where it wraps or truncates";
const LONG_WORD = "Supercalifragilisticexpialidociousdatasetfolder";

function changed(value: unknown, state: State, key = "", depth = 0): unknown {
  if (Array.isArray(value)) {
    const objects = value.length > 0 && value.every((item) => item !== null && typeof item === "object");
    if (!objects) {
      return value;
    }
    if (state === "empty") {
      return [];
    }
    if (state === "many" && depth < 3) {
      return Array.from({ length: Math.max(60, value.length) }, (_, index) => {
        const item = value[index % value.length] as Record<string, unknown>;
        return index < value.length ? item : withId(item, index);
      });
    }
    return value.map((item) => changed(item, state, key, depth + 1));
  }
  if (value !== null && typeof value === "object") {
    return Object.fromEntries(
      Object.entries(value as Record<string, unknown>).map(([field, inner]) => [
        field,
        changed(inner, state, field, depth + 1),
      ]),
    );
  }
  if (state === "long" && typeof value === "string" && value.length > 0) {
    if (/^email$/i.test(key) && value.includes("@")) {
      return `a.much.longer.address.than.usual.${value.split("@")[0]}@an-unusually-long-domain-name.example.com`;
    }
    if (WORDY.test(key) && !/^[A-Z_]+$/.test(value)) {
      return `${value} ${LONG_WORD}${LONG}`;
    }
  }
  return value;
}

/** A copy of a list's item with ids of its own, so a long list's rows are each its own. */
function withId(item: Record<string, unknown>, index: number): Record<string, unknown> {
  const suffix = index.toString(16).padStart(4, "0");
  return Object.fromEntries(
    Object.entries(item).map(([field, value]) => [
      field,
      /^(id|.*Id)$/.test(field) && typeof value === "string" && value.length > 4
        ? `${value.slice(0, -4)}${suffix}`
        : value,
    ]),
  );
}

/** The site's answers, changed for the state. */
function inState(routes: Record<string, Answer>, state: State): Record<string, Answer> {
  return Object.fromEntries(
    Object.entries(routes).map(([path, answer]): [string, Answer] => {
      if (FRAME.test(path) || state === "full") {
        return [path, answer];
      }
      if (state === "loading") {
        return [path, "hold"];
      }
      if (state === "error") {
        return [path, { status: 503, problem: "unavailable" }];
      }
      if (typeof answer === "function") {
        return [
          path,
          (call, request) => {
            // A fixture that looks a record up by id doesn't know a long list's copies: those aren't there.
            let given: ReturnType<typeof answer>;
            try {
              given = answer(call, request);
            } catch {
              return { status: 404, problem: "not-found" };
            }
            return typeof given === "object" && "json" in given
              ? { ...given, json: changed(given.json, state) }
              : given;
          },
        ];
      }
      return [
        path,
        typeof answer === "object" && "json" in answer
          ? { ...answer, json: changed(answer.json, state) }
          : answer,
      ];
    }),
  );
}

type Place = { site: ReviewSite; path: string; screen: string; signedOut?: boolean };

/** The console's screens, each as its sidebar names it, and a record's page as a screen of its own. */
const consoleLabels = new Map(
  [
    ...readFileSync(new URL("../src/features/admin/AdminConsole.tsx", import.meta.url), "utf8").matchAll(
      /\{ href: "(\/admin\/[^"]*)", label: "([^"]+)"/g,
    ),
  ].map((match) => [match[1]!, match[2]!]),
);
const SIGNED_OUT = new Set([
  "/sign-in/",
  "/sign-in/finish/",
  "/account/delete/confirm/",
  "/account/delete/cancel/",
]);
/** Pages that need what only a link carries, opened from it, by site. */
const LINKED: Partial<Record<ReviewSite, Record<string, string>>> = {
  app: {
    "/session/": `/session/?id=${RUNNING.id}`,
    "/cli-login/": "/cli-login/?code=WDJB-MJHT",
    "/account/help/ticket/": `/account/help/ticket/?id=${support.PRIVACY_TICKET}`,
  },
  host: {
    [HOST_PATHS.laptop]: laptopPath(hosting.hostRunning.id),
    [HOST_PATHS.price]: pricePath(hosting.hostRunning.id),
    [HOST_PATHS.sessions]: laptopSessionsPath(hosting.hostRunning.id),
    [HOST_PATHS.link]: `/link/${hosting.LINK_ID}/`,
  },
};
const pagesOf = (site: ReviewSite, screens: Record<string, unknown>): Place[] =>
  Object.keys(screens)
    .filter((path) => !["/sign-in/finish/", "/agree/", "/invite/"].includes(path))
    .map((path) => ({
      site,
      path: LINKED[site]?.[path] ?? path,
      screen: path,
      signedOut: SIGNED_OUT.has(path),
    }));

const PLACES: Place[] = [
  ...pagesOf("admin", ADMIN_SCREENS).map((place) => ({
    ...place,
    screen: consoleLabels.has(place.path) ? `${place.path} (${consoleLabels.get(place.path)})` : place.screen,
  })),
  ...[
    `/admin/users/?id=${admin.RENTER_ID}`,
    `/admin/hosts/?id=${admin.HOST_ID}`,
    `/admin/sessions/?id=${admin.SESSION_ID}`,
    `/admin/abuse/?id=${admin.ABUSE_OPEN_ID}`,
    `/admin/appeals/?id=${admin.appeal.id}`,
    `/admin/tickets/?id=${support.PRIVACY_TICKET}`,
    `/admin/payouts/?batch=${admin.BATCH_ID}`,
  ].map((path) => ({ site: "admin" as const, path, screen: `${path.split("?")[0]} a record` })),
  ...pagesOf("app", APP_SCREENS),
  // The screens every site shares (Account and the rest) are the renter app's to picture; the host site's own are here.
  ...pagesOf("host", HOST_SCREENS).filter((place) => !(place.screen in SHARED_SCREEN_MODULES)),
  ...readdirSync(new URL("../dist-website", import.meta.url), { recursive: true })
    .map(String)
    .filter((file) => file.endsWith("index.html"))
    .map((file) => `/${file.replace(/index\.html$/, "")}`)
    .filter((path) => !path.startsWith("/admin"))
    .sort()
    .map((path) => ({ site: "website" as const, path, screen: path, signedOut: true })),
];

const ORIGIN: Record<ReviewSite, string> = {
  app: APP_URL,
  host: HOST_URL,
  admin: ADMIN_URL,
  website: SITE_URL,
};
const API: Record<ReviewSite, () => Record<string, Answer>> = {
  app: renterApi,
  host: hostApi,
  admin: () => adminApi({ "/admin/v1/views": { json: { views: [] } } }),
  website: websiteApi,
};
/** How long each state takes to settle: the error state waits out the client's two retries. */
const SETTLE: Record<State, number> = {
  full: 1200,
  loading: 700,
  empty: 1200,
  error: 4500,
  long: 1200,
  many: 1500,
};

async function open(page: Page, place: Place, state: State): Promise<void> {
  await fakeApi(page, inState(API[place.site](), state));
  const origin = ORIGIN[place.site];
  if (place.signedOut) {
    await page.goto(`${origin}${place.path}`);
  } else {
    await signIn(page, "asha", place.path, origin);
  }
}

for (const place of PLACES) {
  for (const state of STATES) {
    // A signed-out page has no data to be in a state but its own live figures: only the website's have any.
    if (place.signedOut && place.site !== "website" && state !== "full") {
      continue;
    }
    test(`${place.site} ${place.path} ${state}`, async ({ page }, info: TestInfo) => {
      await page.clock.setFixedTime(data.NOW);
      const shifts = await recordLayoutShifts(page);
      await open(page, place, state);
      await expect(page.locator("body")).not.toBeEmpty();
      await page.waitForTimeout(SETTLE[state]);
      await review(page, info, {
        site: place.site,
        screen: place.screen,
        path: place.path,
        state,
        shifts: state === "loading" ? undefined : (await shifts()).total,
        axe: state === "full",
      });
    });
  }
}
