import type { UseQueryResult } from "@tanstack/react-query";
import { ChevronRight } from "lucide-react";
import { Link } from "wouter";
import { isLive, useTemplateName, type Session } from "@/api/queries";
import { buttonClasses } from "@/components/ui/Button";
import { Card, CardTitle } from "@/components/ui/Card";
import { Money } from "@/components/ui/Money";
import { Skeleton } from "@/components/ui/Skeleton";
import { StateChip } from "@/components/ui/StateChip";
import { EmptyState, ErrorState, StaleNotice } from "@/components/ui/States";
import { formatDateTime, formatTime } from "@/lib/time";

export const costSoFar = (session: Session) => session.chargedPaise + session.accruedPaise;

/** The renter's sessions: live ones first, polled while any is live, then recent ones with their final charge. */
export function SessionsCard({ sessions }: { sessions: UseQueryResult<{ sessions: Session[] }> }) {
  const list = sessions.data?.sessions;
  const live = list?.filter(isLive) ?? [];
  const recent = list?.filter((session) => !isLive(session)) ?? [];

  return (
    <Card aria-labelledby="sessions-title">
      <CardTitle
        id="sessions-title"
        action={
          <Link href="/book/" className={buttonClasses("primary")}>
            Book a GPU
          </Link>
        }
      >
        Sessions
      </CardTitle>
      {list ? (
        <div className="flex flex-col gap-6">
          {sessions.isRefetchError && <StaleNotice updatedAt={sessions.dataUpdatedAt} />}
          {list.length === 0 && (
            <EmptyState title="No sessions yet">
              Book a GPU to run your first session. You&apos;ll see it here while it runs, with what it costs.
            </EmptyState>
          )}
          {live.length > 0 && (
            <section aria-labelledby="live-title">
              <div className="mb-2 flex items-baseline justify-between gap-4">
                <h3 id="live-title" className="text-small font-medium">
                  Running now
                </h3>
                <p className="text-caption text-text-muted" aria-live="polite">
                  Live · updated <span className="tabular">{formatTime(sessions.dataUpdatedAt)}</span>
                </p>
              </div>
              <SessionRows sessions={live} live />
            </section>
          )}
          {recent.length > 0 && (
            <section aria-labelledby="recent-title">
              <h3 id="recent-title" className="mb-2 text-small font-medium">
                Recent
              </h3>
              <SessionRows sessions={recent} live={false} />
            </section>
          )}
        </div>
      ) : sessions.isError ? (
        <ErrorState
          error={sessions.error}
          what="your sessions"
          onRetry={() => void sessions.refetch()}
          retrying={sessions.isFetching}
        />
      ) : (
        <SessionsSkeleton />
      )}
    </Card>
  );
}

function SessionRows({ sessions, live }: { sessions: Session[]; live: boolean }) {
  const templateName = useTemplateName();
  return (
    <ul className="divide-y divide-border rounded-control border border-border">
      {sessions.map((session) => (
        <li key={session.id}>
          <Link
            href={`/session/?id=${session.id}`}
            className="flex min-h-16 items-center gap-3 px-3 py-3 hover:bg-sunken md:px-4"
          >
            <div className="min-w-0 flex-1">
              <p className="truncate text-body font-medium">{templateName(session.templateId)}</p>
              <p className="text-caption text-text-muted">
                {session.startedAt
                  ? `Started ${formatDateTime(session.startedAt)}`
                  : `Booked ${formatDateTime(session.createdAt)}`}
              </p>
            </div>
            <div className="flex flex-col items-end gap-1">
              <StateChip state={session.state} />
              <p className="text-small">
                <span className="sr-only">{live ? "Cost so far" : "Charged"} </span>
                <Money paise={live ? costSoFar(session) : session.chargedPaise} className="font-semibold" />
                {live && <span className="text-text-muted"> so far</span>}
              </p>
            </div>
            <ChevronRight aria-hidden="true" className="size-5 shrink-0 text-text-muted" strokeWidth={1.75} />
          </Link>
        </li>
      ))}
    </ul>
  );
}

function SessionsSkeleton() {
  return (
    <div role="status" aria-label="Loading your sessions">
      <Skeleton className="mb-2 h-5 w-1/4" />
      <div className="divide-y divide-border rounded-control border border-border">
        {[0, 1, 2].map((key) => (
          <div key={key} className="flex min-h-16 items-center gap-3 px-3 py-3 md:px-4">
            <div className="flex-1">
              <Skeleton className="h-6 w-1/2" />
              <Skeleton className="mt-1 h-4 w-2/3" />
            </div>
            <Skeleton className="h-12 w-1/4" />
          </div>
        ))}
      </div>
    </div>
  );
}
