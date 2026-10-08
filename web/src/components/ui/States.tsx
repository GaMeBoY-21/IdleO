import { RefreshCw } from "lucide-react";
import type { ReactNode } from "react";
import { ApiError } from "@/api/errors";
import { formatTime } from "@/lib/time";
import { Banner } from "./Banner";
import { Button } from "./Button";

/** The empty state: why there's nothing, and the one thing to do about it (web/DESIGN.md "States"). */
export function EmptyState({
  title,
  children,
  action,
}: {
  title: string;
  children: ReactNode;
  action?: ReactNode;
}) {
  return (
    <div className="flex flex-col items-start gap-3 py-2">
      <p className="text-body font-medium">{title}</p>
      <p className="max-w-prose text-small text-text-muted">{children}</p>
      {action}
    </div>
  );
}

/** What went wrong, in a sentence, and a way to try again. Never a raw status code. */
export function errorSentence(error: unknown, what: string): string {
  if (error instanceof ApiError && error.status === 404) {
    return `We couldn't find ${what}. It may have been removed, or the link is wrong.`;
  }
  if (error instanceof ApiError && error.status >= 500) {
    return `We couldn't load ${what} because of a problem on our side. Try again in a moment.`;
  }
  return `We couldn't load ${what}. Check your connection and try again.`;
}

export function ErrorState({
  error,
  what,
  onRetry,
  retrying,
}: {
  error: unknown;
  what: string;
  onRetry: () => void;
  retrying?: boolean;
}) {
  return (
    <div className="flex flex-col items-start gap-3 py-2">
      <p className="max-w-prose text-small">{errorSentence(error, what)}</p>
      <Button variant="secondary" onClick={onRetry} busy={retrying ?? false}>
        {!retrying && <RefreshCw aria-hidden="true" className="size-4" strokeWidth={1.75} />}
        Try again
      </Button>
    </div>
  );
}

/**
 * When a screen's figures went stale: the oldest of its queries whose last refresh failed, or null while every one
 * is current. A screen shows one stale notice, at its top, from this (web/DESIGN.md "States").
 */
export function staleSince(
  ...queries: { data?: unknown; isRefetchError: boolean; dataUpdatedAt: number }[]
): number | null {
  const stale = queries.filter((query) => query.data !== undefined && query.isRefetchError);
  return stale.length === 0 ? null : Math.min(...stale.map((query) => query.dataUpdatedAt));
}

/** The partial state: the figures shown are from an earlier refresh, and the app is retrying. */
export function StaleNotice({ updatedAt }: { updatedAt: number }) {
  return (
    <Banner tone="warning">
      Couldn&apos;t refresh. Showing figures from <span className="tabular">{formatTime(updatedAt)}</span>.
      Retrying…
    </Banner>
  );
}
