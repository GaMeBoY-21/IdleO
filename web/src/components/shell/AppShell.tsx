import { Link, useLocation } from "wouter";
import type { ReactNode } from "react";
import { Wordmark } from "@/brand/Wordmark";
import { cn } from "@/lib/cn";
import { CreditChip } from "./CreditChip";
import { destinations, isCurrent } from "./nav";
import { UserMenuButton } from "./UserMenuButton";

/**
 * The frame around every signed-in screen (web/DESIGN.md "Space and shape"): one top bar with the wordmark,
 * credit and the user menu; the destinations as links in it from 768 px, and as a bottom tab bar below that.
 * The frame never changes between pages, so switching pages swaps only the content.
 */
export function AppShell({ children }: { children: ReactNode }) {
  const [pathname] = useLocation();
  return (
    <div className="flex min-h-dvh flex-col">
      <a
        href="#main"
        className="sr-only z-30 rounded-control bg-surface px-4 py-3 focus:not-sr-only focus:fixed focus:top-2 focus:left-2"
      >
        Skip to content
      </a>
      <header className="sticky top-0 z-10 border-b border-border bg-surface">
        <div className="mx-auto flex h-16 max-w-content items-center gap-4 px-4 md:px-6 xl:px-8">
          <Link href="/" className="flex min-h-touch items-center" aria-label="IdleO home">
            <Wordmark className="h-6 w-auto" />
          </Link>
          <nav aria-label="Main" className="hidden flex-1 items-center gap-1 md:flex">
            {destinations.map(({ href, label }) => (
              <Link
                key={href}
                href={href}
                aria-current={isCurrent(pathname, href) ? "page" : undefined}
                className={cn(
                  "flex min-h-touch items-center rounded-control px-3 text-small font-medium",
                  isCurrent(pathname, href)
                    ? "bg-accent-soft text-accent-text"
                    : "text-text-muted hover:text-text",
                )}
              >
                {label}
              </Link>
            ))}
          </nav>
          <div className="ml-auto flex items-center gap-1">
            {/* Home shows credit in its own card; once is enough. */}
            {pathname !== "/" && <CreditChip />}
            <UserMenuButton />
          </div>
        </div>
      </header>
      <main
        id="main"
        className="mx-auto w-full max-w-content flex-1 px-4 pt-6 pb-clear-nav md:px-6 md:pb-12 xl:px-8"
      >
        {children}
      </main>
      <nav
        aria-label="Main"
        className="fixed inset-x-0 bottom-0 z-10 border-t border-border bg-surface pb-safe md:hidden"
      >
        <ul className="grid grid-cols-3">
          {destinations.map(({ href, label, icon: Icon, ...rest }) => {
            const current = isCurrent(pathname, href);
            return (
              <li key={href}>
                <Link
                  href={href}
                  aria-current={current ? "page" : undefined}
                  className={cn(
                    "flex min-h-16 flex-col items-center justify-center gap-1 text-caption font-medium",
                    current ? "text-accent-text" : "text-text-muted",
                  )}
                >
                  <Icon aria-hidden="true" className="size-5" strokeWidth={current ? 2.25 : 1.75} />
                  {"short" in rest ? rest.short : label}
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
      <div className="h-16 border-b border-border bg-surface" />
      <div className="mx-auto w-full max-w-content flex-1 px-4 pt-6 md:px-6 xl:px-8">{children}</div>
    </div>
  );
}
