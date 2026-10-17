import type { ReactNode } from "react";
import { useSearch } from "wouter";
import { useTemplates, useUploadInfo } from "@/api/queries";
import { useLiveSession } from "@/api/useLiveSession";
import { FlowHeader } from "@/components/shell/AppShell";
import { Skeleton } from "@/components/ui/Skeleton";
import { EmptyState, ErrorState, StaleNotice } from "@/components/ui/States";
import { sessionTitle } from "@/features/flow/headline";
import { useAfterPaint } from "@/lib/after-first-screen";
import { aboutRuntime, isOffered, serviceOf } from "@/lib/catalog";
import { useWide } from "@/lib/media";
import { formatTime } from "@/lib/time";
import { useTitle } from "@/lib/title";
import { Ended } from "./Ended";
import { RailColumns } from "@/components/shell/Layout";
import { useLogSignals } from "./laptop/useLogSignals";
import { StepsTile } from "./laptop/StepsTile";
import { YourRequestTile } from "./laptop/YourRequestTile";
import { FRAME } from "@/components/shell/Layout";
import { Finding } from "./Finding";
import { Running } from "./Running";
import { sessionInput } from "@/lib/inputs";

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
  // From 768 px the session is tiles on a grid, side by side from 1200 px (web/DESIGN.md "Laptop layout").
  const laptop = useWide();
  const signals = useLogSignals(id ?? "");
  const painted = useAfterPaint(session.data !== undefined);
  const uploadId = (session.data && sessionInput(session.data)?.uploadId) ?? null;
  const upload = useUploadInfo(uploadId, painted);

  const frame = (content: ReactNode) =>
    laptop ? (
      <div className="flex flex-1 flex-col gap-4">{content}</div>
    ) : (
      <div className="flex flex-1 flex-col">
        <FlowHeader back="/sessions/" title="Session" />
        <div className="flex w-full flex-1 flex-col gap-2.5 px-3.5 pt-1 pb-3.5 md:mx-auto md:max-w-card md:px-6 md:pt-8">
          {content}
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

  // Where it has got, in four steps, beside the log (and above the request on the finding screen).
  const steps = (
    <StepsTile
      session={current}
      signals={signals}
      typicalRuntimeSeconds={template?.typicalRuntimeSeconds}
      uploadBytes={upload.data?.upload.sizeBytes}
      uploadFiles={upload.data?.upload.fileCount}
    />
  );
  const serviceName = serviceOf(catalog, current.templateId)?.name ?? templateName;
  const view = FINDING.has(current.state) ? (
    <Finding
      session={current}
      data={{ filename: upload.data?.upload.name ?? null }}
      laptop={laptop}
      steps={steps}
      request={
        <YourRequestTile session={current} template={template} filename={upload.data?.upload.name ?? null} />
      }
    />
  ) : STARTED.has(current.state) ? (
    <Running
      session={current}
      title={sessionTitle(current, catalog)}
      templateName={templateName}
      about={aboutRuntime(template?.typicalRuntimeSeconds)}
      status={status}
      laptop={laptop}
      progress={steps}
      serviceName={serviceName}
      signals={signals}
    />
  ) : (
    <Ended
      session={current}
      templateName={templateName}
      laptop={laptop}
      offered={isOffered(catalog, current.templateId)}
    />
  );
  const notice = session.isRefetchError ? (
    <StaleNotice updatedAt={session.dataUpdatedAt} />
  ) : (
    stream === "down" && <StaleNotice updatedAt={session.dataUpdatedAt} stream />
  );
  return frame(
    <>
      {notice && (laptop ? <div className={FRAME}>{notice}</div> : notice)}
      {view}
    </>,
  );
}

/** The tiles as they'll load, so nothing above the figures moves: the summary on the left, the rest on the right. */
function DetailSkeleton({ laptop }: { laptop: boolean }) {
  return (
    <div role="status" aria-label="Loading this session">
      {laptop ? (
        <RailColumns
          left={<Skeleton tile className="h-panel" />}
          right={<Skeleton tile className="h-panel xl:flex-1" />}
        />
      ) : (
        <Skeleton tile className="h-session-tile w-full" />
      )}
    </div>
  );
}
