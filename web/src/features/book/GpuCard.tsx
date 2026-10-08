import { Cpu } from "lucide-react";
import type { ReactNode } from "react";
import { ApiError } from "@/api/errors";
import { IconTile } from "@/components/ui/Option";
import { SkeletonLine } from "@/components/ui/Skeleton";
import { cn } from "@/lib/cn";
import { gigabytes } from "@/lib/gpu";
import { formatEstimate, formatPaiseRange } from "@/lib/money";
import type { QuoteState } from "./useQuote";

/**
 * "Your GPU" (docs/design/redesign/book-laptop.html): one card, the best free GPU for this job. We match it when the
 * renter requests, like a ride; they never choose one. It shows the memory needed, how many GPUs fit right now, their
 * price per hour and an estimate (the typical runtime at those prices), all from the quote. `quote` is null while the
 * settings can't be quoted yet.
 */
export function GpuCard({
  quote,
  vramMb,
  typicalSeconds,
  laptop,
}: {
  quote: QuoteState | null;
  vramMb: number;
  typicalSeconds: number | null | undefined;
  laptop: boolean;
}) {
  const memory = `At least ${gigabytes(vramMb)}`;
  const best = laptop ? "Best free GPU for this job" : "Best free GPU";
  let title: ReactNode = best;
  let detail: ReactNode;
  let price: ReactNode = null;

  if (quote === null) {
    detail = "Finish your settings to see the price";
  } else if (quote.kind === "none-free") {
    title = "No GPU is free right now";
    detail = `${memory} · we look again every 15 s`;
  } else if (quote.kind === "error") {
    detail =
      quote.error instanceof ApiError && quote.error.type === "/problems/validation-failed"
        ? "Check your settings to see the price"
        : "We couldn't get a price. Trying again…";
  } else if (quote.kind === "loading") {
    detail = <SkeletonLine size={laptop ? "small" : "caption"} width="w-3/4" />;
    price = <SkeletonLine size="option" width="w-16" />;
  } else {
    const { hostsFitNow, pricePaisePerMin: range } = quote.quote;
    detail = `${memory} · ${hostsFitNow} free now${laptop ? " · picked for you" : ""}`;
    if (range) {
      const perHour = formatPaiseRange(range.min * 60, range.max * 60);
      const estimate =
        typicalSeconds === null || typicalSeconds === undefined
          ? null
          : formatEstimate(
              Math.floor((range.min * typicalSeconds) / 60),
              Math.ceil((range.max * typicalSeconds) / 60),
            );
      price = (
        <>
          <span className={laptop ? "text-option font-semibold" : "text-body font-semibold"}>
            {estimate ?? perHour}
          </span>
          <span className="text-text-muted">{estimate === null ? "an hour" : `${perHour}/h`}</span>
        </>
      );
    }
  }

  const free = quote?.kind === "quoted";
  return (
    <div className="flex flex-col gap-3">
      <div
        className={cn(
          "flex items-center gap-4 rounded-control border-2",
          laptop ? "p-4" : "p-3.5",
          free ? "border-text" : "border-transparent bg-fill",
        )}
      >
        <IconTile icon={Cpu} strong={free} size="lg" />
        <div className="flex min-w-0 flex-1 flex-col gap-0.5">
          <p className={cn("font-semibold", laptop ? "text-option" : "text-body")}>{title}</p>
          <div className={cn("text-text-muted tabular", laptop ? "text-small" : "text-caption")}>
            {detail}
          </div>
        </div>
        {price && (
          <div
            className={cn(
              "flex shrink-0 flex-col items-end gap-0.5 tabular",
              laptop ? "text-small" : "text-caption",
            )}
          >
            {price}
          </div>
        )}
      </div>
      <p className={cn("text-text-muted", laptop ? "text-small" : "text-caption")}>
        {laptop
          ? "Like a ride: you request, we match the best free GPU right now. You pay that GPU's price per minute."
          : "We match the best free GPU right now. You pay its price per minute."}
      </p>
    </div>
  );
}
