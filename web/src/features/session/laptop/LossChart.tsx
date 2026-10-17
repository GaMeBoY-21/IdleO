import { Tile } from "@/components/ui/Tile";
import { thin } from "@/lib/log-signals";

const WIDTH = 820;
const HEIGHT = 130;

/** The line, and the same line closed down to the bottom, as SVG path data for the values spread over the width. */
export function paths(values: number[]): { line: string; area: string } {
  const points = thin(values);
  const low = Math.min(...points);
  const high = Math.max(...points);
  const span = high - low || 1;
  const x = (i: number) => (points.length === 1 ? 0 : (i * WIDTH) / (points.length - 1));
  const y = (value: number) => 8 + ((high - value) * (HEIGHT - 16)) / span;
  const line = points
    .map((value, i) => `${i === 0 ? "M" : "L"}${x(i).toFixed(1)} ${y(value).toFixed(1)}`)
    .join(" ");
  return { line, area: `${line} L${WIDTH} ${HEIGHT} L0 ${HEIGHT} Z` };
}

/**
 * "Training loss" (docs/design/bento/laptop/live-session-laptop.html): the loss values the job's own log lines print,
 * as an ink line over a lime area, with the first and the latest in the corner. Drawn only when the log carries
 * loss values: a job that prints none gets no chart, and nothing is made up.
 */
export function LossChart({ losses }: { losses: number[] }) {
  if (losses.length < 2) {
    return null;
  }
  const { line, area } = paths(losses);
  const first = losses[0]!;
  const latest = losses[losses.length - 1]!;
  const fmt = (value: number) => value.toFixed(2);
  return (
    <Tile
      as="section"
      aria-labelledby="loss-title"
      size="lg"
      className="flex flex-col gap-2 xl:px-6 xl:pt-5 xl:pb-4"
    >
      <div className="flex items-baseline justify-between">
        <h2 id="loss-title" className="text-option font-extrabold">
          Training loss
        </h2>
        <span className="text-small text-text-muted tabular">
          {fmt(first)} → <span className="font-extrabold text-text">{fmt(latest)}</span>
        </span>
      </div>
      <svg
        viewBox={`0 0 ${WIDTH} ${HEIGHT}`}
        preserveAspectRatio="none"
        role="img"
        aria-label={`Training loss, from ${fmt(first)} to ${fmt(latest)}`}
        className="block h-32 w-full"
      >
        <path d={area} className="fill-lime/45" />
        <path
          d={line}
          fill="none"
          className="stroke-text"
          strokeWidth="2.2"
          strokeLinejoin="round"
          strokeLinecap="round"
          vectorEffect="non-scaling-stroke"
        />
      </svg>
    </Tile>
  );
}
