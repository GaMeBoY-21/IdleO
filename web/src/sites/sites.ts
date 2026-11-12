/**
 * The web app's sites (ADR 0058): one codebase, built once per site, each at its own address with its own routes,
 * headers and budgets: the renter app (app.idleo.in), the host site (host.idleo.in) and the admin site (admin.idleo.in).
 * The marketing website (idleo.in, src/site/) is a fourth, built apart by vite.site.config.ts.
 *
 * This file is read by the build (vite.config.ts, scripts/), so it imports nothing.
 */

export const SITES = ["app", "host", "admin"] as const;
export type SiteName = (typeof SITES)[number];

export interface SiteFacts {
  /** Who it's for, in a few words. */
  for: string;
  /** Its build folder beside web/, when IDLEO_DIST doesn't say otherwise. */
  dist: string;
  /** The development server's port, and the local stack's. */
  port: number;
  /** The build setting that holds its address, for links from the other sites. */
  urlSetting: "IDLEO_APP_URL" | "IDLEO_HOST_URL" | "IDLEO_ADMIN_URL";
  /** The screen its first load is measured on (scripts/first-load.ts): where it opens once signed in. */
  home: string;
  /** The module drawn inside the home's own when it has one: the console's control room, inside the console's frame. */
  homeScreen?: string;
  /**
   * Its first load's budget, gzipped, sign-in adapter included (web/README.md "The budgets"): 150 KB for the renter app
   * and the host site; 190 KB for the admin site, which only staff open, on laptops (the product owner's say, Nov 11:
   * the console's frame and control room with its tables were 186.3 KB).
   */
  firstLoadKb: number;
  /**
   * The packages its lazily loaded screens import, beside the sign-in adapter's (SIGN_IN_DEPS): its development server
   * optimises them up front (vite.config.ts), so opening a screen never makes it re-optimise mid-session and serve
   * "504 Outdated Optimize Dep" for what's already loaded. scripts/dev-deps.ts fails a build whose lazily loaded chunks
   * hold a direct dependency not named here.
   */
  lazyDeps: readonly string[];
}

/** What every site's lazily loaded screens import: forms, their checks, the dialogs and real visits' vitals. */
const LAZY_SHARED = [
  "@hookform/resolvers/zod",
  "radix-ui",
  "react-hook-form",
  "web-vitals",
  "zod/mini",
] as const;

/** Each sign-in adapter's packages (src/auth/), loaded after the first paint, so lazily too. */
export const SIGN_IN_DEPS: Record<"firebase" | "oidc-mock", readonly string[]> = {
  firebase: ["firebase/app", "firebase/auth"],
  "oidc-mock": ["oidc-client-ts"],
};

export const SITE_FACTS: Record<SiteName, SiteFacts> = {
  app: {
    for: "renters",
    dist: "dist",
    port: 3000,
    urlSetting: "IDLEO_APP_URL",
    home: "/",
    firstLoadKb: 150,
    lazyDeps: LAZY_SHARED,
  },
  host: {
    for: "hosts",
    dist: "dist-host",
    port: 3003,
    urlSetting: "IDLEO_HOST_URL",
    home: "/",
    firstLoadKb: 150,
    lazyDeps: LAZY_SHARED,
  },
  // The console keeps its /admin/ addresses on its own site, so every link to one only changes its host.
  admin: {
    for: "staff",
    dist: "dist-admin",
    port: 3004,
    urlSetting: "IDLEO_ADMIN_URL",
    home: "/admin/",
    homeScreen: "src/features/admin/Dashboard.tsx",
    firstLoadKb: 190,
    // The console's QR code (its second factor) and its long lists.
    lazyDeps: [...LAZY_SHARED, "uqr", "@tanstack/react-virtual"],
  },
};

/** The website's local port (scripts/serve.ts, ./start.sh); its address setting is IDLEO_SITE_URL. */
export const WEBSITE_PORT = 3002;

export function isSiteName(name: string): name is SiteName {
  return (SITES as readonly string[]).includes(name);
}

/** The site a build is for: IDLEO_WEB_SITE, the renter app when it's unset. */
export function siteOf(env: Record<string, string | undefined>): SiteName {
  const name = env.IDLEO_WEB_SITE ?? "app";
  if (!isSiteName(name)) {
    throw new Error(`IDLEO_WEB_SITE must be one of ${SITES.join(", ")}, not ${name}`);
  }
  return name;
}

/**
 * A site's build folder. IDLEO_DIST names the renter app's (a second build beside the first, as the browser tests make
 * for Firebase's screens), and every other site's is that name with the site's own after it: dist-firebase, then
 * dist-firebase-host.
 */
export function distOf(site: SiteName, base: string | undefined): string {
  if (base === undefined) {
    return SITE_FACTS[site].dist;
  }
  return site === "app" ? base : `${base.replace(/\/+$/, "")}-${site}`;
}

/**
 * Which site's code each part of src/ is (ADR 0058, "The code stays apart"): a site's build may hold only its own and
 * what's shared. Anything not listed is shared. scripts/site-modules.ts reads every module in each build against this.
 */
export const OWNED: Record<SiteName, readonly string[]> = {
  app: [
    "src/features/home/",
    "src/features/book/",
    "src/features/session/",
    "src/features/sessions/",
    "src/features/files/",
    "src/features/credit/",
    "src/features/cli-login/",
    "src/features/flow/",
    "src/features/images/",
    "src/features/account/ApiKeys.tsx",
    "src/features/account/CreateKey.tsx",
    "src/features/account/RevokeDialog.tsx",
    "src/features/account/MyImages.tsx",
    "src/features/account/Reports.tsx",
    "src/api/wallet.ts",
    "src/api/home.ts",
    "src/api/images.ts",
    "src/api/keys.ts",
    "src/api/session-follower.ts",
    "src/api/useLiveSession.ts",
    "src/api/logs.ts",
    "src/lib/uploads.ts",
    "src/lib/log-signals.ts",
  ],
  host: [
    "src/features/hosting/",
    "src/api/hosting.ts",
    "src/api/payouts.ts",
    "src/lib/payouts.ts",
    "src/lib/use-host-id.ts",
  ],
  admin: ["src/features/admin/"],
};

/** The site a source module belongs to, by its path from web/, or null when shared. */
export function ownerOf(module: string): SiteName | null {
  for (const [owner, prefixes] of Object.entries(OWNED) as [SiteName, readonly string[]][]) {
    if (prefixes.some((prefix) => module === prefix || (prefix.endsWith("/") && module.startsWith(prefix)))) {
      return owner;
    }
  }
  return null;
}
