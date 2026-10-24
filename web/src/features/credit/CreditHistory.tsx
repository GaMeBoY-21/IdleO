import { ChevronRight } from "lucide-react";
import type { ReactNode } from "react";
import { Link } from "wouter";
import { useHistory, useWallet, type HistoryEntry } from "@/api/queries";
import { Page } from "@/components/shell/AppShell";
import { RailColumns } from "@/components/shell/Layout";
import { Button } from "@/components/ui/Button";
import { SectionTitle } from "@/components/ui/Tile";
import { Money } from "@/components/ui/Money";
import { SkeletonLine } from "@/components/ui/Skeleton";
import { EmptyState, ErrorState, StaleNotice, staleSince } from "@/components/ui/States";
import { RelativeTime } from "@/components/ui/Time";
import { useNow } from "@/lib/clock";
import { dayHeading, dayOf } from "@/lib/time";
import { Tile } from "@/components/ui/Tile";
import { useBillingDetails, useInvoices, useRefundRequests } from "@/api/wallet";
import { useAfterPaint } from "@/lib/after-first-screen";
import { AddCredit } from "./AddCredit";
import { BillingDetailsTile } from "./BillingDetailsTile";
import { CreditCard } from "./CreditCard";
import { Documents } from "./Documents";
import { RefundTile } from "./RefundTile";
import { creditKind } from "./kinds";
import { cn } from "@/lib/cn";
import { useWide } from "@/lib/media";
import { useTitle } from "@/lib/title";

const ROW = "flex min-h-18 items-center gap-3 py-4";

/** The entries as days, in the order they came (newest first): a day's entries are consecutive. */
function byDay(entries: HistoryEntry[]): { day: string; first: string; entries: HistoryEntry[] }[] {
  const days: { day: string; first: string; entries: HistoryEntry[] }[] = [];
  for (const entry of entries) {
    const day = dayOf(entry.createdAt);
    const last = days.at(-1);
    if (last?.day === day) {
      last.entries.push(entry);
    } else {
      days.push({ day, first: entry.createdAt, entries: [entry] });
    }
  }
  return days;
}

/**
 * The renter's credit, big, the way to add more (ADR 0039), then every movement of it, newest first, a page at a time,
 * and the documents for it. It is a page load of its own (AppLink), with the policy that lets Razorpay's Checkout run.
 */
export function CreditHistory() {
  useTitle("Credit");
  const wallet = useWallet();
  const history = useHistory();
  const painted = useAfterPaint();
  const details = useBillingDetails(painted);
  const invoices = useInvoices(painted);
  const refunds = useRefundRequests(painted);
  const entries = history.data?.pages.flatMap((page) => page.entries);
  const since = staleSince(wallet, history);
  const now = useNow("half-minute");
  const wide = useWide();
  const Frame = wide ? Rail : Narrow;

  return (
    <Frame
      title="Credit"
      summary={
        <>
          <CreditCard wallet={wallet} wide={wide} />
          <AddCredit details={details} />
        </>
      }
      more={
        <>
          <BillingDetailsTile details={details} />
          <RefundTile wallet={wallet} refunds={refunds} />
        </>
      }
      documents={<Documents invoices={invoices} />}
    >
      {since !== null && <StaleNotice updatedAt={since} />}
      <section aria-labelledby="history-title" className="flex flex-col gap-1">
        <SectionTitle id="history-title">History</SectionTitle>
        {entries ? (
          <div className="flex flex-col gap-4">
            {entries.length === 0 ? (
              <EmptyState title="No credit yet">
                Credit you receive, and what your sessions are charged, will be listed here.
              </EmptyState>
            ) : (
              byDay(entries).map((group) => (
                <section
                  key={group.day}
                  aria-label={dayHeading(group.first, now)}
                  className="flex flex-col gap-2"
                >
                  <h3 className="px-1 text-small font-semibold text-text-muted">
                    {dayHeading(group.first, now)}
                  </h3>
                  <Tile as="div" className="px-4.5 py-0">
                    <ul className="flex flex-col divide-y divide-divider">
                      {group.entries.map((entry) => (
                        <HistoryRow key={entry.id} entry={entry} />
                      ))}
                    </ul>
                  </Tile>
                </section>
              ))
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
          <Tile
            as="div"
            className="flex flex-col divide-y divide-divider px-4.5 py-0"
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
          </Tile>
        )}
      </section>
    </Frame>
  );
}

interface FrameProps {
  title: string;
  summary: ReactNode;
  more: ReactNode;
  documents: ReactNode;
  children: ReactNode;
}

/** On a phone: the balance and Add credit first, then the documents and the history, then the rest. */
function Narrow({ title, summary, more, documents, children }: FrameProps) {
  return (
    <Page title={title}>
      {summary}
      {documents}
      {children}
      {more}
    </Page>
  );
}

/**
 * From 768 px: the balance in the left rail's dark tile, Add credit, billing details and refunds under it, and the
 * documents and history beside it (web/DESIGN.md "Laptop layout"). On a phone the balance is the first block of the
 * page, under its title.
 */
function Rail({ title, summary, more, documents, children }: FrameProps) {
  return (
    <RailColumns
      fit={false}
      left={
        <>
          {summary}
          {more}
        </>
      }
      right={
        <>
          <h1 className="sr-only">{title}</h1>
          {documents}
          {children}
        </>
      }
    />
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
        <Link href={`/session/?id=${entry.reference.id}`} className={cn(ROW, "hover:underline")}>
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
