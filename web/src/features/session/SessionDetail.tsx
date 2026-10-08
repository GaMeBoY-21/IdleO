import type { ReactNode } from "react";
import { useSearch } from "wouter";
import { useTemplates, useUploadInfo } from "@/api/queries";
import { useLiveSession } from "@/api/useLiveSession";
import { FlowHeader } from "@/components/shell/AppShell";
import { SkeletonLine } from "@/components/ui/Skeleton";
import { EmptyState, ErrorState, StaleNotice } from "@/components/ui/States";
import { sessionTitle } from "@/features/flow/headline";
import { ProgressRoute } from "@/features/flow/ProgressRoute";
import { TwoPanels } from "@/features/flow/TwoPanels";
import { useAfterPaint } from "@/lib/after-first-screen";
import { aboutRuntime } from "@/lib/catalog";
import { useLaptop } from "@/lib/media";
import { formatTime } from "@/lib/time";
import { useTitle } from "@/lib/title";
import { Ended } from "./Ended";
import { Finding } from "./Finding";
import { LogsLater } from "./LogsLater";
import { Running } from "./Running";

const FINDING = new Set(["REQUESTED", "MATCHED", "PROVISIONING"]);
const STARTED = new Set(["RUNNING", "HOST_LOST", "FINISHING"]);

/** Figures read before this came from a list, or from an earlier visit. */
const PAGE_OPENED_AT = Date.now();

/**
 * One session, live (web/DESIGN.md: designed for the phone first, complete on a laptop). The screen changes with
 * its state, like a ride: finding its GPU, GPU found, running, then done or why it ended. It follows the session's
 * event stream until the session is settled (src/api/useLiveSession.ts). On a phone it's a flow with its own back
 * button; on a laptop, the session on the left and its route and logs on the right.
 */
export function SessionDetail() {
  const id = new URLSearchParams(useSearch()).get("id");
  useTitle("Session");
  const { session, stream } = useLiveSession(id);
  const templates = useTemplates();
  const laptop = useLaptop();
  const painted = useAfterPaint(session.data !== undefined);
  const uploadId = session.data?.input?.uploadId ?? null;
  const upload = useUploadInfo(uploadId, painted);

  const frame = (left: ReactNode, right: ReactNode = null) =>
    laptop ? (
      <TwoPanels bare left={left} right={right} rightLabel="Progress and logs" />
    ) : (
      <div className="flex flex-1 flex-col">
        <FlowHeader back="/sessions/" title="Session" />
        <div className="flex w-full flex-1 flex-col gap-5 px-4 pt-1 md:mx-auto md:max-w-card md:px-6 md:pt-8">
          {left}
        </div>
      </div>
    );

  if (id === null) {
    return frame(
      <EmptyState title="No session chosen">
        Open a session from your home screen or from all sessions.
      </EmptyState>,
    );
  }
  const known = templates.data !== undefined || templates.isError;
  if (!session.data || !known) {
    return frame(
      session.isError ? (
        <ErrorState
          error={session.error}
          what="this session"
          onRetry={() => void session.refetch()}
          retrying={session.isFetching}
        />
      ) : (
        <DetailSkeleton laptop={laptop} />
      ),
    );
  }

  const current = session.data;
  const catalog = templates.data ?? { services: [], templates: [] };
  const template = catalog.templates.find((t) => t.id === current.templateId);
  const templateName = template?.name ?? current.templateId;
  const stale = session.isRefetchError || stream === "down";
  const refreshing = session.isFetching && session.dataUpdatedAt < PAGE_OPENED_AT && !stale;
  const status = refreshing ? (
    <>
      Figures from <span className="tabular">{formatTime(session.dataUpdatedAt)}</span>, refreshing
    </>
  ) : (
    stream === "live" &&
    !stale && (
      <>
        Live, updated <span className="tabular">{formatTime(session.dataUpdatedAt)}</span>
      </>
    )
  );

  const view = FINDING.has(current.state) ? (
    <Finding session={current} data={{ filename: upload.data?.filename ?? null }} laptop={laptop} />
  ) : STARTED.has(current.state) ? (
    <Running
      session={current}
      title={sessionTitle(current, catalog)}
      templateName={templateName}
      about={aboutRuntime(template?.typicalRuntimeSeconds)}
      status={status}
      laptop={laptop}
    />
  ) : (
    <Ended session={current} templateName={templateName} laptop={laptop} />
  );
  const left = (
    <>
      {session.isRefetchError ? (
        <StaleNotice updatedAt={session.dataUpdatedAt} />
      ) : (
        stream === "down" && <StaleNotice updatedAt={session.dataUpdatedAt} stream />
      )}
      {view}
    </>
  );
  const right = (
    <>
      <div className="rounded-panel bg-ground px-9 py-8">
        <ProgressRoute
          across
          session={current}
          typicalRuntimeSeconds={template?.typicalRuntimeSeconds}
          uploadBytes={upload.data?.sizeBytes}
        />
      </div>
      {current.startedAt && <LogsLater id={current.id} settled={current.settled} laptop />}
    </>
  );
  return frame(left, right);
}

/** The pill, the title and the timer as they'll load, so nothing above the figures moves. */
function DetailSkeleton({ laptop }: { laptop: boolean }) {
  return (
    <div className="flex flex-col gap-3" role="status" aria-label="Loading this session">
      <SkeletonLine size="small" width="w-1/4" />
      <SkeletonLine size={laptop ? "question" : "title"} width="w-3/4" />
      <SkeletonLine size="small" width="w-1/3" />
      <SkeletonLine size="timer" width="w-1/2" />
    </div>
  );
}
