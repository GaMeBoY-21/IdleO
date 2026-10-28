import { Link, useLocation } from "wouter";
import { brand } from "@/brand/brand";
import { Wordmark } from "@/brand/Wordmark";
import { AppLink } from "@/components/ui/AppLink";
import { cn } from "@/lib/cn";
import { AccountLink } from "./AccountLink";
import { NotificationBell } from "./NotificationBell";
import { destinations, isCurrent } from "./nav";

/** The sidebar's width: the content beside it is pushed in by the same (`md:pl-sidebar`, web/DESIGN.md "Navigation"). */
export const SIDEBAR_PUSH = "md:pl-sidebar";

/**
 * The app's left sidebar, from 768 px (approved reference `AppHome`): the wordmark, the six destinations, and the
 * account at the bottom beside the bell. The current destination has the raised fill and a 4 px bar in the accent;
 * the others are indented to line up with its text.
 */
export function Sidebar() {
  const [pathname] = useLocation();
  return (
    <aside className="fixed inset-y-0 left-0 z-10 hidden w-sidebar flex-col gap-1.5 border-r border-line bg-sidebar px-4 py-6 md:flex">
      <Link href="/" className="flex min-h-touch items-center px-3 pb-4" aria-label={`${brand.name} home`}>
        <Wordmark />
      </Link>
      <nav aria-label="Main" className="flex flex-col gap-1.5">
        {destinations.map(({ href, label }) => {
          const current = isCurrent(pathname, href);
          return (
            <AppLink
              key={href}
              href={href}
              aria-current={current ? "page" : undefined}
              className={cn(
                "flex min-h-touch items-center gap-3 rounded-control py-3 pr-3.5 text-small transition-colors",
                current
                  ? "bg-raised pl-3.5 font-semibold text-text"
                  : "pl-7.5 font-medium text-text-2 hover:bg-raised hover:text-text",
              )}
            >
              {current && <span aria-hidden="true" className="h-4.5 w-1 rounded-full bg-progress" />}
              {label}
            </AppLink>
          );
        })}
      </nav>
      <span className="flex-1" />
      <div className="flex items-center gap-1">
        <AccountLink />
        <NotificationBell />
      </div>
    </aside>
  );
}
