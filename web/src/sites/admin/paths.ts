/**
 * The admin site's addresses (ADR 0058). The console keeps the /admin/ addresses it had in the renter app, now on its
 * own site (admin.idleo.in), so a link, a bookmark or a runbook's path changes only its host. The renter app answers
 * every old one with a permanent redirect here (scripts/postbuild.ts), query and all, and this site's own root leads to
 * the console.
 *
 * Read by the build too, so it imports nothing.
 */
export const CONSOLE = "/admin/";

/** The renter app's old console addresses and where they moved: the same path, on the admin site. */
export const MOVED_TO_ADMIN: Readonly<Record<string, string>> = {
  [`${CONSOLE}*`]: `${CONSOLE}*`,
};

/** The admin site's own redirects: its root is the console's. */
export const ADMIN_REDIRECTS: Readonly<Record<string, string>> = {
  "/": CONSOLE,
};
