import { useQuery, useQueryClient } from "@tanstack/react-query";
import "./states";
import { Suspense } from "react";
import { Toasts } from "@/components/shell/Toasts";
import { Link, Route, Switch, useLocation } from "wouter";
import { Wordmark } from "@/brand/Wordmark";
import { Button } from "@/components/ui/Button";
import { Skeleton } from "@/components/ui/Skeleton";
import { cn } from "@/lib/cn";
import { ADMIN, adminApi, adminData, adminSession } from "./api";
import { CONSOLE_SCREENS } from "./console-screens";
import { Gate } from "./Gate";
import { HelpButton, KeysProvider } from "./Keys";
import { forgetRecent, PaletteButton, PaletteProvider } from "./Palette";
import { NeedsPermission, useCan } from "./permissions";

/**
 * The admin console (ADR 0027), on the admin site alone (admin.idleo.in, ADR 0058): a left navigation and dense tables,
 * in the app's Night Shift tokens (and Day Shift, with the device). Its frame is one chunk and each screen another
 * (console-screens.ts).
 */
export const ADMIN_SCREENS = [
  { href: "/admin/", label: "Control room", needs: "ops.dashboard.read" },
  { href: "/admin/inbox/", label: "Inbox", needs: "queues.read" },
  { href: "/admin/users/", label: "Users", needs: "users.read" },
  { href: "/admin/hosts/", label: "Hosts", needs: "hosts.read" },
  { href: "/admin/fleet/", label: "Fleet", needs: "fleet.read" },
  { href: "/admin/sessions/", label: "Sessions", needs: "sessions.read" },
  { href: "/admin/review/", label: "Review queue", needs: "review.read" },
  { href: "/admin/disputes/", label: "Reports", needs: "disputes.read" },
  { href: "/admin/chargebacks/", label: "Chargebacks", needs: "payments.read" },
  { href: "/admin/abuse/", label: "Abuse", needs: "abuse.read" },
  { href: "/admin/appeals/", label: "Appeals", needs: "enforcement.read" },
  { href: "/admin/images/", label: "Images", needs: "images.read" },
  { href: "/admin/tickets/", label: "Tickets", needs: "tickets.read" },
  { href: "/admin/money/", label: "Money", needs: "money.read" },
  { href: "/admin/ledger/", label: "Ledger", needs: "ledger.read" },
  { href: "/admin/adjustments/", label: "Adjustments", needs: "ledger.read" },
  { href: "/admin/months/", label: "Months", needs: "ledger.read" },
  { href: "/admin/money-reports/", label: "Money reports", needs: "money.read" },
  { href: "/admin/settlements/", label: "Settlements", needs: "recon.read" },
  { href: "/admin/payments/", label: "Payments", needs: "payments.read" },
  { href: "/admin/refunds/", label: "Refunds", needs: "refunds.read" },
  { href: "/admin/invoices/", label: "Invoices", needs: "invoices.read" },
  { href: "/admin/payouts/", label: "Payouts", needs: "payouts.read" },
  { href: "/admin/verification/", label: "Verification", needs: "verifications.read" },
  { href: "/admin/clawbacks/", label: "Clawbacks", needs: "clawbacks.read" },
  { href: "/admin/tax/", label: "Tax", needs: "tax.export" },
  { href: "/admin/flags/", label: "Flags", needs: "flags.read" },
  { href: "/admin/invites/", label: "Invites", needs: "invites.read" },
  { href: "/admin/notices/", label: "Notices", needs: "notices.publish" },
  { href: "/admin/approvals/", label: "Approvals", needs: "approvals.read" },
  { href: "/admin/audit/", label: "Audit log", needs: "audit.read" },
  { href: "/admin/security/", label: "Your access", needs: "" },
] as const;

/** A screen, once its section's permission is held; it never asks the server for what would be refused. */
function guarded(href: string, screen: React.ReactNode) {
  const needs = ADMIN_SCREENS.find((candidate) => candidate.href === href)?.needs ?? "";
  return <NeedsPermission permission={needs}>{screen}</NeedsPermission>;
}

export function AdminConsole() {
  return (
    <Gate>
      <PaletteProvider screens={ADMIN_SCREENS}>
        <KeysProvider>
          <Layout>
            <Suspense fallback={<Skeleton className="h-16 w-full" />}>
              <Switch>
                {Object.entries(CONSOLE_SCREENS).map(([href, { Screen }]) =>
                  // Your access is everyone's: it's where an admin without a section's permission sees what they have.
                  href === "/admin/security/" ? (
                    <Route key={href} path={href} component={Screen} />
                  ) : (
                    <Route key={href} path={href}>
                      {guarded(href, <Screen />)}
                    </Route>
                  ),
                )}
              </Switch>
            </Suspense>
          </Layout>
        </KeysProvider>
      </PaletteProvider>
    </Gate>
  );
}

/**
 * The sidebar's sections in groups (web/DESIGN.md "The admin sidebar"), in the order an admin's day runs: what needs doing
 * now, the people and their machines, sessions, trust and safety, money, the books, the platform, then their own access.
 * Every screen is in exactly one; a group shows only the screens this admin may open.
 */
