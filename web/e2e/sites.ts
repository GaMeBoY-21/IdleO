/**
 * The addresses of the web app's sites in the browser tests (ADR 0058), as scripts/e2e/agent-online.sh serves them: the
 * renter app is the projects' baseURL (WEB_URL); the host site's screens are opened on HOST_URL; the console's specs make
 * the admin site's, ADMIN_URL, their own baseURL.
 */
export const APP_URL = process.env.WEB_URL ?? "http://localhost:3000";
export const HOST_URL = process.env.HOST_URL ?? "http://localhost:3003";
export const ADMIN_URL = process.env.ADMIN_URL ?? "http://localhost:3004";
