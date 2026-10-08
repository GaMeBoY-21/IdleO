import { useMutation, useQueryClient } from "@tanstack/react-query";
import { ArrowLeft, Square } from "lucide-react";
import { useState, type ReactNode } from "react";
import { Link, useSearch } from "wouter";
import { api, data } from "@/api/client";
import { isLive, useTemplateName, useTemplatesKnown, writeSession, keys, type Session } from "@/api/queries";
import { useLiveSession } from "@/api/useLiveSession";
import { Banner } from "@/components/ui/Banner";
import { Button, buttonClasses } from "@/components/ui/Button";
import { Card } from "@/components/ui/Card";
import { SkeletonLine } from "@/components/ui/Skeleton";
import { StateChip } from "@/components/ui/StateChip";
import { EmptyState, ErrorState, StaleNotice } from "@/components/ui/States";
import { RelativeTime } from "@/components/ui/Time";
import { ending } from "@/lib/labels";
import { useLaptop } from "@/lib/media";
import { problemMessage } from "@/lib/problems";
import { formatTime } from "@/lib/time";
import { useTitle } from "@/lib/title";
import { Figures } from "./Figures";
import { Checkpoints, Outputs, Timeline } from "./History";
import { Logs } from "./Logs";
import { StopDialog } from "./StopDialog";

/**
 * One session, live (product surface 3.2; designed for the phone first, complete on a laptop): its state, four
 * figures, its one next step, why it moved or ended, its logs and checkpoints, its timeline and, once completed, its
 * outputs. It follows the session's event stream until the session is settled (src/api/useLiveSession.ts).
 */
