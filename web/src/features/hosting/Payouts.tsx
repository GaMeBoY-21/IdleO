import { useState } from "react";
import { Link } from "wouter";
import {
  downloadStatement,
  usePayoutHistory,
  usePayoutSummary,
  type PayoutSummary,
  type PayoutView,
} from "@/api/payouts";
import { Button } from "@/components/ui/Button";
import { Money } from "@/components/ui/Money";
import { SkeletonLine } from "@/components/ui/Skeleton";
import { ErrorState, EmptyState } from "@/components/ui/States";
import { SectionTitle, Tile } from "@/components/ui/Tile";
import { useNow } from "@/lib/clock";
import { cn } from "@/lib/cn";
import { blockedWords, payoutStatus } from "@/lib/payouts";
import { formatDateTime, formatDay } from "@/lib/time";

/**
 * A host's payouts on Earnings (ADR 0040): what's pending (each session with the day it becomes available), what's
 * available, when the next payout is, the tax deducted this year, every payout and its status, and the year's
 * statement. Every figure is the server's; asked for after the screen's first paint (`enabled`).
 */
export function PayoutsTile({ enabled }: { enabled: boolean }) {
  const summary = usePayoutSummary(enabled);
  if (summary.data) {
    return <Summary summary={summary.data} />;
  }
  if (summary.isError) {
    return (
      <ErrorState
        error={summary.error}
        what="your payouts"
        onRetry={() => void summary.refetch()}
        retrying={summary.isFetching}
      />
    );
  }
  return <SummarySkeleton />;
}

const FIGURE = "flex min-h-18 flex-col justify-center gap-0.5 rounded-control bg-ground px-4 py-3.5";

export function Summary({ summary }: { summary: PayoutSummary }) {
  const blocked = blockedWords(summary.blockedBy);
  const nextAvailable = summary.pending.find((session) => session.availableAt != null)?.availableAt;
  return (
    <Tile as="section" aria-labelledby="payouts-title" className="flex flex-col gap-4 xl:p-7">
      <div className="flex items-baseline justify-between gap-3">
        <SectionTitle id="payouts-title">Payouts</SectionTitle>
        <Link href="/hosting/payout-details/" className="text-small font-semibold underline">
          Payout details
        </Link>
      </div>
      <dl className="grid grid-cols-2 gap-2">
        <div className={FIGURE}>
          <dt className="text-small text-text-muted">Available</dt>
          <dd className="text-option font-semibold">
            <Money paise={summary.availablePaise} />
          </dd>
        </div>
        <div className={FIGURE}>
          <dt className="text-small text-text-muted">Pending</dt>
          <dd className="text-option font-semibold">
            <Money paise={summary.pendingPaise} />
          </dd>
        </div>
        <div className={FIGURE}>
          <dt className="text-small text-text-muted">Next payout</dt>
          <dd className="text-option font-semibold">{formatDay(summary.nextPayoutAt)}</dd>
        </div>
        <div className={FIGURE}>
          <dt className="text-small text-text-muted">Tax deducted {summary.financialYear}</dt>
          <dd className="text-option font-semibold">
            <Money paise={summary.yearTdsPaise} />
          </dd>
        </div>
      </dl>
      {/* Two lines kept for what's said here, whichever it is, so nothing below moves. */}
      <div className="flex min-h-9 flex-col gap-0.5 text-small">
        {blocked ? (
          <p className="font-semibold text-warning">
            {blocked}{" "}
            <Link href="/hosting/payout-details/" className="underline">
              Payout details
            </Link>
          </p>
        ) : summary.inFlightPaise > 0 ? (
          <p>
            <Money paise={summary.inFlightPaise} /> is on its way to your UPI ID.
          </p>
        ) : (
          <p className="text-text-muted">
            Paid every Monday from <Money paise={summary.minimumPaise} /> available. Earnings wait 7 days
            after a session, for any report about it.
          </p>
        )}
        {summary.owedPaise > 0 && (
          <p className="text-text-muted">
            <Money paise={summary.owedPaise} /> refunded to renters after it was paid out comes out of your
            next earnings.
          </p>
        )}
      </div>
      {summary.pending.length === 0 ? (
        <p className="min-h-touch content-center text-small text-text-muted">Nothing pending.</p>
      ) : (
        <details className="text-small">
          <summary className="min-h-touch cursor-pointer content-center font-semibold">
            {summary.pending.length === 1
              ? "1 session pending"
              : `${summary.pending.length} sessions pending`}
            {nextAvailable && <> · the next on {formatDay(nextAvailable)}</>}
          </summary>
          <ul className="flex flex-col divide-y divide-divider">
            {summary.pending.map((session) => (
              <li key={`${session.hostId}${session.sessionId}`} className="flex items-center gap-3 py-2">
                <span className="min-w-0 flex-1 break-words">
                  {session.machineName}
                  <span className="block text-caption text-text-muted">
                    {session.availableAt
                      ? `Available ${formatDay(session.availableAt)}`
                      : "Held while a report about it is open"}
                  </span>
                </span>
                <Money paise={session.amountPaise} className="font-semibold" />
              </li>
            ))}
          </ul>
        </details>
      )}
    </Tile>
  );
}

