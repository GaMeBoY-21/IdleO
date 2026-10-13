import { ChevronLeft, ChevronRight } from "lucide-react";
import { Link } from "wouter";
import { useDisputePages, type Dispute } from "@/api/trust";
import { Page } from "@/components/shell/AppShell";
import { Button } from "@/components/ui/Button";
import { SectionTitle } from "@/components/ui/Card";
import { SkeletonLine } from "@/components/ui/Skeleton";
import { EmptyState, ErrorState, StaleNotice, staleSince } from "@/components/ui/States";
import { RelativeTime } from "@/components/ui/Time";
import { useAfterPaint } from "@/lib/after-first-screen";
import { cn } from "@/lib/cn";
import { reasonTitle, reportWords, REPLY_PROMISE } from "@/lib/reports";
import { useTitle } from "@/lib/title";

const ROW = "flex min-h-18 items-start gap-3 py-4";

/**
 * Account → Reports (ADR 0028): every problem the person has reported, newest first, and where each stands: open (we
 * reply within 2 days), or resolved, with our reason and any refund, which is also in their credit. A row opens its
 * session. Only their own words and ours; never anything about a host.
 */
export function Reports() {
  useTitle("Reports");
  // The list isn't what the screen paints first, so it's asked for once it has painted (ADR 0018).
  const painted = useAfterPaint();
  const reports = useDisputePages(painted);
  const since = staleSince(reports);
  const rows = reports.data?.pages.flatMap((page) => page.disputes);

  return (
    <Page title="Reports">
      <Link
        href="/account/"
        className="-mt-4 flex w-fit items-center gap-1 text-small text-text-muted underline"
      >
        <ChevronLeft aria-hidden="true" className="size-4" strokeWidth={1.75} />
        Account
      </Link>
      {since !== null && <StaleNotice updatedAt={since} />}
      <p className="max-w-prose text-small text-text-muted">
        A person reads every report. {REPLY_PROMISE} A refund goes straight to your credit.
      </p>
      <section aria-labelledby="reports-title" className="flex flex-col gap-1">
        <SectionTitle id="reports-title">Your reports</SectionTitle>
        {rows ? (
          <div className="flex flex-col gap-4">
            {rows.length === 0 ? (
              <EmptyState title="No reports">
                If a session goes wrong, open it and choose Report a problem. It will be listed here.
              </EmptyState>
            ) : (
              <ul className="flex flex-col divide-y divide-divider">
                {rows.map((dispute) => (
                  <ReportItem key={dispute.id} dispute={dispute} />
                ))}
              </ul>
            )}
            {reports.hasNextPage && (
              <Button
                variant="secondary"
                className="self-center"
                busy={reports.isFetchingNextPage}
                onClick={() => void reports.fetchNextPage()}
              >
                Show older
              </Button>
            )}
            {reports.isFetchNextPageError && (
              <p className="text-small text-danger" role="alert">
                We couldn&apos;t load older reports. Try again.
              </p>
            )}
          </div>
        ) : reports.isError ? (
          <ErrorState
            error={reports.error}
            what="your reports"
            onRetry={() => void reports.refetch()}
            retrying={reports.isFetching}
          />
        ) : (
          <div
            className="flex flex-col divide-y divide-divider"
            role="status"
            aria-label="Loading your reports"
          >
            {[0, 1, 2].map((key) => (
              <div key={key} className={ROW}>
                <div className="flex flex-1 flex-col gap-0.5">
                  <SkeletonLine size="option" width="w-1/2" />
                  <SkeletonLine size="small" width="w-2/3" />
                  <SkeletonLine size="small" width="w-1/3" />
                </div>
                <span aria-hidden="true" className="size-5 shrink-0" />
              </div>
            ))}
          </div>
        )}
      </section>
    </Page>
  );
}

/** One report: why it was sent and when, where it stands, and our reason. As a whole, the link to its session. */
function ReportItem({ dispute }: { dispute: Dispute }) {
  const words = reportWords(dispute);
  return (
    <li>
      <Link href={`/session/?id=${dispute.sessionId}`} className={cn(ROW, "hover:bg-fill")}>
        <div className="flex min-w-0 flex-1 flex-col gap-0.5">
          <p className="text-option font-semibold break-words">{reasonTitle(dispute.reason)}</p>
          <p className="text-small text-text-muted first-letter:uppercase">
            <RelativeTime value={dispute.createdAt} prefix="sent" />
          </p>
          <p className={cn("text-small font-semibold", words.resolved ? "text-text" : "text-warning")}>
            {words.headline}
          </p>
          {words.resolved && words.line !== "" && (
            <p className="text-small break-words text-text-muted">{words.line}</p>
          )}
        </div>
        <ChevronRight
          aria-hidden="true"
          className="mt-1 size-5 shrink-0 text-text-muted"
          strokeWidth={1.75}
        />
      </Link>
    </li>
  );
}