export function SessionDetail() {
  const id = new URLSearchParams(useSearch()).get("id");
  useTitle("Session");
  const { session, stream } = useLiveSession(id);
  const namesKnown = useTemplatesKnown();
  const laptop = useLaptop();

  const back = (
    <Link
      href="/sessions/"
      className="flex min-h-touch items-center gap-2 self-start text-small text-accent-text"
    >
      <ArrowLeft aria-hidden="true" className="size-4" strokeWidth={1.75} />
      All sessions
    </Link>
  );

  if (id === null) {
    return (
      <div className="flex flex-col gap-4">
        {back}
        <Card>
          <EmptyState title="No session chosen">
            Open a session from your home screen or from all sessions.
          </EmptyState>
        </Card>
      </div>
    );
  }
  if (session.data && namesKnown) {
    const stale = session.isRefetchError || stream === "down";
    return (
      <div className="flex flex-col gap-4">
        {back}
        {session.isRefetchError ? (
          <StaleNotice updatedAt={session.dataUpdatedAt} />
        ) : (
          stream === "down" && <StaleNotice updatedAt={session.dataUpdatedAt} stream />
        )}
        <Live
          session={session.data}
          updatedAt={session.dataUpdatedAt}
          live={stream === "live" && !stale}
          laptop={laptop}
        />
      </div>
    );
  }
  return (
    <div className="flex flex-col gap-4">
      {back}
      {session.isError ? (
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

function Live({
  session,
  updatedAt,
  live,
  laptop,
}: {
  session: Session;
  updatedAt: number;
  live: boolean;
  laptop: boolean;
}) {
  const templateName = useTemplateName();
  const action = <PrimaryAction session={session} />;
  const running = isLive(session);

  const header = (
    <div className="flex flex-col gap-2">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
        <h1 className="text-heading font-semibold break-words">{templateName(session.templateId)}</h1>
        <StateChip state={session.state} />
      </div>
      <p className="text-small text-text-muted">
        {session.startedAt ? (
          <RelativeTime value={session.startedAt} prefix="Started" />
        ) : (
          <RelativeTime value={session.createdAt} prefix="Booked" />
        )}
        {running && live && (
          <span aria-live="polite">
            {" · "}Live, updated <span className="tabular">{formatTime(updatedAt)}</span>
          </span>
        )}
      </p>
    </div>
  );

  const outputs = session.state === "COMPLETED" && <Outputs id={session.id} />;
  const logs = <Logs id={session.id} settled={session.settled} laptop={laptop} />;

  return (
    <>
      {laptop ? (
        <div className="flex items-start justify-between gap-6">
          {header}
          <div className="shrink-0">{action}</div>
        </div>
      ) : (
        header
      )}
      <Banners session={session} />
      <Figures session={session} />
      {laptop ? (
        <div className="grid grid-cols-12 items-start gap-6">
          <div className="col-span-8 flex min-w-0 flex-col gap-4">
            {outputs}
            {logs}
          </div>
          <div className="col-span-4 flex min-w-0 flex-col gap-4">
            <Checkpoints session={session} />
            <Timeline session={session} />
          </div>
        </div>
      ) : (
        <>
          {outputs}
          {logs}
          <Checkpoints session={session} />
          <Timeline session={session} />
          {/* The action bar's room, so nothing it covers is out of reach and nothing moves when it appears. */}
          <div aria-hidden="true" className="h-16" />
          <div
            data-action-bar=""
            className="fixed inset-x-0 bottom-clear-tabs z-10 border-t border-border bg-surface px-4 py-2"
          >
            {action}
          </div>
        </>
      )}
    </>
  );
}

/** The one next step, by state (product surface 3.2): Stop, Cancel, Download outputs, or Run again. */
function PrimaryAction({ session }: { session: Session }) {
  const client = useQueryClient();
  const [asking, setAsking] = useState(false);
  const settle = (next: Session) => {
    writeSession(client, next);
    void client.invalidateQueries({ queryKey: keys.wallet });
  };
  const stop = useMutation({
    mutationFn: () =>
      data(
        api.POST("/v1/sessions/{id}/stop", {
          params: { path: { id: session.id } },
          body: { checkpointFirst: true },
        }),
      ),
    onSuccess: (next) => {
      settle(next);
      setAsking(false);
    },
  });
  const cancel = useMutation({
    mutationFn: () => data(api.POST("/v1/sessions/{id}/cancel", { params: { path: { id: session.id } } })),
    onSuccess: settle,
  });
  const failed = (error: unknown): ReactNode => <Banner tone="danger">{problemMessage(error)}</Banner>;

  switch (session.state) {
    case "RUNNING":
      return (
        <>
          <Button variant="danger" block onClick={() => setAsking(true)} className="md:w-auto">
            <Square aria-hidden="true" className="size-4" strokeWidth={1.75} />
            Stop
          </Button>
          <StopDialog
            open={asking}
            onOpenChange={(open) => {
              setAsking(open);
              stop.reset();
            }}
            onStop={() => stop.mutate()}
            stopping={stop.isPending}
            error={stop.isError ? failed(stop.error) : null}
          />
        </>
      );
    case "REQUESTED":
    case "MATCHED":
    case "PROVISIONING":
      return (
        <div className="flex flex-col gap-2">
          {cancel.isError && failed(cancel.error)}
          <Button
            variant="secondary"
            block
            className="md:w-auto"
            busy={cancel.isPending}
            onClick={() => cancel.mutate()}
          >
            Cancel
          </Button>
        </div>
      );
    case "COMPLETED":
      return (
        <a href="#outputs" className={buttonClasses("primary", true)}>
          Download outputs
        </a>
      );
    case "HOST_LOST":
    case "FINISHING":
      return (
        <p className="flex min-h-touch items-center text-small text-text-muted">
          {session.state === "FINISHING"
            ? "Saving a last checkpoint and its outputs…"
            : "Moving to another host…"}
        </p>
      );
    default:
      return (
        <Link
          href={`/book/?template=${encodeURIComponent(session.templateId)}&step=configure&from=${session.id}`}
          className={buttonClasses("primary", true)}
        >
          Run again
        </Link>
      );
  }
}

/** Low credit, a move to another host, and why it ended: what the renter should know, in plain words. */
function Banners({ session }: { session: Session }) {
  const banners: ReactNode[] = [];
  if (isLive(session) && session.lowCredit) {
    banners.push(
      <Banner key="low" tone="warning">
        Your credit is running low. When it runs out, the session saves a checkpoint and stops, and
        you&apos;re charged only up to then.
      </Banner>,
    );
  }
  if (session.attempt > 1) {
    const resumed = session.checkpoints.filter((checkpoint) => checkpoint.attempt < session.attempt).at(-1);
    banners.push(
      <Banner key="moved" tone="info">
        {resumed
          ? `Moved to another host and resumed from checkpoint ${resumed.seq}.`
          : "Moved to another host and started again from the beginning."}{" "}
        The lost minutes aren&apos;t charged.
      </Banner>,
    );
  }
  const last = session.events.at(-1)?.reason;
  const stoppedFor = session.events.find((event) => event.state === "FINISHING")?.reason;
  if (session.state === "FAILED" || session.state === "NO_HOST_FREE") {
    const words = ending(session.errorCode ?? last);
    if (words) {
      banners.push(
        <Banner key="ended" tone="danger">
          <span className="font-medium">{words.sentence}</span> {words.next}
        </Banner>,
      );
    }
  } else if (
    session.state === "CANCELLED" ||
    (session.state === "COMPLETED" &&
      (stoppedFor === "credit_exhausted" || stoppedFor === "max_spend_reached"))
  ) {
    const words = ending(session.state === "CANCELLED" ? last : stoppedFor);
    if (words) {
      banners.push(
        <Banner key="ended" tone="info">
          {words.sentence} {words.next}
        </Banner>,
      );
    }
  }
  return banners.length > 0 ? <div className="flex flex-col gap-2">{banners}</div> : null;
}

/** The heading and the figures exactly as they'll load, so nothing above the logs moves. */
function DetailSkeleton() {
  return (
    <div className="flex flex-col gap-4" role="status" aria-label="Loading this session">
      <div className="flex flex-col gap-2">
        <SkeletonLine size="heading" width="w-1/2" />
        <SkeletonLine size="small" width="w-1/3" />
      </div>
      <div className="grid grid-cols-2 gap-px overflow-hidden rounded-card border border-border bg-border xl:grid-cols-4">
        {[0, 1, 2, 3].map((key) => (
          <div key={key} className="bg-surface p-4">
            <SkeletonLine size="small" width="w-1/2" />
            <div className="mt-1">
              <SkeletonLine size="title" width="w-3/4" />
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
