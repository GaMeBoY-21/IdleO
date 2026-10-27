import { ChevronRight, Flag } from "lucide-react";
import { lazy, Suspense, useEffect, useState } from "react";
import { Link } from "wouter";
import { useSessionDisputes } from "@/api/trust";
import { SkeletonLine } from "@/components/ui/Skeleton";
import { RelativeTime } from "@/components/ui/Time";
import { afterFirstScreen } from "@/lib/after-first-screen";
import { REPLY_PROMISE, reportWords } from "@/lib/reports";

// The sheet (Radix) isn't needed to show the session: it loads after the first screen, or when the row is pressed.
const loadSheet = () => import("./ReportSheet");
const ReportSheet = lazy(() => loadSheet().then((module) => ({ default: module.ReportSheet })));

const ROW =
  "tile-motion flex min-h-18 w-full items-center gap-3.5 rounded-panel bg-panel px-4.5 py-3.5 text-left";

/**
 * "Report a problem" on an ended session, and where the latest report stands (ADR 0028): one row that's always two
 * lines, so the report arriving moves nothing. With none open it opens the report sheet; with a report, open or
 * resolved, it says what we decided and links to Account's Reports, where every report is.
 */
export function ReportRow({ sessionId, enabled }: { sessionId: string; enabled: boolean }) {
  const reports = useSessionDisputes(sessionId, enabled);
  const [asking, setAsking] = useState(false);
  useEffect(() => afterFirstScreen(() => void loadSheet()), []);
  const latest = reports.data?.disputes[0];
  const words = latest ? reportWords(latest) : null;

  return (
    <>
      {latest && words ? (
        <Link href="/account/reports/" className={ROW}>
          <Flag aria-hidden="true" className="size-5 shrink-0" strokeWidth={1.75} />
          <span className="flex min-w-0 flex-1 flex-col gap-0.5">
            <span className="text-body font-semibold">{words.headline}</span>
            <span className="truncate text-small text-text-2">
              {words.resolved ? words.line : <ReportSent at={latest.createdAt} />}
            </span>
          </span>
          <ChevronRight aria-hidden="true" className="size-5 shrink-0" strokeWidth={2} />
        </Link>
      ) : (
        <button type="button" className={ROW} disabled={reports.isPending} onClick={() => setAsking(true)}>
          <Flag aria-hidden="true" className="size-5 shrink-0" strokeWidth={1.75} />
          <span className="flex min-w-0 flex-1 flex-col gap-0.5">
            <span className="text-body font-semibold">Report a problem</span>
            {reports.isPending ? (
              <SkeletonLine size="small" width="w-2/3" />
            ) : (
              <span className="truncate text-small text-text-2">
                {reports.isError
                  ? "We couldn't check your reports. Press to report."
                  : "Something wrong with this session?"}
              </span>
            )}
          </span>
          <ChevronRight aria-hidden="true" className="size-5 shrink-0" strokeWidth={2} />
        </button>
      )}
      {/* Outside the row, so the confirmation stays when the row turns into the report's status behind it. */}
      {asking && (
        <Suspense fallback={null}>
          <ReportSheet sessionId={sessionId} open={asking} onOpenChange={setAsking} />
        </Suspense>
      )}
    </>
  );
}

function ReportSent({ at }: { at: string }) {
  return (
    <>
      Sent <RelativeTime value={at} /> · {REPLY_PROMISE}
    </>
  );
}
