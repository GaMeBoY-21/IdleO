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
import { Sessions } from "./Sessions";
import { Users } from "./Users";

/**
 * The admin console (ADR 0027): its own chunk, loaded only on an /admin/ address, so renters never download it. A
 * left navigation and dense tables, in the app's black-and-white language.
 */
export const ADMIN_SCREENS = [
  { href: "/admin/", label: "Dashboard" },
  { href: "/admin/users/", label: "Users" },
  { href: "/admin/hosts/", label: "Hosts" },
  { href: "/admin/sessions/", label: "Sessions" },
  { href: "/admin/review/", label: "Review queue" },
  { href: "/admin/disputes/", label: "Reports" },
  { href: "/admin/chargebacks/", label: "Chargebacks" },
  { href: "/admin/abuse/", label: "Abuse" },
  { href: "/admin/images/", label: "Images" },
  { href: "/admin/tickets/", label: "Tickets" },
  { href: "/admin/money/", label: "Money" },
  { href: "/admin/payments/", label: "Payments" },
  { href: "/admin/refunds/", label: "Refunds" },
  { href: "/admin/invoices/", label: "Invoices" },
  { href: "/admin/payouts/", label: "Payouts" },
  { href: "/admin/verification/", label: "Verification" },
  { href: "/admin/clawbacks/", label: "Clawbacks" },
  { href: "/admin/tax/", label: "Tax" },
  { href: "/admin/invites/", label: "Invites" },
  { href: "/admin/notices/", label: "Notices" },
  { href: "/admin/audit/", label: "Audit log" },
] as const;

export function AdminConsole() {
  return (
    <Gate>
      <Layout>
        <Switch>
          <Route path="/admin/" component={Dashboard} />
          <Route path="/admin/users/" component={Users} />
          <Route path="/admin/hosts/" component={Hosts} />
          <Route path="/admin/sessions/" component={Sessions} />
          <Route path="/admin/review/" component={Review} />
          <Route path="/admin/disputes/" component={Disputes} />
          <Route path="/admin/abuse/" component={Abuse} />
          <Route path="/admin/images/" component={Images} />
          <Route path="/admin/tickets/" component={Tickets} />
          <Route path="/admin/notices/" component={Notices} />
          <Route path="/admin/money/" component={Money} />
          <Route path="/admin/payments/" component={Payments} />
          <Route path="/admin/refunds/" component={Refunds} />
          <Route path="/admin/invoices/" component={Invoices} />
          <Route path="/admin/chargebacks/" component={Chargebacks} />
          <Route path="/admin/payouts/" component={Payouts} />
          <Route path="/admin/verification/" component={Verification} />
          <Route path="/admin/clawbacks/" component={Clawbacks} />
          <Route path="/admin/tax/" component={Tax} />
          <Route path="/admin/invites/" component={Invites} />
          <Route path="/admin/audit/" component={Audit} />
        </Switch>
      </Layout>
    </Gate>
  );
}

function Layout({ children }: { children: React.ReactNode }) {
  const [path] = useLocation();
  const client = useQueryClient();
  const leave = async () => {
    await adminApi.DELETE("/admin/v1/session").catch(() => undefined);
    adminSession.clear();
    client.removeQueries({ queryKey: [ADMIN] });
  };
  return (
    <div className="flex min-h-dvh flex-col md:flex-row">
      <header className="flex flex-col gap-2 border-b border-divider px-4 py-3 md:w-56 md:shrink-0 md:border-r md:border-b-0 md:py-6">
        <div className="flex items-center justify-between gap-2 md:flex-col md:items-start">
          <Link href="/" className="flex items-center gap-2" aria-label="IdleO home">
            <Wordmark />
          </Link>
          <span className="text-small font-semibold">Admin</span>
        </div>
        <nav aria-label="Admin" className="-mx-1 overflow-x-auto md:mx-0 md:mt-4">
          <ul className="flex gap-1 md:flex-col">
            {ADMIN_SCREENS.map((screen) => {
              const current = path === screen.href;
              return (
                <li key={screen.href}>
                  <Link
                    href={screen.href}
                    aria-current={current ? "page" : undefined}
                    className={cn(
                      "flex min-h-touch items-center rounded-full px-3 text-small font-semibold whitespace-nowrap md:rounded-control",
                      current ? "bg-primary text-primary-fg" : "text-text hover:bg-fill",
                    )}
                  >
                    {screen.label}
                  </Link>
                </li>
              );
            })}
          </ul>
        </nav>
        <Button variant="quiet" className="self-start md:mt-auto" onClick={() => void leave()}>
          Leave the console
        </Button>
      </header>
      <main className="min-w-0 flex-1 px-4 py-6 md:px-8">{children}</main>
    </div>
  );
}
