import { brand } from "@/brand/brand";
import type { SiteContext } from "../context";
import { band, column, heading, largeButton, muted, primaryButton } from "../ui";
import { HowItWorks, Questions, Sealed } from "./Landing";
import { LivePrices } from "./LivePrices";

/**
 * The renter's page (ADR 0058): how it works, the GPUs and their prices right now (live, after the page has painted, and
 * "typical" until then: src/site/islands/pricing.ts), the sandbox and what it keeps apart, the questions, and one button
 * into the renter app. No figure here is invented: a live one is the API's, the rest say "typical".
 */
export function Rent({ context }: { context: SiteContext }) {
  return (
    <>
      <section className={`${column} ${band} flex flex-col gap-5`} aria-labelledby="rent-title">
        <h1 id="rent-title" className={`${heading} max-w-prose`}>
          Rent a GPU
        </h1>
        <p className={`${muted} max-w-prose md:text-option`}>
          Fine-tune a model, run your code or your own image on an RTX GPU someone nearby isn&apos;t using.
          You pay in rupees for the minutes it ran, never more than the cap you set.
        </p>
        <a href={context.links.getStarted} className={`${primaryButton} ${largeButton} self-start`}>
          Rent a GPU
        </a>
        <p className="text-small text-text-3">{brand.name} is invite-only while it&apos;s in its pilot.</p>
      </section>
      <HowItWorks />
      <section
        className={`${column} ${band}`}
        aria-labelledby="prices"
        data-live-prices=""
        data-api={context.apiUrl ?? ""}
      >
        <LivePrices title="The GPUs, and what they cost right now" />
      </section>
      <Sealed />
      <Questions />
    </>
  );
}
