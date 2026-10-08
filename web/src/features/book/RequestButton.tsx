import type { ReactNode } from "react";
import { ApiError } from "@/api/errors";
import { Banner } from "@/components/ui/Banner";
import { Button } from "@/components/ui/Button";
import { Money } from "@/components/ui/Money";
import { SkeletonLine } from "@/components/ui/Skeleton";
import { cn } from "@/lib/cn";
import { problemMessage } from "@/lib/problems";
import type { QuoteState } from "./useQuote";

const settingsRefused = (error: unknown) =>
  error instanceof ApiError && error.type === "/problems/validation-failed";

/**
 * "Request a GPU", the screen's one primary button, and the line under it: what's held while it runs and the credit
 * there is, both from the quote (docs/design/redesign/book-laptop.html). It waits, saying why, while the upload is
 * going ("Waiting for your upload… 64%"), while no GPU is free, and when the credit is short (both figures). With
 * settings still to finish it can be pressed, and pressing opens them.
 */
export function RequestButton({
  quote,
  uploadingPercent,
  busy,
  error,
  onRequest,
  laptop,
}: {
  quote: QuoteState | null;
  uploadingPercent: number | null;
  busy: boolean;
  error: unknown;
  onRequest: () => void;
  laptop: boolean;
}) {
  const quoted = quote?.kind === "quoted" ? quote.quote : null;
  const short = quoted !== null && !quoted.enoughCredit;
  const waiting =
    uploadingPercent !== null ||
    short ||
    quote?.kind === "loading" ||
    quote?.kind === "none-free" ||
    (quote?.kind === "error" && !settingsRefused(quote.error));

  let line: ReactNode;
  if (quote === null || (quote.kind === "error" && settingsRefused(quote.error))) {
    line = "Finish your settings to see what's held while it runs.";
  } else if (quote.kind === "loading") {
    line = <SkeletonLine size={laptop ? "small" : "caption"} width="w-3/4" />;
  } else if (quote.kind === "none-free") {
    line = "No GPU is free right now. We look again every 15 seconds.";
  } else if (quote.kind === "error") {
    line = problemMessage(quote.error);
  } else if (short) {
    line = (
      <>
        <Money paise={quote.quote.holdPaise} /> is held while it runs, and you have{" "}
        <Money paise={quote.quote.availablePaise} />. Lower the spending cap in Settings.
      </>
    );
  } else {
    line = (
      <>
        <Money paise={quote.quote.holdPaise} /> is held while it runs. You have{" "}
        <Money paise={quote.quote.availablePaise} />.
      </>
    );
  }

  return (
    <div className="flex flex-col gap-3">
      {error !== null && !settingsRefused(error) && <Banner tone="danger">{problemMessage(error)}</Banner>}
      <Button block large disabled={waiting} busy={busy} onClick={onRequest}>
        {uploadingPercent !== null ? (
          <span className="tabular">Waiting for your upload… {uploadingPercent}%</span>
        ) : (
          "Request a GPU"
        )}
      </Button>
      <div
        aria-live="polite"
        className={cn(
          "text-center tabular",
          laptop ? "min-h-5 text-small" : "min-h-4.5 text-caption",
          short ? "text-danger" : "text-text-muted",
        )}
      >
        {line}
      </div>
    </div>
  );
}
