import { ChevronRight } from "lucide-react";
import { Link } from "wouter";
import { useHistory, useWallet, type HistoryEntry } from "@/api/queries";
import { Page } from "@/components/shell/AppShell";
import { Button } from "@/components/ui/Button";
import { SectionTitle } from "@/components/ui/Tile";
import { Money } from "@/components/ui/Money";
import { SkeletonLine } from "@/components/ui/Skeleton";
import { EmptyState, ErrorState, StaleNotice, staleSince } from "@/components/ui/States";
import { RelativeTime } from "@/components/ui/Time";
import { CreditCard } from "@/features/home/CreditCard";
import { creditKind } from "@/lib/labels";
import { cn } from "@/lib/cn";
import { useTitle } from "@/lib/title";

const ROW = "flex min-h-18 items-center gap-3 py-4";

/** The renter's credit, big, then every movement of it, newest first, a page at a time. */
export function CreditHistory() {
  useTitle("Credit");
  const wallet = useWallet();
  const history = useHistory();
  const entries = history.data?.pages.flatMap((page) => page.entries);
  const since = staleSince(wallet, history);

  return (
    <Page title="Credit">
      {since !== null && <StaleNotice updatedAt={since} />}
      <CreditCard wallet={wallet} />
      <section aria-labelledby="history-title" className="flex flex-col gap-1">
        <SectionTitle id="history-title">History</SectionTitle>
        {entries ? (
          <div className="flex flex-col gap-4">
            {entries.length === 0 ? (
              <EmptyState title="No credit yet">
                Credit you receive, and what your sessions are charged, will be listed here.
              </EmptyState>
            ) : (
              <ul className="flex flex-col divide-y divide-divider">
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
            className="flex flex-col divide-y divide-divider"
            role="status"
            aria-label="Loading your credit history"
          >
            {[0, 1, 2, 3].map((key) => (
              <div key={key} className={ROW}>
                <div className="flex flex-1 flex-col gap-0.5">
                  <SkeletonLine size="option" width="w-1/2" />
                  <SkeletonLine size="small" width="w-1/3" />
                </div>
                <SkeletonLine size="option" width="w-1/4" />
                <span aria-hidden="true" className="size-5 shrink-0" />
              </div>
            ))}
          </div>
        )}
      </section>
    </Page>
  );
}

/** One movement of credit; one that came from a session is, as a whole, the link to it. */
function HistoryRow({ entry }: { entry: HistoryEntry }) {
  const credit = entry.direction === "credit";
  const isSession = ["session", "session_refund", "dispute_refund"].includes(entry.reference.type);
  const content = (
    <>
      <div className="flex min-w-0 flex-1 flex-col gap-0.5">
        <p className="text-option font-semibold">{creditKind(entry.kind)}</p>
        <p className="text-small text-text-muted first-letter:uppercase">
          <RelativeTime value={entry.createdAt} />
        </p>
      </div>
      <p className={cn("text-option font-semibold tabular", credit && "text-running")}>
        <span className="sr-only">{credit ? "Added" : "Taken"} </span>
        <span aria-hidden="true">{credit ? "+" : "−"}</span>
        <Money paise={entry.amountPaise} />
      </p>
    </>
  );
  return (
    <li>
      {isSession ? (
        <Link href={`/session/?id=${entry.reference.id}`} className={cn(ROW, "hover:bg-fill")}>
          {content}
          <ChevronRight aria-hidden="true" className="size-5 shrink-0 text-text-muted" strokeWidth={1.75} />
        </Link>
      ) : (
        // The same width as a linked row's chevron, so amounts line up down the list.
        <div className={ROW}>
          {content}
          <span aria-hidden="true" className="size-5 shrink-0" />
        </div>
      )}
    </li>
  );
}
