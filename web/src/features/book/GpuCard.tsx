import { Cpu } from "lucide-react";
import type { ReactNode } from "react";
import { ApiError } from "@/api/errors";
import { IconTile } from "@/components/ui/Option";
import { SkeletonLine } from "@/components/ui/Skeleton";
import { cn } from "@/lib/cn";
import { formatEstimate, formatPaiseRange } from "@/lib/money";
import type { QuoteState } from "./useQuote";
import { BIGGEST_MB, classGigabytes } from "./vram";

/** The GPU memory the quote says the booking needs, or, before it says, what the form asks for. */
export function memoryNeeded(quote: QuoteState | null, asked: number): number {
  const quoted = quote?.kind === "quoted" || quote?.kind === "none-free" ? quote.quote : null;
  return quoted?.vramNeededMb ?? asked;
}

/** Whether no GPU here could ever take it: it needs more memory than the biggest has. */
export const tooBig = (quote: QuoteState | null, asked: number) => memoryNeeded(quote, asked) > BIGGEST_MB;

/**
 * "Your GPU" (docs/design/redesign/book-finetune-laptop.html): one card, the best free GPU with the memory this job
 * needs. We match it when the renter requests, like a ride; they never choose one. It shows how many GPUs fit right
 * now, their price per hour and an estimate for the typical run, all from the quote. `quote` is null while the
 * answers can't be quoted yet. A model too big for any GPU here says so, never "No GPU free", since waiting won't help.
 */
export function GpuCard({
  quote,
  vramMb,
  typicalMinutes,
  laptop,
}: {
  quote: QuoteState | null;
  vramMb: number;
  typicalMinutes: number | null;
  laptop: boolean;
}) {
  const needed = memoryNeeded(quote, vramMb);
  let title: ReactNode = `Best free ${classGigabytes(needed)} GPU`;
  let detail: ReactNode;
  let price: ReactNode = null;

  if (needed > BIGGEST_MB) {
    title = `Your model needs about ${classGigabytes(needed)} of GPU memory.`;
    detail = `The biggest GPUs on IdleO have ${classGigabytes(BIGGEST_MB)}.`;
  } else if (quote === null) {
    detail = "Answer the questions to see the price";
  } else if (quote.kind === "none-free") {
    title = "No GPU is free right now";
    detail = laptop ? `${classGigabytes(needed)} or more · we look again every 15 s` : "Checked every 15 s";
  } else if (quote.kind === "error") {
    detail =
      quote.error instanceof ApiError && quote.error.type === "/problems/validation-failed"
        ? "Check your answers to see the price"
        : "We couldn't get a price. Trying again…";
  } else if (quote.kind === "loading") {
    detail = <SkeletonLine size={laptop ? "small" : "caption"} width="w-3/4" />;
    // The estimate's line and the price's under it, as they'll load.
    price = (
      <>
        <SkeletonLine size={laptop ? "option" : "body"} width="w-16" />
        <SkeletonLine size={laptop ? "small" : "caption"} width="w-24" />
      </>
    );
  } else {
    const { hostsFitNow, pricePaisePerMin: range } = quote.quote;
    detail = laptop ? `${hostsFitNow} free now · picked for you` : `${hostsFitNow} free now`;
    if (range) {
      const perHour = formatPaiseRange(range.min * 60, range.max * 60);
      const estimate =
        typicalMinutes === null
          ? null
          : formatEstimate(range.min * typicalMinutes, range.max * typicalMinutes);
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

  const free = quote?.kind === "quoted" && needed <= BIGGEST_MB;
  return (
    <div
      data-testid="gpu-card"
      className={cn(
        "flex items-center gap-4 rounded-control border-2",
        laptop ? "p-3.5" : "p-3",
        free ? "border-text" : "border-transparent bg-fill",
      )}
    >
      <IconTile icon={Cpu} strong={free} size="lg" />
      <div className="flex min-w-0 flex-1 flex-col gap-0.5">
        <p className={cn("font-semibold", laptop ? "text-option" : "text-body")}>{title}</p>
        <div className={cn("text-text-muted tabular", laptop ? "text-small" : "text-caption")}>{detail}</div>
      </div>
      {price && (
        <div
          className={cn(
            "flex shrink-0 flex-col items-end gap-0.5 tabular",
            laptop ? "w-price text-small" : "w-24 text-caption",
          )}
        >
          {price}
        </div>
      )}
    </div>
  );
}