export function SummarySkeleton() {
  // The loaded tile's own structure, its figures waiting: the same title row, labels and lines, so the same height.
  return (
    <Tile className="flex flex-col gap-4 xl:p-7" role="status" aria-label="Loading your payouts">
      <div className="flex items-baseline justify-between gap-3">
        <SectionTitle>Payouts</SectionTitle>
      </div>
      <dl className="grid grid-cols-2 gap-2">
        {["Available", "Pending", "Next payout", "Tax deducted"].map((label) => (
          <div key={label} className={FIGURE}>
            <dt className="text-small text-text-muted">{label}</dt>
            <dd className="text-option font-semibold">
              <SkeletonLine size="option" width="w-2/3" />
            </dd>
          </div>
        ))}
      </dl>
      <div className="flex min-h-9 flex-col gap-0.5 text-small">
        <SkeletonLine size="small" width="w-2/3" />
      </div>
      <div className="flex min-h-touch items-center">
        <SkeletonLine size="small" width="w-1/3" />
      </div>
    </Tile>
  );
}

/** Every payout, newest first, with its status and, for one that came back, why. */
export function PayoutHistory({ enabled }: { enabled: boolean }) {
  const history = usePayoutHistory(enabled);
  const payouts = history.data?.pages.flatMap((page) => page.payouts);
  return (
    <section aria-labelledby="payout-history-title" className="flex flex-col gap-1">
      <SectionTitle id="payout-history-title">Payout history</SectionTitle>
      {payouts ? (
        payouts.length === 0 ? (
          <EmptyState title="No payouts yet">
            Your first payout goes out on a Monday once your earnings are available and your UPI ID is
            checked.
          </EmptyState>
        ) : (
          <div className="flex flex-col gap-4">
            <Tile as="div" className="px-4.5 py-0">
              <ul className="flex flex-col divide-y divide-divider">
                {payouts.map((payout) => (
                  <PayoutRow key={payout.id} payout={payout} />
                ))}
              </ul>
            </Tile>
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
          </div>
        )
      ) : history.isError ? (
        <ErrorState
          error={history.error}
          what="your payouts"
          onRetry={() => void history.refetch()}
          retrying={history.isFetching}
        />
      ) : (
        <Tile as="div" aria-hidden="true" className="flex flex-col divide-y divide-divider px-4.5 py-0">
          {[0, 1].map((key) => (
            <div key={key} className="flex min-h-18 items-center gap-3 py-4">
              <div className="flex flex-1 flex-col gap-0.5">
                <SkeletonLine size="option" width="w-1/2" />
                <SkeletonLine size="small" width="w-1/3" />
              </div>
              <SkeletonLine size="option" width="w-1/4" />
            </div>
          ))}
        </Tile>
      )}
    </section>
  );
}

