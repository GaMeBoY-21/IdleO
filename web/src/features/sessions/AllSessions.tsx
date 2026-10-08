import { Link, useSearch } from "wouter";
import { useSessionPages, useTemplatesKnown } from "@/api/queries";
import { Button, buttonClasses } from "@/components/ui/Button";
import { Card } from "@/components/ui/Card";
import { EmptyState, ErrorState, StaleNotice, staleSince } from "@/components/ui/States";
import { cn } from "@/lib/cn";
import { useTitle } from "@/lib/title";
import { SessionList, SessionRowsSkeleton } from "./SessionRow";

/**
 * Every session, newest first (product surface 3.2): the active ones, or the ended ones, a page at a time. Reached
 * from Home and from a session's back link; it isn't a tab. The list polls while any session in it can change.
 */
export function AllSessions() {
  useTitle("All sessions");
  const ended = new URLSearchParams(useSearch()).get("show") === "ended";
  const pages = useSessionPages(!ended);
  const namesKnown = useTemplatesKnown();
  const sessions = pages.data?.pages.flatMap((page) => page.sessions);
  const since = staleSince(pages);

  return (
    <div className="flex flex-col gap-4">
      <h1 className="text-heading font-semibold">All sessions</h1>
      {since !== null && <StaleNotice updatedAt={since} />}
      <nav aria-label="Which sessions" className="flex gap-1 self-start rounded-control bg-sunken p-1">
        {[
          { label: "Active", href: "/sessions/", current: !ended },
          { label: "Ended", href: "/sessions/?show=ended", current: ended },
        ].map((tab) => (
          <Link
            key={tab.label}
            href={tab.href}
            aria-current={tab.current ? "page" : undefined}
            className={cn(
              "flex min-h-touch items-center rounded-control px-4 text-small font-medium",
              tab.current ? "bg-surface text-text" : "text-text-muted hover:text-text",
            )}
          >
            {tab.label}
          </Link>
        ))}
      </nav>
      <Card aria-label={ended ? "Ended sessions" : "Active sessions"}>
        {sessions && namesKnown ? (
          sessions.length === 0 ? (
            <EmptyState
              title={ended ? "No ended sessions" : "Nothing running"}
              action={
                <Link href="/book/" className={buttonClasses("primary")}>
                  Book a GPU
                </Link>
              }
            >
              {ended
                ? "Sessions that have ended are listed here, with what each one cost."
                : "Sessions that are starting or running are listed here, with their cost so far."}
            </EmptyState>
          ) : (
            <div className="flex flex-col gap-4">
              <SessionList sessions={sessions} />
              {pages.hasNextPage && (
                <Button
                  variant="secondary"
                  className="self-center"
                  busy={pages.isFetchingNextPage}
                  onClick={() => void pages.fetchNextPage()}
                >
                  Show more
                </Button>
              )}
              {pages.isFetchNextPageError && (
                <p className="text-small text-danger" role="alert">
                  We couldn&apos;t load more sessions. Try again.
                </p>
              )}
            </div>
          )
        ) : pages.isError ? (
          <ErrorState
            error={pages.error}
            what="your sessions"
            onRetry={() => void pages.refetch()}
            retrying={pages.isFetching}
          />
        ) : (
          <div role="status" aria-label="Loading your sessions">
            <SessionRowsSkeleton rows={5} />
          </div>
        )}
      </Card>
    </div>
  );
}
