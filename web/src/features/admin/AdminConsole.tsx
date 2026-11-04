import { useQueryClient } from "@tanstack/react-query";
import { Link, Route, Switch, useLocation } from "wouter";
import { Wordmark } from "@/brand/Wordmark";
import { Button } from "@/components/ui/Button";
import { cn } from "@/lib/cn";
import { ADMIN, adminApi, adminSession } from "./api";
import { Audit } from "./Audit";
import { Dashboard } from "./Dashboard";
import { Abuse } from "./Abuse";
import { Chargebacks } from "./Chargebacks";
import { Disputes } from "./Disputes";
import { Notices } from "./Notices";
import { Tickets } from "./Tickets";
import { Gate } from "./Gate";
import { NeedsPermission, useCan } from "./permissions";
import { Hosts } from "./Hosts";
import { Invites } from "./Invites";
import { Invoices } from "./Invoices";
import { Money } from "./Money";
import { Payments } from "./Payments";
import { Payouts } from "./Payouts";
import { Verification } from "./Verification";
import { Clawbacks } from "./Clawbacks";
import { Images } from "./Images";
import { Tax } from "./Tax";
import { Refunds } from "./Refunds";
import { Review } from "./Review";
import { Security } from "./Security";
import { Sessions } from "./Sessions";
import { Users } from "./Users";

/**
 * The admin console (ADR 0027): its own chunk, loaded only on an /admin/ address, so renters never download it. A
 * left navigation and dense tables, in the app's Night Shift tokens (and Day Shift, with the device).
 */
export const ADMIN_SCREENS = [
  { href: "/admin/", label: "Dashboard", needs: "dashboard.read" },
  { href: "/admin/users/", label: "Users", needs: "users.read" },
  { href: "/admin/hosts/", label: "Hosts", needs: "hosts.read" },
  { href: "/admin/sessions/", label: "Sessions", needs: "sessions.read" },
  { href: "/admin/review/", label: "Review queue", needs: "review.read" },
  { href: "/admin/disputes/", label: "Reports", needs: "disputes.read" },
  { href: "/admin/chargebacks/", label: "Chargebacks", needs: "payments.read" },
  { href: "/admin/abuse/", label: "Abuse", needs: "abuse.read" },
  { href: "/admin/images/", label: "Images", needs: "images.read" },
  { href: "/admin/tickets/", label: "Tickets", needs: "tickets.read" },
  { href: "/admin/money/", label: "Money", needs: "money.read" },
  { href: "/admin/payments/", label: "Payments", needs: "payments.read" },
  { href: "/admin/refunds/", label: "Refunds", needs: "refunds.read" },
  { href: "/admin/invoices/", label: "Invoices", needs: "invoices.read" },
  { href: "/admin/payouts/", label: "Payouts", needs: "payouts.read" },
  { href: "/admin/verification/", label: "Verification", needs: "verifications.read" },
  { href: "/admin/clawbacks/", label: "Clawbacks", needs: "clawbacks.read" },
  { href: "/admin/tax/", label: "Tax", needs: "export.create" },
  { href: "/admin/invites/", label: "Invites", needs: "invites.read" },
  { href: "/admin/notices/", label: "Notices", needs: "notices.publish" },
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
      <Layout>
        <Switch>
          <Route path="/admin/">{guarded("/admin/", <Dashboard />)}</Route>
          <Route path="/admin/users/">{guarded("/admin/users/", <Users />)}</Route>
          <Route path="/admin/hosts/">{guarded("/admin/hosts/", <Hosts />)}</Route>
          <Route path="/admin/sessions/">{guarded("/admin/sessions/", <Sessions />)}</Route>
          <Route path="/admin/review/">{guarded("/admin/review/", <Review />)}</Route>
          <Route path="/admin/disputes/">{guarded("/admin/disputes/", <Disputes />)}</Route>
          <Route path="/admin/abuse/">{guarded("/admin/abuse/", <Abuse />)}</Route>
          <Route path="/admin/images/">{guarded("/admin/images/", <Images />)}</Route>
          <Route path="/admin/tickets/">{guarded("/admin/tickets/", <Tickets />)}</Route>
          <Route path="/admin/notices/">{guarded("/admin/notices/", <Notices />)}</Route>
          <Route path="/admin/money/">{guarded("/admin/money/", <Money />)}</Route>
          <Route path="/admin/payments/">{guarded("/admin/payments/", <Payments />)}</Route>
          <Route path="/admin/refunds/">{guarded("/admin/refunds/", <Refunds />)}</Route>
          <Route path="/admin/invoices/">{guarded("/admin/invoices/", <Invoices />)}</Route>
          <Route path="/admin/chargebacks/">{guarded("/admin/chargebacks/", <Chargebacks />)}</Route>
          <Route path="/admin/payouts/">{guarded("/admin/payouts/", <Payouts />)}</Route>
          <Route path="/admin/verification/">{guarded("/admin/verification/", <Verification />)}</Route>
          <Route path="/admin/clawbacks/">{guarded("/admin/clawbacks/", <Clawbacks />)}</Route>
          <Route path="/admin/tax/">{guarded("/admin/tax/", <Tax />)}</Route>
          <Route path="/admin/invites/">{guarded("/admin/invites/", <Invites />)}</Route>
          <Route path="/admin/audit/">{guarded("/admin/audit/", <Audit />)}</Route>
          <Route path="/admin/security/" component={Security} />
        </Switch>
      </Layout>
    </Gate>
  );
}

