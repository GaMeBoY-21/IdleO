import { COMMISSION_PERCENT } from "@/lib/hosting";
import type { SiteContext } from "../context";
import { LivePrices } from "./LivePrices";
import { EXAMPLE_CLASS_GB } from "../examples";
import { band, column, heading, largeButton, muted, primaryButton, sectionTitle, tile } from "../ui";

/** The same, in lavender, for the two typical examples. */
const EXAMPLE_ROW = "flex h-24 flex-col justify-center rounded-card bg-ion px-6 text-ink";

/**
 * The pricing page (ADR 0029). Its figures come from GET /v1/public/availability after the page has painted
 * (src/site/islands/pricing.ts); until they arrive each row is a skeleton of the same size, and the same rows hold the
 * error and stale states, so nothing ever moves. Every worked figure says "typical".
 */
export function Pricing({ context }: { context: SiteContext }) {
  return (
    <>
      <section className={`${column} ${band}`}>
        <h1 className={`${heading} max-w-prose`}>Pricing</h1>
        <p className={`${muted} mt-4 max-w-prose md:text-card`}>
          You pay for the minutes your session runs, at the price of the GPU it was given. These are the
          prices right now, by the GPU memory your job needs.
        </p>
      </section>

      <section
        className={`${column} ${band}`}
        aria-labelledby="prices"
        data-live-prices=""
        data-api={context.apiUrl ?? ""}
      >
        <LivePrices />

        <h2 className={`${sectionTitle} mt-10`} id="examples">
          Two examples
        </h2>
        <p className="mt-2 text-small text-ink-subtle">
          Typical, not promises: the runs' minutes are the usual ones for each option, priced on the{" "}
          {EXAMPLE_CLASS_GB} GB class at the prices above. A job's real cost depends on the model, the data
          and the GPU it gets.
        </p>
        <ul className="mt-4 grid grid-cols-1 gap-2 md:grid-cols-2 md:gap-4">
          {context.examples.map((example) => (
            <li
              key={example.label}
              className={EXAMPLE_ROW}
              data-example=""
              data-minutes={example.minutes}
              data-gb={example.gpuMemoryGb}
              data-label={example.label}
            >
              <p className="text-card font-semibold">{example.label}</p>
              <p className="text-small text-ink">About {example.minutes} minutes, typical</p>
              <span
                data-role="skeleton"
                className="skeleton block h-6 w-2/5 rounded-control"
                aria-hidden="true"
              />
              <p data-role="cost" className="tabular h-6 text-body text-ink" hidden></p>
            </li>
          ))}
        </ul>
      </section>

      <section className={`${column} ${band}`} aria-labelledby="billing">
        <h2 id="billing" className={sectionTitle}>
          How billing works
        </h2>
        <dl className="mt-4 grid grid-cols-1 gap-2 md:grid-cols-2 md:gap-4">
          {[
            [
              "By the minute",
              "A GPU is charged per minute. A minute that has started counts as a whole one. A minute is charged only when both the GPU's own machine and our servers saw it run.",
            ],
            [
              "A hold of 30 minutes",
              "When you request a GPU we set aside up to 30 minutes of its price from your credit. It's a hold, not a charge: it's released as your session settles.",
            ],
            [
              "Settled as it runs",
              "Your session is settled every 15 minutes and again when it ends. The hold is topped up as it goes, and when your credit can't cover more, the session saves a checkpoint and stops.",
            ],
            [
              "Your spending cap",
              "Every session has a cap, the most it may cost you, shown before you request. It stops there, and a session never takes your credit below zero.",
            ],
            [
              "Lost time is refunded",
              "If a host drops out, minutes after your last saved checkpoint are waived, and any already charged are given back.",
            ],
            [
              `Our ${COMMISSION_PERCENT}% share`,
              `Of what you pay for a session, the host who ran it keeps ${100 - COMMISSION_PERCENT}% and we keep ${COMMISSION_PERCENT}%. It's inside the price you see, not added on top.`,
            ],
          ].map(([title, text]) => (
            <div key={title} className={tile}>
              <dt className="text-card font-semibold">{title}</dt>
              <dd className="mt-1 text-body text-ink-subtle">{text}</dd>
            </div>
          ))}
        </dl>
      </section>

      <section className={`${column} ${band}`}>
        <a href={context.links.getStarted} className={`${primaryButton} ${largeButton}`}>
          Get started
        </a>
      </section>
    </>
  );
}
