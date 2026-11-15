import { brand } from "@/brand/brand";
import type { SiteContext } from "../context";
import { band, column, exampleTag, frame, heading, largeButton, muted, primaryButton } from "../ui";
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
      <section
        className={`${column} ${band} grid grid-cols-1 items-center gap-12 xl:grid-cols-2`}
        aria-labelledby="rent-title"
      >
        <div className="flex flex-col gap-4">
          <h1 id="rent-title" className={`${heading} max-w-prose`}>
            Rent a GPU
          </h1>
          <p className={`${muted} max-w-prose md:text-card`}>
            Fine-tune a model, run your code or your own image on an RTX GPU someone nearby isn&apos;t using.
            You pay in rupees for the minutes it ran, never more than the cap you set.
          </p>
          <a href={context.links.getStarted} className={`${primaryButton} ${largeButton} self-start`}>
            Rent a GPU
          </a>
          <p className="text-small text-ink-subtle">
            {brand.name} is invite-only while it&apos;s in its pilot.
          </p>
        </div>
        <div
          role="img"
          aria-label="An example of the booking form: what to run, how much GPU memory, and the request"
          className={`${frame} flex flex-col gap-4`}
        >
          <div className="flex items-center justify-between gap-2">
            <span className="text-card">What do you want to run?</span>
            <span className={exampleTag}>Example</span>
          </div>
          <div className="flex flex-col gap-2">
            <div className="flex items-center gap-4 rounded-card border border-hairline-tertiary bg-surface-3 px-4 py-4">
              <span className="flex size-9 items-center justify-center rounded-control border border-hairline bg-surface-4 text-ink-muted">
                1
              </span>
              <span className="flex flex-1 flex-col">
                <span className="text-card">Fine-tune a model</span>
                <span className="text-small text-ink-subtle">Your model and examples</span>
              </span>
            </div>
            <div className="flex items-center gap-4 rounded-card border border-hairline bg-surface-1 px-4 py-4">
              <span className="flex size-9 items-center justify-center rounded-control border border-hairline bg-surface-2 text-ink-muted">
                2
              </span>
              <span className="flex flex-1 flex-col">
                <span className="text-card">Run your code</span>
                <span className="text-small text-ink-subtle">Your folder, our GPU</span>
              </span>
            </div>
          </div>
          <div className="flex items-center justify-between gap-2 rounded-card border border-hairline bg-surface-2 px-4 py-2 text-small">
            <span className="text-ink-subtle">GPU memory</span>
            <span className="tabular">12 GB</span>
          </div>
          <span className={`${primaryButton} ${largeButton} w-full`}>Request a GPU</span>
          <p className="text-caption text-ink-tertiary">
            An example, not the real form. Prices are shown live below.
          </p>
        </div>
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
