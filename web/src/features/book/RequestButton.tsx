import type { ReactNode } from "react";
import { ApiError } from "@/api/errors";
import { Banner } from "@/components/ui/Banner";
import { Button } from "@/components/ui/Button";
import { Money } from "@/components/ui/Money";
import { SkeletonLine } from "@/components/ui/Skeleton";
import { cn } from "@/lib/cn";
import { problemMessage } from "@/lib/problems";
import { siteLink } from "@/lib/site-links";
import type { QuoteState } from "./useQuote";

const settingsRefused = (error: unknown) =>
  error instanceof ApiError && error.type === "/problems/validation-failed";

/**
 * "Request a GPU", the screen's one primary button, and the line under it: what's held while it runs and the credit
 * there is, both from the quote (docs/design/redesign/book-finetune-laptop.html). It waits, saying why, while an
 * upload is going ("Waiting for your upload… 64%"), while no GPU is free, when the credit is short (both figures),
 * and for good when no GPU here has the memory the model needs. With answers still to give it can be pressed, and
 * pressing points at them. Above the button, the two things a renter should know before sending anything (ADR 0038): a
 * host is a person at home, so no secret data, and the Terms say what isn't allowed (their words are the lawyer's, only
 * linked here). It sits above so the line under the button, which can wrap once the quote arrives, has nothing below it to
 * move.
 */
export function RequestButton({
  quote,
  uploadingPercent,
  tooBig,
  busy,
  error,
  onRequest,
  laptop,
}: {
  quote: QuoteState | null;
  uploadingPercent: number | null;
  tooBig: boolean;
  busy: boolean;
  error: unknown;
  onRequest: () => void;
  laptop: boolean;
}) {
  const quoted = quote?.kind === "quoted" ? quote.quote : null;
  const short = quoted !== null && !quoted.enoughCredit;
  const waiting =
    tooBig ||
    uploadingPercent !== null ||
    short ||
    quote?.kind === "loading" ||
    quote?.kind === "none-free" ||
    (quote?.kind === "error" && !settingsRefused(quote.error));

  let line: ReactNode;
  if (tooBig) {
    line = "No GPU here has that much memory, so this can't be requested.";
  } else if (quote === null || (quote.kind === "error" && settingsRefused(quote.error))) {
    line = "Answer the questions to see what's held while it runs.";
  } else if (quote.kind === "loading") {
    line = <SkeletonLine size={laptop ? "small" : "caption"} width="w-3/4" onDark />;
  } else if (quote.kind === "none-free") {
    line = "No GPU is free right now. We look again every 15 seconds.";
  } else if (quote.kind === "error") {
    line = problemMessage(quote.error);
  } else if (short) {
    line = (
      <>
        <Money paise={quote.quote.holdPaise} /> is held while it runs, and you have{" "}
        <Money paise={quote.quote.availablePaise} />. Choose a lower budget.
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
      <p className="text-center text-caption text-dark-muted">
        Hosts are people at home, so don&apos;t send secret data. What isn&apos;t allowed is in{" "}
        <a href={siteLink("/terms/")} target="_blank" rel="noreferrer" className="underline">
          the Terms
        </a>
        .
      </p>
      <Button variant="live" block large disabled={waiting} busy={busy} onClick={onRequest}>
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
          short || tooBig ? "text-danger-on-dark" : "text-dark-muted",
        )}
      >
        {line}
      </div>
    </div>
  );
}
