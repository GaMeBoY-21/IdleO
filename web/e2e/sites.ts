/**
 * The addresses of the web app's sites in the browser tests (ADR 0058), as scripts/e2e/agent-online.sh serves them: the
 * renter app is the projects' baseURL (WEB_URL); the host site's screens are opened on HOST_URL.
 */
export const APP_URL = process.env.WEB_URL ?? "http://localhost:3000";
export const HOST_URL = process.env.HOST_URL ?? "http://localhost:3003";
