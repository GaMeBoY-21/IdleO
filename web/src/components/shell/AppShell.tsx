import { ChevronLeft } from "lucide-react";
import { Link, useLocation } from "wouter";
import type { ReactNode } from "react";
import { brand } from "@/brand/brand";
import { Wordmark } from "@/brand/Wordmark";
import { cn } from "@/lib/cn";
import { NotificationBanners } from "./NotificationBanners";
import { NotificationBell } from "./NotificationBell";
import { AccountLink } from "./AccountLink";
import { CreditChip } from "./CreditChip";
import { FRAME } from "./Layout";
import { AppLink } from "@/components/ui/AppLink";
import { destinations, isCurrent, isFlow, phoneDestinations } from "./nav";
import { UserMenuButton } from "./UserMenuButton";

/**
 * The frame around every signed-in screen (web/DESIGN.md "Navigation"). On a phone: a header with the wordmark, the
 * bell and the account button, and a floating white tab bar (Home, Book, Hosting, Credit); Book and the session
 * screen are flows there, full screen with their own back button. From 768 px: one top bar with the wordmark, the
 * same four as white pill tabs on the page colour, the bell and the account button. The frame never changes between
 * pages, so switching pages swaps only the content.
 */
export function AppShell({ children }: { children: ReactNode }) {
  const [pathname] = useLocation();
  const flow = isFlow(pathname);
  return (
    <div className="flex min-h-dvh flex-col">
      <a
        href="#main"
        className="sr-only z-30 rounded-full bg-fill px-4 py-3 focus:not-sr-only focus:fixed focus:top-2 focus:left-2"
      >
        Skip to content
      </a>
      <header className={cn("sticky top-0 z-10 bg-ground", flow ? "hidden md:block" : "block")}>
        <div className={cn(FRAME, "flex h-16 items-center gap-6 md:h-header")}>
          <div className="flex flex-1 items-center">
            <Link href="/" className="flex min-h-touch items-center" aria-label={`${brand.name} home`}>
              <Wordmark className="md:text-headline md:tracking-tighter" />
            </Link>
          </div>
          <nav aria-label="Main" className="hidden items-center gap-1 rounded-full bg-fill p-1.25 md:flex">
            {destinations.map(({ href, label }) => {
              const current = isCurrent(pathname, href);
              return (
                <AppLink
                  key={href}
                  href={href}
                  aria-current={current ? "page" : undefined}
                  className={cn(
                    "flex min-h-touch items-center rounded-full px-5 text-small transition-colors",
                    current
                      ? "bg-primary font-bold text-primary-fg"
                      : "font-semibold text-text-muted hover:text-text",
                  )}
                >
                  {label}
                </AppLink>
              );
            })}
          </nav>
          <div className="flex flex-1 items-center justify-end gap-2.5">
            <CreditChip />
            <NotificationBell />
            <div className="md:hidden">
              <UserMenuButton />
            </div>
            <AccountLink />
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
          "fixed inset-x-3.5 bottom-tab z-10 h-16 rounded-bar bg-fill shadow-bar md:hidden",
          flow && "hidden",
        )}
      >
        <ul className="grid h-full grid-cols-4">
          {phoneDestinations.map(({ href, label }) => {
            const current = isCurrent(pathname, href);
            return (
              <li key={href} className="flex">
                <AppLink
                  href={href}
                  aria-current={current ? "page" : undefined}
                  className={cn(
                    "flex flex-1 items-center justify-center rounded-bar text-caption font-semibold",
                    current ? "text-text" : "text-text-muted",
                  )}
                >
                  {label}
                </AppLink>
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
      <div className="h-16 bg-ground md:h-header" />
      <div className="flex w-full flex-1 flex-col">{children}</div>
    </div>
  );
}

/**
 * A list screen's content (Notifications; Account and the lists use their own frames on a laptop): one column the
 * width of the card, centred (7 of the 12 columns from 1200 px), with the page margins; its title big.
 */
export function Page({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="w-full px-3.5 pt-2 pb-8 md:px-6 md:pt-6 xl:px-8 xl:pt-4 2xl:px-10">
      <div className="mx-auto flex w-full max-w-card flex-col gap-6 xl:w-7/12 xl:max-w-none">
        <h1 className="text-title xl:text-question">{title}</h1>
        {children}
      </div>
    </div>
  );
}

/** A flow's back button on a phone (Book, the session screen), where the bars give way to the flow. */
export function FlowHeader({ back, title }: { back: string; title?: string }) {
  return (
    <div className="flex h-16 items-center gap-3 px-3.5 md:hidden">
      <Link
        href={back}
        aria-label="Back"
        className="flex size-touch items-center justify-center rounded-full bg-fill"
      >
        <ChevronLeft aria-hidden="true" className="size-5.5" strokeWidth={2} />
      </Link>
      {title && <span className="text-body font-semibold">{title}</span>}
    </div>
  );
}
