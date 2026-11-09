/**
 * Every path the renter app has a screen for (src/sites/app/routes.tsx, src/sites/frame.tsx), and the module that draws
 * it (src/sites/app/screens.ts is this list, as each site has one; the console's checks read it here). scripts/postbuild.ts writes an index.html into each path, so any static host serves a deep link without a rewrite
 * rule, and preloads that screen's own code in it, so a lazily loaded screen doesn't wait for the entry script to ask
 * for it.
 */
import { SHARED_SCREEN_MODULES } from "./sites/screen-modules.ts";

export const SCREENS: Record<string, string | null> = {
  ...SHARED_SCREEN_MODULES,
  "/": "src/features/home/Home.tsx",
  "/credit/": "src/features/credit/CreditHistory.tsx",
  "/session/": "src/features/session/SessionDetail.tsx",
  "/sessions/": "src/features/sessions/AllSessions.tsx",
  "/book/": "src/features/book/Book.tsx",
  "/files/": "src/features/files/MyFiles.tsx",
  "/welcome/": "src/features/home/Welcome.tsx",
  "/account/keys/": "src/features/account/ApiKeys.tsx",
  "/account/reports/": "src/features/account/Reports.tsx",
  "/account/images/": "src/features/account/MyImages.tsx",
  "/admin/": "src/features/admin/AdminConsole.tsx",
  "/admin/inbox/": "src/features/admin/AdminConsole.tsx",
  "/admin/appeals/": "src/features/admin/AdminConsole.tsx",
  "/admin/users/": "src/features/admin/AdminConsole.tsx",
  "/admin/hosts/": "src/features/admin/AdminConsole.tsx",
  "/admin/fleet/": "src/features/admin/AdminConsole.tsx",
  "/admin/sessions/": "src/features/admin/AdminConsole.tsx",
  "/admin/review/": "src/features/admin/AdminConsole.tsx",
  "/admin/disputes/": "src/features/admin/AdminConsole.tsx",
  "/admin/abuse/": "src/features/admin/AdminConsole.tsx",
  "/admin/images/": "src/features/admin/AdminConsole.tsx",
  "/admin/tickets/": "src/features/admin/AdminConsole.tsx",
  "/admin/notices/": "src/features/admin/AdminConsole.tsx",
  "/admin/money/": "src/features/admin/AdminConsole.tsx",
  "/admin/ledger/": "src/features/admin/AdminConsole.tsx",
  "/admin/adjustments/": "src/features/admin/AdminConsole.tsx",
  "/admin/months/": "src/features/admin/AdminConsole.tsx",
  "/admin/money-reports/": "src/features/admin/AdminConsole.tsx",
  "/admin/settlements/": "src/features/admin/AdminConsole.tsx",
  "/admin/payments/": "src/features/admin/AdminConsole.tsx",
  "/admin/refunds/": "src/features/admin/AdminConsole.tsx",
  "/admin/invoices/": "src/features/admin/AdminConsole.tsx",
  "/admin/chargebacks/": "src/features/admin/AdminConsole.tsx",
  "/admin/payouts/": "src/features/admin/AdminConsole.tsx",
  "/admin/verification/": "src/features/admin/AdminConsole.tsx",
  "/admin/clawbacks/": "src/features/admin/AdminConsole.tsx",
  "/admin/tax/": "src/features/admin/AdminConsole.tsx",
  "/admin/flags/": "src/features/admin/AdminConsole.tsx",
  "/admin/invites/": "src/features/admin/AdminConsole.tsx",
  "/admin/audit/": "src/features/admin/AdminConsole.tsx",
  "/admin/approvals/": "src/features/admin/AdminConsole.tsx",
  "/admin/security/": "src/features/admin/AdminConsole.tsx",
  "/cli-login/": "src/features/cli-login/CliLogin.tsx",
};
