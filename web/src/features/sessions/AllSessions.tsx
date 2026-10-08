import { Link, useSearch } from "wouter";
import { useSessionPages, useTemplatesKnown } from "@/api/queries";
import { Page } from "@/components/shell/AppShell";
import { Button, buttonClasses } from "@/components/ui/Button";
import { EmptyState, ErrorState, StaleNotice, staleSince } from "@/components/ui/States";
import { cn } from "@/lib/cn";
import { useTitle } from "@/lib/title";
import { SessionList, SessionRowsSkeleton } from "./SessionRow";

/**
 * Every session, newest first: the active ones, or the ended ones, a page at a time, as rows with dividers
 * between them. A tab on a laptop; on a phone, reached from Home. The list polls while any session in it can change.
 */
export function AllSessions() {
  useTitle("Sessions");
  const ended = new URLSearchParams(useSearch()).get("show") === "ended";
  const pages = useSessionPages(!ended);
  const namesKnown = useTemplatesKnown();
  const sessions = pages.data?.pages.flatMap((page) => page.sessions);
  const since = staleSince(pages);

  return (
    <Page title="Sessions">
      {since !== null && <StaleNotice updatedAt={since} />}
      <nav aria-label="Which sessions" className="flex gap-2">
        {[
          { label: "Active", href: "/sessions/", current: !ended },
          { label: "Ended", href: "/sessions/?show=ended", current: ended },
        ].map((tab) => (
          <Link
            key={tab.label}
            href={tab.href}
            aria-current={tab.current ? "page" : undefined}
            className={cn(
              "flex min-h-touch items-center rounded-full px-4 text-small font-semibold",
              tab.current ? "bg-primary text-primary-fg" : "bg-fill text-text hover:bg-fill-selected",
            )}
          >
            {tab.label}
          </Link>
        ))}
      </nav>
      <section aria-label={ended ? "Ended sessions" : "Active sessions"}>
        {sessions && namesKnown ? (
          sessions.length === 0 ? (
            <EmptyState
              title={ended ? "No ended sessions" : "Nothing running"}
              action={
                <Link href="/book/" className={buttonClasses("primary", false, true)}>
                  Request a GPU
                </Link>
              }
            >
              {ended
                ? "Sessions that have ended are listed here, with what each one cost."
                : "Sessions that are finding a GPU or running are listed here, with their cost so far."}
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
      </section>
    </Page>
  );
}
