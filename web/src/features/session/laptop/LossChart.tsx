import { Tile } from "@/components/ui/Tile";

/** How many bars the chart draws (approved reference `Session`). */
const BARS = 20;

/**
 * The losses as bar heights, 6% to 100% of the chart's height: the values are spread over `BARS` places (each place
 * takes the latest value that falls in it), the highest loss is the tallest bar. A run with fewer values than bars
 * draws one bar for each.
 */
export function barHeights(values: number[]): number[] {
  const count = Math.min(BARS, values.length);
  const picked = Array.from(
    { length: count },
    (_, i) => values[Math.floor(((i + 1) * values.length) / count) - 1]!,
  );
  const low = Math.min(...picked);
  const high = Math.max(...picked);
  const span = high - low || 1;
  return picked.map((value) => 6 + Math.round(((value - low) * 94) / span));
}

/**
 * "Training loss" (approved reference `Session`): the loss values the job's own log lines print, as bars in the ion-deep
 * accent with the latest in the running colour, and the first and the latest values in the corner. Drawn only when the
 * log carries loss values: a job that prints none gets no chart, and nothing is made up.
 */
export function LossChart({ losses }: { losses: number[] }) {
  if (losses.length < 2) {
    return null;
  }
  const heights = barHeights(losses);
  const first = losses[0]!;
  const latest = losses[losses.length - 1]!;
  const fmt = (value: number) => value.toFixed(2);
  return (
    <Tile as="section" aria-labelledby="loss-title" size="lg" className="flex flex-col gap-3.5">
      <div className="flex items-baseline justify-between">
        <h2 id="loss-title" className="text-section">
          Training loss
        </h2>
        <span className="text-body text-text-2 tabular">
          {fmt(first)} → <span className="font-bold text-text">{fmt(latest)}</span>
        </span>
      </div>
      <div
        role="img"
        aria-label={`Training loss, from ${fmt(first)} to ${fmt(latest)}`}
        className="flex h-loss items-end gap-1.25 rounded-inner bg-canvas p-4.5"
      >
        {heights.map((height, index) => (
          <span
            key={index}
            aria-hidden="true"
            style={{ height: `${height}%` }}
            className={
              index === heights.length - 1
                ? "min-w-0 flex-1 rounded-full bg-running-dot"
                : "min-w-0 flex-1 rounded-full bg-ion-deep"
            }
          />
        ))}
      </div>
    </Tile>
  );
}
