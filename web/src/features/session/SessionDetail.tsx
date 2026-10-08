import { ArrowLeft, Download } from "lucide-react";
import { Link, useSearch } from "wouter";
import { isLive, useOutputs, useTemplateName, type Session } from "@/api/queries";
import { useLiveSession } from "@/api/useLiveSession";
import { Card, CardTitle } from "@/components/ui/Card";
import { Money } from "@/components/ui/Money";
import { Skeleton, SkeletonLine } from "@/components/ui/Skeleton";
import { StateChip } from "@/components/ui/StateChip";
import { EmptyState, ErrorState, StaleNotice } from "@/components/ui/States";
import { formatBytes } from "@/lib/bytes";
import { costSoFar } from "@/lib/sessions";
import { eventReason, sessionState } from "@/lib/labels";
import { formatDateTime, formatDuration, formatTime } from "@/lib/time";
import { useTitle } from "@/lib/title";

/** One session: its state, what it has cost, its timeline, and its outputs once it has completed. */
export function SessionDetail() {
  const id = new URLSearchParams(useSearch()).get("id");
  useTitle("Session");
  const { session, stream } = useLiveSession(id);
  const templateName = useTemplateName();

  return (
    <div className="flex flex-col gap-4">
      <Link href="/" className="flex min-h-touch items-center gap-2 self-start text-small text-accent-text">
        <ArrowLeft aria-hidden="true" className="size-4" strokeWidth={1.75} />
        Home
      </Link>
      {id === null ? (
        <Card>
          <EmptyState title="No session chosen">Open a session from your home screen.</EmptyState>
        </Card>
      ) : session.data ? (
        <>
          <div className="flex flex-wrap items-center gap-3">
            <h1 className="text-heading font-semibold">{templateName(session.data.templateId)}</h1>
            <StateChip state={session.data.state} />
          </div>
          {session.isRefetchError ? (
            <StaleNotice updatedAt={session.dataUpdatedAt} />
          ) : (
            stream === "down" && <StaleNotice updatedAt={session.dataUpdatedAt} stream />
          )}
          {/* On a phone the outputs come last, so their list arriving moves nothing; on a laptop they sit under the
              cost, beside the timeline. */}
          <div className="grid items-start gap-4 xl:grid-cols-12">
            <div className="min-w-0 xl:col-span-5">
              <CostCard
                session={session.data}
                updatedAt={session.dataUpdatedAt}
                stale={session.isRefetchError || stream === "down"}
              />
            </div>
            <div className="min-w-0 xl:col-span-7 xl:row-span-2">
              <Timeline session={session.data} />
            </div>
            {session.data.state === "COMPLETED" && (
              <div className="min-w-0 xl:col-span-5">
                <OutputsCard id={session.data.id} />
              </div>
            )}
          </div>
        </>
      ) : session.isError ? (
        <Card>
          <ErrorState
            error={session.error}
            what="this session"
            onRetry={() => void session.refetch()}
            retrying={session.isFetching}
          />
        </Card>
      ) : (
        <DetailSkeleton />
      )}
    </div>
  );
}

function CostCard({ session, updatedAt, stale }: { session: Session; updatedAt: number; stale: boolean }) {
  const live = isLive(session);
  return (
    <Card aria-labelledby="cost-title">
      <CardTitle
        id="cost-title"
        action={
          live &&
          !stale && (
            <p className="text-caption text-text-muted" aria-live="polite">
              Live · updated <span className="tabular">{formatTime(updatedAt)}</span>
            </p>
          )
        }
      >
        Cost
      </CardTitle>
      <p className="text-small text-text-muted">{live ? "So far" : "Charged"}</p>
      <Money
        paise={live ? costSoFar(session) : session.chargedPaise}
        className="block text-heading font-semibold md:text-display"
      />
      <p className="text-caption text-text-muted">Each started minute is charged as a whole minute.</p>
      <dl className="mt-4 grid grid-cols-2 gap-4 border-t border-border pt-4 text-small">
        <div>
          <dt className="text-text-muted">Price</dt>
          <dd className="font-medium">
            {session.pricePaisePerMin === null || session.pricePaisePerMin === undefined ? (
              "Set when a host accepts"
            ) : (
              <>
                <Money paise={session.pricePaisePerMin} /> a minute
              </>
            )}
          </dd>
        </div>
        <div>
          <dt className="text-text-muted">Spending limit</dt>
          <dd className="font-medium">
            <Money paise={session.maxSpendPaise} />
          </dd>
        </div>
        <div>
          <dt className="text-text-muted">Started</dt>
          <dd className="font-medium">{session.startedAt ? formatDateTime(session.startedAt) : "Not yet"}</dd>
        </div>
        <div>
          <dt className="text-text-muted">{live ? "Running for" : "Ran for"}</dt>
          <dd className="font-medium tabular">
            {session.startedAt ? formatDuration(session.startedAt, endOf(session) ?? updatedAt) : "—"}
          </dd>
        </div>
      </dl>
    </Card>
  );
}

