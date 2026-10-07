import { Link } from "wouter";
import { useHistory, useWallet, type HistoryEntry } from "@/api/queries";
import { Button } from "@/components/ui/Button";
import { Card, CardTitle } from "@/components/ui/Card";
import { Money } from "@/components/ui/Money";
import { Skeleton } from "@/components/ui/Skeleton";
import { EmptyState, ErrorState, StaleNotice } from "@/components/ui/States";
import { CreditCard } from "@/features/home/CreditCard";
import { creditKind } from "@/lib/labels";
import { formatDateTime } from "@/lib/time";
import { cn } from "@/lib/cn";
import { useTitle } from "@/lib/title";

/** Every movement of the renter's credit, newest first, a page at a time. */
export function CreditHistory() {
  useTitle("Credit");
  const wallet = useWallet();
  const history = useHistory();
  const entries = history.data?.pages.flatMap((page) => page.entries);

  return (
    <div className="flex flex-col gap-4">
      <h1 className="text-heading font-semibold">Credit</h1>
      <div className="grid gap-4 xl:grid-cols-12">
        <div className="min-w-0 xl:col-span-4">
          <CreditCard wallet={wallet} />
        </div>
        <div className="min-w-0 xl:col-span-8">
          <Card aria-labelledby="history-title">
            <CardTitle id="history-title">History</CardTitle>
            {entries ? (
              <div className="flex flex-col gap-4">
                {history.isRefetchError && <StaleNotice updatedAt={history.dataUpdatedAt} />}
                {entries.length === 0 ? (
                  <EmptyState title="No credit yet">
                    Credit you receive, and what your sessions are charged, will be listed here.
                  </EmptyState>
                ) : (
                  <ul className="divide-y divide-border rounded-control border border-border">
                    {entries.map((entry) => (
                      <HistoryRow key={entry.id} entry={entry} />
                    ))}
                  </ul>
                )}
                {history.hasNextPage && (
                  <Button
                    variant="secondary"
                    className="self-center"
                    busy={history.isFetchingNextPage}
                    onClick={() => void history.fetchNextPage()}
                  >
                    Show older
                  </Button>
                )}
                {history.isFetchNextPageError && (
                  <p className="text-small text-danger" role="alert">
                    We couldn&apos;t load older entries. Try again.
                  </p>
                )}
              </div>
            ) : history.isError ? (
              <ErrorState
                error={history.error}
                what="your credit history"
                onRetry={() => void history.refetch()}
                retrying={history.isFetching}
              />
            ) : (
              <div
                className="divide-y divide-border rounded-control border border-border"
                role="status"
                aria-label="Loading your credit history"
              >
                {[0, 1, 2, 3].map((key) => (
                  <div key={key} className="flex min-h-16 items-center gap-3 px-3 py-3 md:px-4">
                    <div className="flex-1">
                      <Skeleton className="h-6 w-1/2" />
                      <Skeleton className="mt-1 h-4 w-1/3" />
                    </div>
                    <Skeleton className="h-6 w-1/4" />
                  </div>
                ))}
              </div>
            )}
          </Card>
        </div>
      </div>
    </div>
  );
}

function HistoryRow({ entry }: { entry: HistoryEntry }) {
  const credit = entry.direction === "credit";
  const isSession = entry.reference.type === "session" || entry.reference.type === "session_refund";
  return (
    <li className="flex min-h-16 items-center gap-3 px-3 py-3 md:px-4">
      <div className="min-w-0 flex-1">
        <p className="text-body font-medium">{creditKind(entry.kind)}</p>
        <p className="text-caption text-text-muted">
          <time dateTime={entry.createdAt}>{formatDateTime(entry.createdAt)}</time>
          {isSession && (
            <>
              {" · "}
              <Link
                href={`/session/?id=${entry.reference.id}`}
                className="text-accent-text underline underline-offset-2"
              >
                View session
              </Link>
            </>
          )}
        </p>
      </div>
      <p className={cn("text-body font-semibold", credit && "text-success")}>
        <span className="sr-only">{credit ? "Added" : "Taken"} </span>
        <span aria-hidden="true">{credit ? "+" : "−"}</span>
        <Money paise={entry.amountPaise} />
      </p>
    </li>
  );
}
