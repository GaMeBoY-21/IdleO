/**
 * The web app's sites (ADR 0058): one codebase, built once per site, each at its own address with its own routes,
 * headers and budgets. The marketing website (idleo.in, src/site/) is a fourth, built apart by vite.site.config.ts.
 *
 * This file is read by the build (vite.config.ts, scripts/), so it imports nothing.
 */

export const SITES = ["app", "host"] as const;
export type SiteName = (typeof SITES)[number];

export interface SiteFacts {
  /** Who it's for, in a few words. */
  for: string;
  /** Its build folder beside web/, when IDLEO_DIST doesn't say otherwise. */
  dist: string;
  /** The development server's port, and the local stack's. */
  port: number;
  /** The build setting that holds its address, for links from the other sites. */
  urlSetting: "IDLEO_APP_URL" | "IDLEO_HOST_URL";
}

export const SITE_FACTS: Record<SiteName, SiteFacts> = {
  app: { for: "renters", dist: "dist", port: 3000, urlSetting: "IDLEO_APP_URL" },
  host: { for: "hosts", dist: "dist-host", port: 3003, urlSetting: "IDLEO_HOST_URL" },
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
export const OWNED: Record<SiteName | "admin", readonly string[]> = {
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

/** The owner of a source module, by its path from web/: a site, the admin console, or null when shared. */
export function ownerOf(module: string): SiteName | "admin" | null {
  for (const [owner, prefixes] of Object.entries(OWNED) as [SiteName | "admin", readonly string[]][]) {
    if (prefixes.some((prefix) => module === prefix || (prefix.endsWith("/") && module.startsWith(prefix)))) {
      return owner;
    }
  }
  return null;
}