/** When the session stopped running: its last event, once it's no longer live. */
function endOf(session: Session): string | null {
  return isLive(session) ? null : (session.events.at(-1)?.at ?? null);
}

function Timeline({ session }: { session: Session }) {
  return (
    <Card aria-labelledby="timeline-title">
      <CardTitle id="timeline-title">Timeline</CardTitle>
      <ol className="flex flex-col">
        {session.events.map((event, index) => (
          <li key={`${event.at}-${index}`} className="relative flex gap-4 pb-4 last:pb-0">
            <span aria-hidden="true" className="mt-2 size-2 shrink-0 rounded-full bg-border-strong" />
            <div className="min-w-0 flex-1">
              <p className="text-small font-medium">{sessionState(event.state).label}</p>
              <p className="text-small text-text-muted">{eventReason(event.reason)}</p>
            </div>
            <time dateTime={event.at} className="text-caption text-text-muted tabular">
              {formatDateTime(event.at)}
            </time>
          </li>
        ))}
      </ol>
    </Card>
  );
}

function OutputsCard({ id }: { id: string }) {
  const outputs = useOutputs(id, true);
  return (
    <Card aria-labelledby="outputs-title">
      <CardTitle id="outputs-title">Outputs</CardTitle>
      {outputs.data ? (
        outputs.data.files.length === 0 ? (
          <EmptyState title="No outputs">This session finished without saving any outputs.</EmptyState>
        ) : (
          <>
            <ul className="divide-y divide-border rounded-control border border-border">
              {outputs.data.files.map((file) => (
                <li key={file.path}>
                  <a
                    href={file.url}
                    download
                    className="flex min-h-touch items-center gap-3 px-3 py-2 text-small hover:bg-sunken"
                  >
                    <Download aria-hidden="true" className="size-5 text-accent-text" strokeWidth={1.75} />
                    <span className="min-w-0 flex-1 truncate font-medium">{file.path}</span>
                    <span className="text-caption text-text-muted tabular">
                      {formatBytes(file.sizeBytes)}
                    </span>
                  </a>
                </li>
              ))}
            </ul>
            <p className="mt-2 text-caption text-text-muted">
              Download links work until{" "}
              <span className="tabular">{formatTime(outputs.data.linksExpireAt)}</span>; they renew while this
              page is open.
            </p>
          </>
        )
      ) : outputs.isError ? (
        <ErrorState
          error={outputs.error}
          what="the outputs"
          onRetry={() => void outputs.refetch()}
          retrying={outputs.isFetching}
        />
      ) : (
        <Skeleton className="h-touch w-full" />
      )}
    </Card>
  );
}

/** The heading and the cost card exactly as they'll load, so nothing above the timeline moves. */
function DetailSkeleton() {
  return (
    <div className="flex flex-col gap-4" role="status" aria-label="Loading this session">
      <SkeletonLine size="heading" width="w-1/2" />
      <div className="grid items-start gap-4 xl:grid-cols-12">
        <div className="min-w-0 rounded-card border border-border bg-surface p-4 md:p-6 xl:col-span-5">
          <div className="mb-4 flex min-h-touch items-center">
            <SkeletonLine size="title" width="w-1/4" />
          </div>
          <SkeletonLine size="small" width="w-1/4" />
          <SkeletonLine size="heading" md="display" width="w-1/2" />
          <SkeletonLine size="caption" width="w-3/4" />
          <div className="mt-4 grid grid-cols-2 gap-4 border-t border-border pt-4">
            {[0, 1, 2, 3].map((key) => (
              <div key={key}>
                <SkeletonLine size="small" width="w-1/2" />
                <SkeletonLine size="small" width="w-3/4" />
              </div>
            ))}
          </div>
        </div>
        <div className="min-w-0 rounded-card border border-border bg-surface p-4 md:p-6 xl:col-span-7">
          <div className="mb-4 flex min-h-touch items-center">
            <SkeletonLine size="title" width="w-1/4" />
          </div>
          {[0, 1, 2].map((key) => (
            <div key={key} className="pb-4 last:pb-0">
              <SkeletonLine size="small" width="w-1/3" />
              <SkeletonLine size="small" width="w-1/2" />
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
