import type { ReactNode } from "react";
import { brand } from "@/brand/brand";
import { Wordmark } from "@/brand/Wordmark";
import { CONTACT, DOCS, EARN, GPUS, LANDING, PRICING, PRIVACY, SIGN_IN, TERMS } from "./paths";
import { column, link, primaryButton, secondaryButton } from "./ui";

const NAV = [
  { href: PRICING, label: "Pricing" },
  { href: GPUS, label: "GPUs" },
  { href: EARN, label: "Earn" },
  { href: DOCS, label: "Docs" },
] as const;

/**
 * The frame every public page shares (ADR 0029): the header with the pages as white pills, Sign in and the dark "Get started"
 * button, and the footer. Plain markup, no script. On a phone the pages sit on a row of their own under the wordmark
 * and the two buttons, so nothing is hidden behind a menu that would need script.
 */
export function Shell({ path, children }: { path: string; children: ReactNode }) {
  const current = (href: string) =>
    path === href || (href !== LANDING && path.startsWith(href)) ? "page" : undefined;
  return (
    <>
      <a
        href="#content"
        className="sr-only focus:not-sr-only focus:absolute focus:left-4 focus:top-4 focus:rounded-full focus:bg-primary focus:px-4 focus:py-3 focus:text-primary-fg"
      >
        Skip to the content
      </a>
      <header>
        <div
          className={`${column} flex flex-wrap items-center justify-between gap-x-6 gap-y-1 py-2 md:flex-nowrap`}
        >
          <a href={LANDING} className="flex min-h-touch items-center" aria-label={`${brand.name} home`}>
            <Wordmark />
          </a>
          <nav aria-label="Pages" className="order-last flex w-full gap-1 md:order-none md:w-auto md:flex-1">
            {NAV.map((item) => (
              <a
                key={item.href}
                href={item.href}
                aria-current={current(item.href)}
                className="flex min-h-touch items-center rounded-full bg-fill px-4 text-small font-semibold text-text hover:bg-fill-selected aria-[current=page]:bg-primary aria-[current=page]:text-primary-fg"
              >
                {item.label}
              </a>
            ))}
          </nav>
          <div className="flex items-center gap-2">
            <a href={SIGN_IN} className={secondaryButton}>
              Sign in
            </a>
            <a href={SIGN_IN} className={primaryButton}>
              Get started
            </a>
          </div>
        </div>
      </header>
      <main id="content" tabIndex={-1}>
        {children}
      </main>
      <footer>
        <div
          className={`${column} flex flex-col gap-3 py-8 text-small text-text-muted md:flex-row md:items-center md:justify-between`}
        >
          <p>© {brand.name}. We provide the GPU; you bring your own model, data and code.</p>
          <nav aria-label="About" className="flex gap-x-6">
            <a href={TERMS} className={`${link} flex min-h-touch items-center`}>
              Terms
            </a>
            <a href={PRIVACY} className={`${link} flex min-h-touch items-center`}>
              Privacy
            </a>
            <a href={CONTACT} className={`${link} flex min-h-touch items-center`}>
              Contact
            </a>
          </nav>
        </div>
      </footer>
    </>
  );
}
