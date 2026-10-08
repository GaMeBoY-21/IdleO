import type { UseQueryResult } from "@tanstack/react-query";
import { ChevronRight } from "lucide-react";
import { Link } from "wouter";
import { isLive, useTemplateName, type Session } from "@/api/queries";
import { buttonClasses } from "@/components/ui/Button";
import { Card, CardTitle } from "@/components/ui/Card";
import { EmptyState, ErrorState } from "@/components/ui/States";
import { SessionList, SessionRowsSkeleton } from "@/features/sessions/SessionRow";
import { recentTemplates } from "@/lib/sessions";
import { formatTime } from "@/lib/time";

type Sessions = UseQueryResult<{ sessions: Session[] }>;

/** How many ended sessions Home lists; the rest are on All sessions. */
export const RECENT_COUNT = 5;

/**
 * The sessions running now, first on Home, with their cost so far and elapsed time. "Live" says when they were last
 * refreshed, and is never shown while the figures are stale.
 */
export function RunningCard({
  sessions,
  updatedAt,
  stale,
}: {
  sessions: Session[];
  updatedAt: number;
  stale: boolean;
}) {
  return (
    <Card aria-labelledby="live-title">
      <CardTitle
        id="live-title"
        action={
          !stale && (
            <p className="text-caption text-text-muted" aria-live="polite">
              Live · updated <span className="tabular">{formatTime(updatedAt)}</span>
            </p>
          )
        }
      >
        Running now
      </CardTitle>
      <SessionList sessions={sessions} />
    </Card>
  );
}

/** The sessions that have ended, newest first, with their final charge; and Home's one next step, booking. */
export function RecentCard({ sessions }: { sessions: Sessions }) {
  const list = sessions.data?.sessions;
  const recent = list?.filter((session) => !isLive(session)) ?? [];
  const book = (
    <Link href="/book/" className={buttonClasses("primary")}>
      Book a GPU
    </Link>
  );

  return (
    <Card aria-labelledby="recent-title">
      <CardTitle id="recent-title" action={list && list.length > 0 ? book : undefined}>
        Recent sessions
      </CardTitle>
      {list ? (
        list.length === 0 ? (
          <EmptyState title="No sessions yet" action={book}>
            Book a GPU to run your first session. You&apos;ll see it here while it runs, with what it costs.
          </EmptyState>
        ) : recent.length === 0 ? (
          <EmptyState title="Nothing has ended yet">
            Sessions that have ended are listed here, with what each one cost.
          </EmptyState>
        ) : (
          <SessionList sessions={recent.slice(0, RECENT_COUNT)} />
        )
      ) : sessions.isError ? (
        <ErrorState
          error={sessions.error}
          what="your sessions"
          onRetry={() => void sessions.refetch()}
          retrying={sessions.isFetching}
        />
      ) : (
        <div role="status" aria-label="Loading your sessions">
          <SessionRowsSkeleton />
        </div>
      )}
    </Card>
  );
}

/** The templates the renter booked last, a tap from booking them again. */
export function BookAgainCard({ sessions }: { sessions: Session[] }) {
  const templateName = useTemplateName();
  const templates = recentTemplates(sessions, 3);
  if (templates.length === 0) {
    return null;
  }
  return (
    <Card aria-labelledby="again-title">
      <CardTitle id="again-title">Book again</CardTitle>
      <ul className="divide-y divide-border rounded-control border border-border">
        {templates.map((id) => (
          <li key={id}>
            <Link
              href={`/book/?template=${encodeURIComponent(id)}&step=configure`}
              className="flex min-h-touch items-center gap-3 px-3 py-2 text-small hover:bg-sunken"
            >
              <span className="min-w-0 flex-1 font-medium break-words">{templateName(id)}</span>
              <ChevronRight
                aria-hidden="true"
                className="size-5 shrink-0 text-text-muted"
                strokeWidth={1.75}
              />
            </Link>
          </li>
        ))}
      </ul>
    </Card>
  );
}
