import { Link } from "wouter";
import type { EarningsEntry } from "@/api/hosting";
import { Money } from "@/components/ui/Money";
import { Tile } from "@/components/ui/Tile";
import { RelativeTime } from "@/components/ui/Time";
import { cn } from "@/lib/cn";
import { formatMinutes } from "@/lib/hosting";

/** "Latest earnings", the last three entries of the ledger's `host_payable`, with "All ›" (docs/design/bento/laptop/hosting-laptop.html). */
export function LatestEarnings({ entries, className }: { entries: EarningsEntry[]; className?: string }) {
  return (
    <Tile
      aria-labelledby="latest-earnings"
      size="lg"
      className={cn("flex min-h-0 flex-col gap-1 xl:p-6", className)}
    >
      <div className="flex items-baseline justify-between pb-1.5">
        <h2 id="latest-earnings" className="text-option font-extrabold">
          Latest earnings
        </h2>
        <Link href="/hosting/earnings/" className="text-caption font-bold hover:underline">
          All ›
        </Link>
      </div>
      {entries.length === 0 ? (
        <p className="py-3 text-small text-text-muted">
          Nothing earned yet. It shows here as soon as a session ends.
        </p>
      ) : (
        <ul className="flex flex-col">
          {entries.slice(0, 3).map((entry) => {
            const givenBack = entry.amountPaise < 0;
            return (
              <li
                key={entry.id}
                className="flex items-center justify-between gap-3 border-b border-divider py-2.5 text-small last:border-b-0"
              >
                <span className="min-w-0">
                  <span className="block truncate font-bold">
                    {entry.label}
                    {!givenBack && entry.minutes > 0 && ` · ${formatMinutes(entry.minutes)}`}
                  </span>
                  <span className="block truncate text-caption text-text-muted first-letter:uppercase">
                    {entry.machineName} · <RelativeTime value={entry.createdAt} />
                  </span>
                </span>
                <span className={cn("font-extrabold tabular", givenBack && "text-text-muted")}>
                  <span className="sr-only">{givenBack ? "Taken back " : "Earned "}</span>
                  <span aria-hidden="true">{givenBack ? "−" : "+"}</span>
                  <Money paise={Math.abs(entry.amountPaise)} />
                </span>
              </li>
            );
          })}
        </ul>
      )}
    </Tile>
  );
}
