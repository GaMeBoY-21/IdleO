import { screen, type Screen } from "@/sites/screen";

/**
 * The console's screens (ADR 0027), each its own chunk, as every site's are (ADR 0058): the admin site's first load is its
 * frame and the screen it opens on, within the 150 KB every site keeps to. The admin site loads the screen an address
 * opens before it draws (src/sites/admin/routes.tsx), and the rest once that has painted, so moving between them is
 * instant. Every screen of ADMIN_SCREENS (AdminConsole.tsx) has one here; its tests check it.
 */
export const CONSOLE_SCREENS: Record<string, Screen> = {
  "/admin/": screen(() => import("./Dashboard").then((m) => m.Dashboard)),
  "/admin/users/": screen(() => import("./Users").then((m) => m.Users)),
  "/admin/hosts/": screen(() => import("./Hosts").then((m) => m.Hosts)),
  "/admin/fleet/": screen(() => import("./Fleet").then((m) => m.Fleet)),
  "/admin/sessions/": screen(() => import("./Sessions").then((m) => m.Sessions)),
  "/admin/review/": screen(() => import("./Review").then((m) => m.Review)),
  "/admin/disputes/": screen(() => import("./Disputes").then((m) => m.Disputes)),
  "/admin/abuse/": screen(() => import("./Abuse").then((m) => m.Abuse)),
  "/admin/images/": screen(() => import("./Images").then((m) => m.Images)),
  "/admin/tickets/": screen(() => import("./Tickets").then((m) => m.Tickets)),
  "/admin/notices/": screen(() => import("./Notices").then((m) => m.Notices)),
  "/admin/money/": screen(() => import("./Money").then((m) => m.Money)),
  "/admin/ledger/": screen(() => import("./Ledger").then((m) => m.Ledger)),
  "/admin/adjustments/": screen(() => import("./Adjustments").then((m) => m.Adjustments)),
  "/admin/months/": screen(() => import("./Months").then((m) => m.Months)),
  "/admin/money-reports/": screen(() => import("./MoneyReports").then((m) => m.MoneyReports)),
  "/admin/settlements/": screen(() => import("./Settlements").then((m) => m.Settlements)),
  "/admin/payments/": screen(() => import("./Payments").then((m) => m.Payments)),
  "/admin/refunds/": screen(() => import("./Refunds").then((m) => m.Refunds)),
  "/admin/invoices/": screen(() => import("./Invoices").then((m) => m.Invoices)),
  "/admin/chargebacks/": screen(() => import("./Chargebacks").then((m) => m.Chargebacks)),
  "/admin/payouts/": screen(() => import("./Payouts").then((m) => m.Payouts)),
  "/admin/verification/": screen(() => import("./Verification").then((m) => m.Verification)),
  "/admin/clawbacks/": screen(() => import("./Clawbacks").then((m) => m.Clawbacks)),
  "/admin/tax/": screen(() => import("./Tax").then((m) => m.Tax)),
  "/admin/invites/": screen(() => import("./Invites").then((m) => m.Invites)),
  "/admin/inbox/": screen(() => import("./Inbox").then((m) => m.Inbox)),
  "/admin/appeals/": screen(() => import("./Enforcement").then((m) => m.Appeals)),
  "/admin/flags/": screen(() => import("./Flags").then((m) => m.Flags)),
  "/admin/approvals/": screen(() => import("./Approvals").then((m) => m.Approvals)),
  "/admin/audit/": screen(() => import("./Audit").then((m) => m.Audit)),
  "/admin/security/": screen(() => import("./Security").then((m) => m.Security)),
};