/** The sidebar's sections in groups, in the order of {@link ADMIN_SCREENS}; every screen is in exactly one. */
const GROUPS: { label: string; hrefs: readonly string[] }[] = [
  { label: "Overview", hrefs: ["/admin/", "/admin/users/", "/admin/hosts/", "/admin/sessions/"] },
  {
    label: "Safety",
    hrefs: [
      "/admin/review/",
      "/admin/disputes/",
      "/admin/chargebacks/",
      "/admin/abuse/",
      "/admin/images/",
      "/admin/tickets/",
    ],
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
      "/admin/tax/",
    ],
  },
  {
    label: "The service",
    hrefs: ["/admin/invites/", "/admin/notices/", "/admin/audit/", "/admin/security/"],
  },
];

/**
 * The console's frame (web/DESIGN.md "Account, sign-in and the rest"): the app's sidebar, denser: 240 px wide, its
 * sections in four groups with items 36 px tall from 768 px (44 px on a phone, where they scroll sideways), and the
 * sidebar itself scrolls when the window is shorter than the list.
 */
function Layout({ children }: { children: React.ReactNode }) {
  const [path] = useLocation();
  const client = useQueryClient();
  const can = useCan();
  const screens = ADMIN_SCREENS.filter((screen) => can(screen.needs));
  const leave = async () => {
    await adminApi.DELETE("/admin/v1/session").catch(() => undefined);
    adminSession.clear();
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
            "flex min-h-touch items-center gap-3 rounded-control px-3 text-small whitespace-nowrap md:min-h-9",
            current
              ? "bg-raised font-semibold text-text"
              : "font-medium text-text-2 hover:bg-raised hover:text-text",
          )}
        >
          {current && <span aria-hidden="true" className="h-4 w-1 rounded-full bg-progress" />}
          {screen.label}
        </Link>
      </li>
    );
  };
  return (
    <div className="flex min-h-dvh flex-col md:flex-row">
      <header className="flex flex-col gap-2 border-b border-line bg-sidebar px-4 py-3 md:sticky md:top-0 md:h-dvh md:w-sidebar-admin md:shrink-0 md:overflow-y-auto md:border-r md:border-b-0 md:py-6">
        <div className="flex items-center justify-between gap-2 md:flex-col md:items-start">
          <Link href="/" className="flex items-center gap-2 md:px-3" aria-label="IdleO home">
            <Wordmark />
          </Link>
          <span className="text-caption font-bold tracking-wider text-text-3 uppercase md:px-3">Admin</span>
        </div>
        <nav aria-label="Admin" className="-mx-1 overflow-x-auto md:mx-0 md:mt-3">
          <ul className="flex gap-1 md:hidden">{screens.map(item)}</ul>
          <div className="hidden flex-col gap-4 md:flex">
            {GROUPS.filter((group) => screens.some((screen) => group.hrefs.includes(screen.href))).map(
              (group) => (
                <section key={group.label} aria-label={group.label} className="flex flex-col gap-0.5">
                  <h2 className="px-3 pb-1 text-caption font-semibold tracking-wider text-text-3 uppercase">
                    {group.label}
                  </h2>
                  <ul className="flex flex-col gap-0.5">
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
    </div>
  );
}