const GROUPS: { label: string; hrefs: readonly string[] }[] = [
  { label: "Today", hrefs: ["/admin/", "/admin/inbox/", "/admin/approvals/"] },
  {
    label: "People and hosts",
    hrefs: ["/admin/users/", "/admin/hosts/", "/admin/fleet/", "/admin/invites/"],
  },
  { label: "Sessions", hrefs: ["/admin/sessions/", "/admin/review/", "/admin/images/"] },
  {
    label: "Trust and safety",
    hrefs: ["/admin/disputes/", "/admin/chargebacks/", "/admin/abuse/", "/admin/appeals/", "/admin/tickets/"],
  },
  {
    label: "Money",
    hrefs: [
      "/admin/money/",
      "/admin/payments/",
      "/admin/refunds/",
      "/admin/invoices/",
      "/admin/payouts/",
      "/admin/verification/",
      "/admin/clawbacks/",
    ],
  },
  {
    label: "Books",
    hrefs: [
      "/admin/ledger/",
      "/admin/adjustments/",
      "/admin/months/",
      "/admin/settlements/",
      "/admin/money-reports/",
      "/admin/tax/",
    ],
  },
  { label: "Platform", hrefs: ["/admin/flags/", "/admin/notices/", "/admin/audit/"] },
  { label: "Settings", hrefs: ["/admin/security/"] },
];

/**
 * The console's frame (web/DESIGN.md "Account, sign-in and the rest"): the app's sidebar, denser: 240 px wide, its
 * sections in eight groups with items 36 px tall from 768 px (44 px on a phone, where they scroll sideways), and the
 * sidebar itself scrolls when the window is shorter than the list.
 */
function Layout({ children }: { children: React.ReactNode }) {
  const [path] = useLocation();
  const client = useQueryClient();
  const can = useCan();
  const screens = ADMIN_SCREENS.filter((screen) => can(screen.needs));
  const waiting = useQuery({
    queryKey: [ADMIN, "approvals-count"],
    queryFn: () => adminData(adminApi.GET("/admin/v1/approvals/count")),
    enabled: can("approvals.read"),
    refetchInterval: 60_000,
  });
  const counts = useQuery({
    queryKey: [ADMIN, "queue-counts"],
    queryFn: () => adminData(adminApi.GET("/admin/v1/queue/counts")),
    enabled: can("queues.read"),
    refetchInterval: 60_000,
  });
  const leave = async () => {
    await adminApi.DELETE("/admin/v1/session").catch(() => undefined);
    adminSession.clear();
    forgetRecent();
    client.removeQueries({ queryKey: [ADMIN] });
  };
  const item = (screen: (typeof ADMIN_SCREENS)[number]) => {
    const current = path === screen.href;
    return (
      <li key={screen.href}>
        <Link
          href={screen.href}
          aria-current={current ? "page" : undefined}
          className={cn(
            "flex min-h-touch items-center gap-2 rounded-control px-4 text-small whitespace-nowrap md:min-h-9",
            current
              ? "bg-raised font-semibold text-text"
              : "font-medium text-text-2 hover:bg-raised hover:text-text",
          )}
        >
          {current && <span aria-hidden="true" className="h-4 w-1 rounded-full bg-progress" />}
          {screen.label}
          {screen.href === "/admin/inbox/" && counts.data && (
            <span
              className="tabular ml-auto text-caption font-bold"
              aria-label={`${counts.data.mine} mine, ${counts.data.unassigned} unassigned, ${counts.data.breaching} breaching`}
            >
              {counts.data.mine} · {counts.data.unassigned} · {counts.data.breaching}
            </span>
          )}
          {screen.href === "/admin/approvals/" && (waiting.data?.pending ?? 0) > 0 && (
            <span
              className="tabular ml-auto text-caption font-bold"
              aria-label={`${waiting.data?.pending ?? 0} waiting`}
            >
              {waiting.data?.pending}
            </span>
          )}
        </Link>
      </li>
    );
  };
  return (
    <div className="flex min-h-dvh flex-col md:flex-row">
      <header className="flex flex-col gap-2 border-b border-line bg-sidebar px-4 py-4 md:sticky md:top-0 md:h-dvh md:w-sidebar-admin md:shrink-0 md:overflow-y-auto md:border-r md:border-b-0 md:py-6">
        <div className="flex items-center justify-between gap-2 md:flex-col md:items-start">
          <Link
            href="/admin/"
            className="flex items-center gap-2 md:px-4"
            aria-label="IdleO admin, control room"
          >
            <Wordmark />
          </Link>
          <span className="text-caption font-bold tracking-wider text-text-3 uppercase md:px-4">Admin</span>
        </div>
        <div className="flex flex-col gap-2">
          <PaletteButton />
          <HelpButton />
        </div>
        <nav aria-label="Admin" className="-mx-1 overflow-x-auto md:mx-0 md:mt-4">
          <ul className="flex gap-1 md:hidden">{screens.map(item)}</ul>
          <div className="hidden flex-col gap-4 md:flex">
            {GROUPS.filter((group) => screens.some((screen) => group.hrefs.includes(screen.href))).map(
              (group) => (
                <section key={group.label} aria-label={group.label} className="flex flex-col gap-1">
                  <h2 className="px-4 pb-1 text-caption font-semibold tracking-wider text-text-3 uppercase">
                    {group.label}
                  </h2>
                  <ul className="flex flex-col gap-1">
                    {screens.filter((screen) => group.hrefs.includes(screen.href)).map(item)}
                  </ul>
                </section>
              ),
            )}
          </div>
        </nav>
        <Button variant="quiet" className="self-start md:mt-auto" onClick={() => void leave()}>
          Leave the console
        </Button>
      </header>
      <main className="min-w-0 flex-1 px-4 py-6 md:px-8 md:py-8">{children}</main>
      <Toasts />
    </div>
  );
}
