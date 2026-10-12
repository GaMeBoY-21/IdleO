import { ChevronLeft } from "lucide-react";
import { Link, useLocation } from "wouter";
import type { ReactNode } from "react";
import { Wordmark } from "@/brand/Wordmark";
import { cn } from "@/lib/cn";
import { CreditChip } from "./CreditChip";
import { NotificationBanners } from "./NotificationBanners";
import { NotificationBell } from "./NotificationBell";
import { isCurrent, isFlow, laptopDestinations, phoneTabs } from "./nav";
import { UserMenuButton } from "./UserMenuButton";

/**
 * The frame around every signed-in screen (web/DESIGN.md "Navigation"). From 768 px: one 64 px top bar with the
 * wordmark, pill navigation, the credit pill and the account button. Below that: a top bar with the wordmark, credit
 * and account, and bottom tabs; Book and the session screen are flows there, full screen with their own back
 * button. The frame never changes between pages, so switching pages swaps only the content.
 */
export function AppShell({ children }: { children: ReactNode }) {
  const [pathname] = useLocation();
  const flow = isFlow(pathname);
  return (
    <div className="flex min-h-dvh flex-col">
      <a
        href="#main"
        className="sr-only z-30 rounded-control bg-ground px-4 py-3 focus:not-sr-only focus:fixed focus:top-2 focus:left-2"
      >
        Skip to content
      </a>
      <header
        className={cn(
          "sticky top-0 z-10 bg-ground md:border-b md:border-divider",
          flow ? "hidden md:block" : "block",
        )}
      >
        <div className="flex h-15 items-center gap-8 px-4 md:h-16 md:px-8">
          <Link href="/" className="flex min-h-touch items-center" aria-label="IdleO home">
            <Wordmark className="h-6 w-auto" />
          </Link>
          <nav aria-label="Main" className="hidden flex-1 items-center gap-2 md:flex">
            {laptopDestinations.map(({ href, label }) => (
              <Link
                key={href}
                href={href}
                aria-current={isCurrent(pathname, href) ? "page" : undefined}
                className={cn(
                  "flex min-h-touch items-center rounded-full px-3.5 text-small font-semibold",
                  isCurrent(pathname, href) ? "bg-primary text-primary-fg" : "text-text hover:bg-fill",
                )}
              >
                {label}
              </Link>
            ))}
          </nav>
          <div className="ml-auto flex items-center gap-2 md:ml-0">
            <CreditChip />
            <NotificationBell />
            <UserMenuButton />
          </div>
        </div>
      </header>
      <NotificationBanners />
      <main id="main" className={cn("flex w-full flex-1 flex-col", !flow && "pb-clear-nav md:pb-0")}>
        {children}
      </main>
      <nav
        aria-label="Main"
        className={cn(
          "fixed inset-x-0 bottom-0 z-10 border-t border-divider bg-ground pb-safe md:hidden",
          flow && "hidden",
        )}
      >
        <ul className="grid grid-cols-4">
          {phoneTabs.map(({ href, label, icon: Icon }) => {
            const current = isCurrent(pathname, href);
            return (
              <li key={href}>
                <Link
                  href={href}
                  aria-current={current ? "page" : undefined}
                  className={cn(
                    "flex min-h-18 flex-col items-center justify-center gap-1 text-caption",
                    current ? "font-semibold text-text" : "font-medium text-text-muted",
                  )}
                >
                  <Icon aria-hidden="true" className="size-5.5" strokeWidth={current ? 2 : 1.75} />
                  {label}
                </Link>
              </li>
            );
          })}
        </ul>
      </nav>
    </div>
  );
}

/** The frame with nothing in it yet, while sign-in state loads: same bars, same heights. */
export function ShellSkeleton({ children }: { children?: ReactNode }) {
  return (
    <div className="flex min-h-dvh flex-col" aria-busy="true">
      <div className="h-15 bg-ground md:h-16 md:border-b md:border-divider" />
      <div className="flex w-full flex-1 flex-col">{children}</div>
    </div>
  );
}

/**
 * A list screen's content (Sessions, Credit): one column the width of the laptop's card, centred, with the page
 * margins; its title big, as every screen's question is.
 */
export function Page({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="mx-auto flex w-full max-w-card flex-col gap-7 px-4 pt-2 pb-8 md:px-6 md:pt-10">
      <h1 className="text-title xl:text-question">{title}</h1>
      {children}
    </div>
  );
}

/** A flow's back button on a phone (Book, the session screen), where the bars give way to the flow. */
export function FlowHeader({ back, title }: { back: string; title?: string }) {
  return (
    <div className="flex h-15 items-center gap-2 px-2 md:hidden">
      <Link
        href={back}
        aria-label="Back"
        className="flex size-touch items-center justify-center rounded-full"
      >
        <ChevronLeft aria-hidden="true" className="size-5.5" strokeWidth={2} />
      </Link>
      {title && <span className="text-body font-semibold">{title}</span>}
    </div>
  );
}