export function PayoutRow({ payout }: { payout: PayoutView }) {
  const status = payoutStatus(payout.status);
  return (
    <li className="flex min-h-18 items-center gap-3 py-4">
      <div className="flex min-w-0 flex-1 flex-col gap-0.5">
        <p className="text-option font-semibold">
          {formatDay(payout.createdAt)}
          {payout.kind === "final" && " · final"}
        </p>
        <p
          className={cn(
            "text-small",
            status.tone === "danger"
              ? "text-danger"
              : status.tone === "running"
                ? "text-running"
                : "text-text-muted",
          )}
        >
          {status.label}
          {payout.processedAt && payout.status === "processed" && ` ${formatDateTime(payout.processedAt)}`}
        </p>
        {payout.why && <p className="text-small text-text-muted">{payout.why}</p>}
        {payout.tdsPaise > 0 && (
          <p className="text-caption text-text-muted">
            <Money paise={payout.grossPaise} /> less <Money paise={payout.tdsPaise} /> tax (section{" "}
            {payout.tdsSection})
          </p>
        )}
      </div>
      <p className="text-option font-semibold tabular">
        <Money paise={payout.netPaise} />
      </p>
    </li>
  );
}

/**
 * The year's statement: gross paid out, tax deducted, net paid, as PDF or CSV. Drawn whole before the years arrive,
 * its controls waiting, so nothing moves when they do.
 */
export function Statement({ years }: { years: string[] | null }) {
  const [chosen, setChosen] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);
  // Until the years arrive, this financial year (the first the server lists), so the menu is as wide as it will be.
  const now = useNow("half-minute");
  const year = chosen ?? years?.[0] ?? financialYear(now);
  const download = (format: "pdf" | "csv") => {
    setFailed(false);
    downloadStatement(year, format).catch(() => setFailed(true));
  };
  return (
    <Tile
      as="section"
      aria-labelledby="statement-title"
      aria-busy={years === null}
      className="flex flex-col gap-3"
    >
      <SectionTitle id="statement-title">Yearly statement</SectionTitle>
      <p className="text-small text-text-muted">
        What you were paid in a financial year (April to March), the tax deducted and the net: for your tax
        return.
      </p>
      <div className="flex flex-wrap items-end gap-3">
        <label className="flex flex-col gap-2 text-small font-medium">
          Year
          <select
            className="min-h-touch rounded-control bg-ground px-3 text-body"
            value={year}
            disabled={years === null}
            onChange={(event) => setChosen(event.target.value)}
          >
            {(years ?? [year]).map((option) => (
              <option key={option} value={option}>
                {option}
              </option>
            ))}
          </select>
        </label>
        <Button variant="secondary" disabled={years === null} onClick={() => download("pdf")}>
          PDF
        </Button>
        <Button variant="secondary" disabled={years === null} onClick={() => download("csv")}>
          CSV
        </Button>
      </div>
      <p role="alert" className="min-h-4.5 text-caption text-danger">
        {failed ? "We couldn't make the statement. Try again." : ""}
      </p>
    </Tile>
  );
}

/** India's financial year of a moment, `2026-27` from 1 April 2026 to 31 March 2027, as the server names it. */
export function financialYear(at: number): string {
  const india = new Date(at + 5.5 * 3_600_000);
  const start = india.getUTCMonth() >= 3 ? india.getUTCFullYear() : india.getUTCFullYear() - 1;
  return `${start}-${String((start + 1) % 100).padStart(2, "0")}`;
}

/** The statement, with the years the summary names once it has arrived. */
export function StatementTile({ enabled }: { enabled: boolean }) {
  const summary = usePayoutSummary(enabled);
  return <Statement years={summary.data?.statementYears ?? null} />;
}
