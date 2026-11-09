import type { AuthAdapterName } from "./build-settings";
import type { SiteName } from "./sites/sites";

/**
 * The build's settings, written into the bundle by Vite at build time. vite.config.ts checks them before
 * anything is built (src/build-settings.ts).
 */
export const config = {
  apiUrl: import.meta.env.VITE_API_URL ?? "",
  auth: (import.meta.env.VITE_AUTH ?? "oidc-mock") as AuthAdapterName,
  oidc: {
    issuer: import.meta.env.VITE_OIDC_ISSUER ?? "",
    clientId: import.meta.env.VITE_OIDC_CLIENT_ID ?? "",
  },
  /** The host app's install steps, for the add-a-machine screen, until there's an installer (ADR 0023). */
  hostRunbookUrl:
    import.meta.env.VITE_HOST_RUNBOOK_URL ??
    "https://github.com/GaMeBoY-21/IdleO/blob/main/docs/runbooks/hosts.md",
  /** The public website's address (ADR 0029): the app has its own, and links to the website's pages by this. */
  siteUrl: (import.meta.env.IDLEO_SITE_URL || "http://localhost:3002").replace(/\/+$/, ""),
  /** The renter app's address and the host site's (ADR 0058): each site links to the other by these. */
  appUrl: (import.meta.env.IDLEO_APP_URL || "http://localhost:3000").replace(/\/+$/, ""),
  hostUrl: (import.meta.env.IDLEO_HOST_URL || "http://localhost:3003").replace(/\/+$/, ""),
  /** Grafana Cloud's address, for the control room's link to the alerts (ADR 0056); no token is ever in the app. */
  grafanaUrl: (import.meta.env.VITE_GRAFANA_URL ?? "").replace(/\/+$/, ""),
  /** Which site this build is (ADR 0058): the renter app or the host site. */
  site: (import.meta.env.IDLEO_WEB_SITE ?? "app") as SiteName,
  /** The release this build is (IDLEO_RELEASE), which real visits' vitals and errors name (ADR 0036). */
  release: import.meta.env.IDLEO_RELEASE || "development",
  firebase: {
    apiKey: import.meta.env.VITE_FIREBASE_API_KEY ?? "",
    authDomain: import.meta.env.VITE_FIREBASE_AUTH_DOMAIN ?? "",
    projectId: import.meta.env.VITE_FIREBASE_PROJECT_ID ?? "",
  },
} as const;
