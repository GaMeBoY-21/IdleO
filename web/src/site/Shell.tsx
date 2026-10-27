import type { ReactNode } from "react";
import { brand } from "@/brand/brand";
import { Wordmark } from "@/brand/Wordmark";
import { CONTACT, DOCS, EARN, GPUS, LANDING, PRICING, PRIVACY, TERMS, type EntryLinks } from "./paths";
import { column, ghostButton, primaryButton } from "./ui";

const NAV = [
  { href: PRICING, label: "Pricing" },
  { href: GPUS, label: "GPUs" },
  { href: EARN, label: "Earn" },
  { href: DOCS, label: "Docs" },
] as const;

const FOOTER = [
  {
    title: "Product",
    links: [
      { href: PRICING, label: "Pricing" },
      { href: GPUS, label: "GPUs" },
      { href: EARN, label: "Earn" },
    ],
  },
  { title: "Developers", links: [{ href: DOCS, label: "Docs" }] },
  {
    title: "Company",
    links: [
      { href: CONTACT, label: "Contact" },
      { href: TERMS, label: "Terms" },
      { href: PRIVACY, label: "Privacy" },
    ],
  },
] as const;

/** Appearance: the same three choices, in the same words, as the app's (src/components/shell/AppearanceChoice.tsx). */
const APPEARANCE = [
  { value: "system", label: "Match my device" },
  { value: "light", label: "Light" },
  { value: "dark", label: "Dark" },
] as const;

/**
 * The frame every public page shares (ADR 0029): the header with the wordmark, the pages, "Log in" and the volt "Get
 * started" button, both into the app at its own address, and the footer with its Appearance switch. Plain markup; the
 * switch is made to work by the one small script every page loads (src/site/islands/theme.ts), and holds its place until
 * then. On a phone the pages sit on a row of their own under the wordmark and the two buttons, so nothing is hidden
 * behind a menu that would need script.
 */
export function Shell({ path, links, children }: { path: string; links: EntryLinks; children: ReactNode }) {
  const current = (href: string) =>
    path === href || (href !== LANDING && path.startsWith(href)) ? "page" : undefined;
  return (
    <>
      <a
        href="#content"
        className="sr-only focus:not-sr-only focus:absolute focus:top-4 focus:left-4 focus:rounded-control focus:bg-primary focus:px-4 focus:py-3 focus:text-primary-fg"
      >
        Skip to the content
      </a>
      <header>
        <div className={`${column} flex flex-wrap items-center justify-between gap-x-6 gap-y-2 py-5`}>
          <a href={LANDING} className="flex min-h-touch items-center" aria-label={`${brand.name} home`}>
            <Wordmark />
          </a>
          <nav aria-label="Pages" className="order-last flex w-full gap-1 md:order-none md:w-auto">
            {NAV.map((item) => (
              <a
                key={item.href}
                href={item.href}
                aria-current={current(item.href)}
                className="flex min-h-touch items-center rounded-control px-3 text-small font-medium text-text hover:underline hover:underline-offset-4 aria-[current=page]:font-bold aria-[current=page]:underline aria-[current=page]:underline-offset-8 md:px-4"
              >
                {item.label}
              </a>
            ))}
          </nav>
          <div className="flex items-center gap-2">
            <a href={links.logIn} className={ghostButton}>
              Log in
            </a>
            <a href={links.getStarted} className={primaryButton}>
              Get started
            </a>
          </div>
        </div>
      </header>
      <main id="content" tabIndex={-1}>
        {children}
      </main>
      <footer className={`${column} flex flex-col gap-12 pt-12 pb-10 md:pt-16`}>
        <div className="grid gap-8 text-small sm:grid-cols-2 md:grid-cols-4">
          {FOOTER.map((group) => (
            <nav key={group.title} aria-label={group.title} className="flex flex-col gap-1">
              <p className="mb-1 font-semibold text-text-3">{group.title}</p>
              {group.links.map((item) => (
                <a
                  key={item.href}
                  href={item.href}
                  className="flex min-h-touch items-center text-text hover:underline hover:underline-offset-4"
                >
                  {item.label}
                </a>
              ))}
            </nav>
          ))}
          <div className="flex flex-col gap-1">
            <p id="appearance" className="mb-1 font-semibold text-text-3">
              Appearance
            </p>
            <div
              role="group"
              aria-labelledby="appearance"
              data-appearance=""
              className="invisible flex flex-wrap gap-1"
            >
              {APPEARANCE.map((choice) => (
                <button
                  key={choice.value}
                  type="button"
                  aria-pressed="false"
                  data-choice={choice.value}
                  className="min-h-touch rounded-control border border-edge bg-ghost px-3 text-small font-semibold text-text hover:bg-ghost-hover aria-pressed:border-transparent aria-pressed:bg-primary aria-pressed:text-primary-fg"
                >
                  {choice.label}
                </button>
              ))}
            </div>
          </div>
        </div>
        <p className="text-small text-text-3">
          © {brand.name}. We provide the GPU; you bring your own model, data and code.
        </p>
        <span
          aria-hidden="true"
          data-text={brand.name.toLowerCase()}
          className="ghost-wordmark font-display text-display-wordmark text-bone select-none"
        />
      </footer>
    </>
  );
}
