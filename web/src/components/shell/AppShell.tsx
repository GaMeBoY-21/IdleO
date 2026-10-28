import { ChevronLeft } from "lucide-react";
import { Link, useLocation } from "wouter";
import type { ReactNode } from "react";
import { brand } from "@/brand/brand";
import { Wordmark } from "@/brand/Wordmark";
import { AppLink } from "@/components/ui/AppLink";
import { PageHeading } from "@/components/ui/Heading";
import { cn } from "@/lib/cn";
import { NotificationBanners } from "./NotificationBanners";
import { NotificationBell } from "./NotificationBell";
import { isCurrent, isFlow, phoneDestinations } from "./nav";
import { Sidebar, SIDEBAR_PUSH } from "./Sidebar";
import { UserMenuButton } from "./UserMenuButton";

/**
 * The frame around every signed-in screen (web/DESIGN.md "Navigation"). From 768 px: the left sidebar and the content
 * beside it. On a phone: a header with the wordmark and the bell, and a floating tab bar of the five most used
 * destinations and "More"; Book and the session screen are flows there, full screen with their own back button. The
 * frame never changes between pages, so switching pages swaps only the content.
 */
export function AppShell({ children }: { children: ReactNode }) {
  const [pathname] = useLocation();
  const flow = isFlow(pathname);
  return (
    <div className={cn("flex min-h-dvh flex-col", SIDEBAR_PUSH)}>
      <a
        href="#main"
        className="sr-only z-30 rounded-full bg-panel px-4 py-3 focus:not-sr-only focus:fixed focus:top-2 focus:left-2"
      >
        Skip to content
      </a>
      <Sidebar />
      <header className={cn("sticky top-0 z-10 bg-canvas md:hidden", flow && "hidden")}>
        <div className="flex h-16 items-center justify-between gap-6 px-3.5">
          <Link href="/" className="flex min-h-touch items-center" aria-label={`${brand.name} home`}>
            <Wordmark />
          </Link>
          <NotificationBell />
        </div>
      </header>
      <NotificationBanners />
      <main id="main" className={cn("flex w-full flex-1 flex-col", !flow && "pb-clear-nav md:pb-0")}>
        {children}
      </main>
      <nav
        aria-label="Main"
        className={cn(
          "fixed inset-x-3.5 bottom-tab z-10 h-16 rounded-bar border border-line bg-panel md:hidden",
          flow && "hidden",
        )}
      >
        <ul className="grid h-full grid-cols-6">
          {phoneDestinations.map(({ href, label, icon: Icon }) => {
            const current = isCurrent(pathname, href);
            return (
              <li key={href} className="flex">
                <AppLink
                  href={href}
                  aria-current={current ? "page" : undefined}
                  className={cn(
                    "relative flex flex-1 flex-col items-center justify-center gap-0.5 rounded-bar",
                    current ? "font-semibold text-text" : "font-medium text-text-2",
                  )}
                >
                  {current && (
                    <span aria-hidden="true" className="absolute top-1 h-1 w-6 rounded-full bg-progress" />
                  )}
                  <Icon aria-hidden="true" className="size-5.5" strokeWidth={current ? 2 : 1.75} />
                  <span className="text-caption">{label}</span>
                </AppLink>
              </li>
            );
          })}
          <li className="flex">
            <UserMenuButton tab />
          </li>
        </ul>
      </nav>
    </div>
  );
}

/** The frame with nothing in it yet, while sign-in state loads: the same sidebar's width, the same header's height. */
export function ShellSkeleton({ children }: { children?: ReactNode }) {
  return (
    <div className={cn("flex min-h-dvh flex-col", SIDEBAR_PUSH)} aria-busy="true">
      <div className="fixed inset-y-0 left-0 hidden w-sidebar border-r border-line bg-sidebar md:block" />
      <div className="h-16 bg-canvas md:hidden" />
      <div className="flex w-full flex-1 flex-col">{children}</div>
    </div>
  );
}

/**
 * A list screen's content (Notifications; Account and the lists use their own frames on a laptop): one column the
 * width of the card, centred (7 of the 12 columns from 1200 px), with the page margins; its headline big.
 */
export function Page({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="w-full px-3.5 pt-2 pb-8 md:px-8 md:pt-8 xl:px-10 md:pb-12">
      <div className="mx-auto flex w-full max-w-card flex-col gap-6 xl:w-7/12 xl:max-w-none">
        <PageHeading>{title}</PageHeading>
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
        className="flex size-touch items-center justify-center rounded-full bg-panel"
      >
        <ChevronLeft aria-hidden="true" className="size-5.5" strokeWidth={2} />
      </Link>
      {title && <span className="text-body font-semibold">{title}</span>}
    </div>
  );
}
