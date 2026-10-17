import { useHost, useHostSessions, type HostSession } from "@/api/hosting";
import { Button } from "@/components/ui/Button";
import { Money } from "@/components/ui/Money";
import { SkeletonLine } from "@/components/ui/Skeleton";
import { Tile } from "@/components/ui/Tile";
import { EmptyState, ErrorState, StaleNotice, staleSince } from "@/components/ui/States";
import { RelativeTime } from "@/components/ui/Time";
import { cn } from "@/lib/cn";
import { formatMinutes, outcomeLabel } from "@/lib/hosting";
import { useTitle } from "@/lib/title";
import { HostFlow } from "@/components/shell/HostFlow";
import { useHostId } from "@/lib/use-host-id";

const ROW = "flex min-h-18 items-center gap-3 py-4";

/**
 * The sessions a machine ran, newest first, a page at a time (docs/design/hosting/): what ran, when, for how long, what
 * it earned and how it ended. Never who it ran for.
 */
export function HostSessions() {
  const id = useHostId();
  const host = useHost(id);
  const pages = useHostSessions(id ?? "", id !== null);
  const sessions = pages.data?.pages.flatMap((page) => page.sessions);
  const since = staleSince(pages);
  useTitle("Sessions it ran");
  const back = id ? `/hosting/machine/?id=${id}` : "/hosting/";

  return (
    <HostFlow
      back={back}
      backLabel={host.data?.name ?? "Hosting"}
      title="Sessions it ran"
      lede="What ran here, when, for how long and what each earned. Renters are never named."
    >
      {since !== null && <StaleNotice updatedAt={since} />}
      {id === null ? (
        <EmptyState title="No machine chosen">Open a machine from the Hosting tab.</EmptyState>
      ) : sessions ? (
        sessions.length === 0 ? (
          <EmptyState title="Nothing has run here yet">
            Sessions that run on this machine are listed here, with what each earned. Renters are never named.
          </EmptyState>
        ) : (
          <div className="flex flex-col gap-4">
            <Tile as="div" className="px-4.5 py-0">
              <ul className="flex flex-col divide-y divide-divider">
                {sessions.map((session) => (
                  <SessionRow key={session.id} session={session} />
                ))}
              </ul>
            </Tile>
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
          what="the sessions it ran"
          onRetry={() => void pages.refetch()}
          retrying={pages.isFetching}
        />
      ) : (
        <Tile
          as="div"
          role="status"
          aria-label="Loading the sessions it ran"
          className="flex flex-col divide-y divide-divider px-4.5 py-0"
        >
          {[0, 1, 2, 3].map((key) => (
            <div key={key} className={ROW}>
              <div className="flex flex-1 flex-col gap-0.5">
                <SkeletonLine size="option" width="w-1/2" />
                <SkeletonLine size="small" width="w-2/3" />
              </div>
              <SkeletonLine size="option" width="w-1/4" />
            </div>
          ))}
        </Tile>
      )}
    </HostFlow>
  );
}

function SessionRow({ session }: { session: HostSession }) {
  const running = session.outcome === "running";
  return (
    <li className={ROW}>
      <div className="flex min-w-0 flex-1 flex-col gap-0.5">
        <p className="text-option font-semibold break-words">
          {session.runningLabel} · {formatMinutes(session.minutes)}
        </p>
        <p className="text-small text-text-muted">
          <RelativeTime value={session.startedAt} /> · {outcomeLabel(session)}
        </p>
      </div>
      <p className={cn("text-option font-semibold tabular", session.earnedPaise > 0 && "text-running")}>
        <span className="sr-only">Earned </span>
        {session.earnedPaise > 0 && <span aria-hidden="true">+</span>}
        <Money paise={session.earnedPaise} />
        {running && <span className="sr-only"> so far</span>}
      </p>
    </li>
  );
}
