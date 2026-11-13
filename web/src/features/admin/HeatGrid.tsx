import { Link } from "wouter";
import type { components } from "@/api/schema.gen";
import { cn } from "@/lib/cn";
import { Empty } from "./ui";

/**
 * The control room's heat maps (ADR 0056): a chart drawn as a table, so a screen reader reads it cell by cell. It's the
 * one table that isn't DataTable: a grid of shaded hours, not a list of records (eslint-rules/one-component.js).
 */
type HeatCell = components["schemas"]["ControlRoomCell"];

const SHADES = ["", "bg-live/10", "bg-live/15", "bg-live/20", "bg-live/30"];

/** A GPU model by hour of day in India, each cell shaded by its share of the busiest, with its value for a screen reader. */
export function Heatmap({
  caption,
  cells,
  href,
}: {
  caption: string;
  cells: HeatCell[];
  /** The list behind a cell, when it has one: its number then opens it. */
  href?: (model: string, hour: number) => string | null;
}) {
  const rows = [...new Set(cells.map((cell) => cell.model))].sort();
  const most = Math.max(0, ...cells.map((cell) => cell.value));
  if (rows.length === 0) {
    return <Empty title={caption}>Nothing in the last 7 days yet.</Empty>;
  }
  return (
    <div className="min-w-0 overflow-x-auto">
      <table className="w-full border-collapse text-caption">
        <caption className="pb-2 text-left text-small font-semibold">{caption}</caption>
        <thead>
          <tr>
            <th scope="col" className="pr-2 text-left font-medium">
              GPU model
            </th>
            {Array.from({ length: 24 }, (_, hour) => (
              <th key={hour} scope="col" className="w-6 text-center font-normal text-ink-subtle">
                {hour % 6 === 0 ? hour : <span className="sr-only">{hour}</span>}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((model) => (
            <tr key={model}>
              <th scope="row" className="pr-2 text-left font-medium whitespace-nowrap">
                {model}
              </th>
              {Array.from({ length: 24 }, (_, hour) => {
                const value = cells.find((cell) => cell.model === model && cell.hour === hour)?.value ?? 0;
                const shade = most === 0 || value === 0 ? 0 : Math.max(1, Math.ceil((4 * value) / most));
                return (
                  <td key={hour} className={cn("h-6 border border-hairline p-0", SHADES[shade])}>
                    {value > 0 && href?.(model, hour) ? (
                      <Link
                        href={href(model, hour) ?? ""}
                        className="flex h-6 w-full items-center justify-center text-caption tabular"
                        title={`${model} at ${hour}:00: ${value}`}
                      >
                        {value}
                        <span className="sr-only">
                          {" "}
                          at {hour}:00, {model}: open these bookings
                        </span>
                      </Link>
                    ) : (
                      value > 0 && (
                        <span className="flex h-6 items-center justify-center text-caption tabular">
                          {Math.round(value * 10) / 10}
                          <span className="sr-only"> at {hour}:00</span>
                        </span>
                      )
                    )}
                  </td>
                );
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
