import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { api, data } from "@/api/client";
import { ApiError } from "@/api/errors";
import type { components } from "@/api/schema.gen";
import type { CreateSession } from "./form";

export type Quote = components["schemas"]["QuoteResponse"];

/** How often the quote is asked again while Book is open: GPUs come and go. */
export const QUOTE_EVERY_MS = 15_000;
/** A change waits this long for the next keystroke before it's quoted. */
const SETTLE_MS = 300;

/** What the latest quote says: a price, no GPU free, an error, or nothing yet. */
export type QuoteState =
  | { kind: "loading" }
  | { kind: "quoted"; quote: Quote }
  | { kind: "none-free"; quote: Quote | null }
  | { kind: "error"; error: unknown };

function useSettled<T>(value: T): T {
  const [settled, setSettled] = useState(value);
  const key = JSON.stringify(value);
  useEffect(() => {
    const timer = setTimeout(() => setSettled(value), SETTLE_MS);
    return () => clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps -- the value, compared by what it holds
  }, [key]);
  return settled;
}

const noGpuFree = (error: unknown) => error instanceof ApiError && error.type === "/problems/no-gpu-free";

/**
 * The quote for the request as it stands (`POST /v1/session-quotes`, ADR 0021): the hold, the credit, the GPUs that
 * fit right now and their prices. Asked again every 15 seconds and after any change; the last answer stays on screen
 * while the next is on its way, so nothing flickers. `request` is null while the form can't be quoted yet, and
 * `enabled` holds the first one back until the screen has painted.
 */
export function useQuote(request: CreateSession | null, enabled: boolean): QuoteState {
  const settled = useSettled(request);
  const quote = useQuery({
    queryKey: ["quote", settled],
    queryFn: () => data(api.POST("/v1/session-quotes", { body: settled! })),
    enabled: enabled && settled !== null,
    placeholderData: keepPreviousData,
    staleTime: 0,
    gcTime: 60_000,
    refetchInterval: QUOTE_EVERY_MS,
    refetchOnWindowFocus: false,
    retry: (failures, error) => !(error instanceof ApiError && error.status < 500) && failures < 2,
  });
  // The newer of the last answer and the last error is what's true now.
  const failedLast = quote.isError && quote.errorUpdatedAt >= quote.dataUpdatedAt;
  if (failedLast) {
    return noGpuFree(quote.error)
      ? { kind: "none-free", quote: null }
      : { kind: "error", error: quote.error };
  }
  if (quote.data) {
    return quote.data.hostsFitNow === 0
      ? { kind: "none-free", quote: quote.data }
      : { kind: "quoted", quote: quote.data };
  }
  return { kind: "loading" };
}
