/**
 * Every path the admin site has a page for (src/sites/admin/routes.tsx), and the module that draws it, for
 * scripts/postbuild.ts, as each site has one: signing in, the Terms, an invite code, and each of the console's screens
 * (src/features/admin/AdminConsole.tsx's ADMIN_SCREENS; its tests check every one has a page here). Its root has none:
 * it redirects to the console.
 */
import { SHARED_SCREEN_MODULES } from "../screen-modules.ts";

/** The console is one chunk (ADR 0027): every one of its screens is drawn by it. */
const CONSOLE_MODULE = "src/features/admin/AdminConsole.tsx";

/** The shared screens the admin site has: signing in and what may come straight after it. */
export const ADMIN_SHARED = ["/sign-in/", "/sign-in/finish/", "/agree/", "/invite/"] as const;

export const SCREENS: Record<string, string | null> = {
  ...Object.fromEntries(ADMIN_SHARED.map((path) => [path, SHARED_SCREEN_MODULES[path]!])),
  "/admin/": CONSOLE_MODULE,
  "/admin/inbox/": CONSOLE_MODULE,
  "/admin/appeals/": CONSOLE_MODULE,
  "/admin/users/": CONSOLE_MODULE,
  "/admin/hosts/": CONSOLE_MODULE,
  "/admin/fleet/": CONSOLE_MODULE,
  "/admin/sessions/": CONSOLE_MODULE,
  "/admin/review/": CONSOLE_MODULE,
  "/admin/disputes/": CONSOLE_MODULE,
  "/admin/abuse/": CONSOLE_MODULE,
  "/admin/images/": CONSOLE_MODULE,
  "/admin/tickets/": CONSOLE_MODULE,
  "/admin/notices/": CONSOLE_MODULE,
  "/admin/money/": CONSOLE_MODULE,
  "/admin/ledger/": CONSOLE_MODULE,
  "/admin/adjustments/": CONSOLE_MODULE,
  "/admin/months/": CONSOLE_MODULE,
  "/admin/money-reports/": CONSOLE_MODULE,
  "/admin/settlements/": CONSOLE_MODULE,
  "/admin/payments/": CONSOLE_MODULE,
  "/admin/refunds/": CONSOLE_MODULE,
  "/admin/invoices/": CONSOLE_MODULE,
  "/admin/chargebacks/": CONSOLE_MODULE,
  "/admin/payouts/": CONSOLE_MODULE,
  "/admin/verification/": CONSOLE_MODULE,
  "/admin/clawbacks/": CONSOLE_MODULE,
  "/admin/tax/": CONSOLE_MODULE,
  "/admin/flags/": CONSOLE_MODULE,
  "/admin/invites/": CONSOLE_MODULE,
  "/admin/audit/": CONSOLE_MODULE,
  "/admin/approvals/": CONSOLE_MODULE,
  "/admin/security/": CONSOLE_MODULE,
};
