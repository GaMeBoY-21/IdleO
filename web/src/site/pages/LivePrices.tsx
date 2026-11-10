import { GPU_CLASSES } from "../gpus";
import { sectionTitle } from "../ui";

/** A row's place on the page, 96 px high whatever it holds, so what arrives never moves anything. */
const ROW = "flex h-24 flex-col justify-center rounded-panel bg-panel px-5 text-text";

/**
 * The live prices by GPU memory (ADR 0029): one row each, filled by src/site/islands/pricing.ts from
 * GET /v1/public/availability after the page has painted, each a skeleton of the same size until then, with whether any
 * are online now; the same rows hold the error and stale states, so nothing ever moves. Pricing and Rent show it (ADR
 * 0058); its section is the island's `[data-live-prices]`, which the caller opens.
 */
export function LivePrices({ title = "What a GPU costs" }: { title?: string }) {
  return (
    <>
      <h2 id="prices" className={sectionTitle}>
        {title}
      </h2>
      <ul className="mt-4 grid gap-2.5 md:grid-cols-2 md:gap-3.5">
        {GPU_CLASSES.map((gpu) => (
          <li key={gpu.gb} className={ROW} data-class-row={gpu.gb} data-state="loading">
            <p className="flex items-center justify-between text-option font-semibold">
              <span>{gpu.gb} GB GPU memory</span>
              <span data-role="online" className="text-small font-medium text-text-2" hidden></span>
            </p>
            <span
              data-role="skeleton"
              className="skeleton mt-1 block h-6 w-3/5 rounded-control"
              aria-hidden="true"
            />
            <p data-role="price" className="tabular h-6 text-option text-text" hidden></p>
            <p data-role="kind" className="h-4.5 text-caption text-text-2"></p>
          </li>
        ))}
      </ul>
      <div
        className="mt-2 flex min-h-14 items-start justify-between gap-x-3 text-caption text-text-2"
        aria-live="polite"
      >
        <p data-role="status" className="flex-1">
          Looking up today's prices…
        </p>
        <button
          type="button"
          data-role="retry"
          className="min-h-touch shrink-0 px-2 text-small font-semibold text-text underline underline-offset-4"
          hidden
        >
          Try again
        </button>
      </div>
      <p data-role="credit" className="mt-2 min-h-6 text-body text-text-2"></p>
      <noscript>
        <p className="text-small text-text-2">Today's prices need JavaScript on this page.</p>
      </noscript>
    </>
  );
}
