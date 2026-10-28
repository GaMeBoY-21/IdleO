import type { ReactNode } from "react";
import type { Session } from "@/api/queries";
import { ActionFoot, StopAction } from "./Actions";
import { Banners } from "./Banners";
import { GpuTile, RunningTile, SmallTiles } from "./Figures";
import { laptopTiles } from "./laptop/registry";
import { FIT_FRAME } from "@/components/shell/Layout";
import { cn } from "@/lib/cn";
import { LogsLater } from "./LogsLater";
import type { LogSignals } from "@/lib/log-signals";
import { CheckpointTile } from "./Outputs";

/**
 * A session that has started and not ended (web/DESIGN.md "The session screen"): the dark tile with its state, the
 * time and the cost, then tiles for the GPU (use and temperature), the checkpoints, the log and Stop. Moving to
 * another host and saving its outputs keep the same screen, without Stop. On a phone they stack, the logs a row that
 * opens; on a laptop the night band is across the top, the GPU's panel beside the log, and the session's progress under
 * them (approved reference `Session`).
 */
export function Running({
  session,
  title,
  templateName,
  about,
  status,
  laptop,
  progress,
  serviceName,
  signals,
}: {
  session: Session;
  title: string;
  templateName: string;
  about: string | null;
  status: ReactNode;
  laptop: boolean;
  /** The steps tile (laptop): where it has got, by the route and the job's own log. */
  progress: ReactNode;
  serviceName: string;
  signals: LogSignals;
}) {
  const stop =
    session.state === "RUNNING" ? (
      <StopAction session={session} laptop={laptop} />
    ) : (
      <ActionFoot laptop={laptop} note={null}>
        <p className="flex min-h-14 items-center justify-center text-body text-text-2">
          {session.state === "FINISHING"
            ? "Saving a last checkpoint and its outputs…"
            : "Moving to another host…"}
        </p>
      </ActionFoot>
    );
  const tiles = (
    <>
      <Banners session={session} />
      <RunningTile
        session={session}
        title={title}
        templateName={templateName}
        about={about}
        status={status}
        laptop={laptop}
      />
      <SmallTiles>
        {session.gpu && <GpuTile gpu={session.gpu} />}
        <CheckpointTile session={session} />
      </SmallTiles>
    </>
  );
  if (!laptop) {
    return (
      <>
        {tiles}
        <LogsLater id={session.id} settled={session.settled} laptop={false} />
        {stop}
      </>
    );
  }
  const { LiveTile, StatsPanel } = laptopTiles();
  return (
    <div className={cn(FIT_FRAME, "flex flex-col gap-4")}>
      <Banners session={session} />
      <LiveTile session={session} serviceName={serviceName} about={about} status={status} signals={signals} />
      <LogsLater
        id={session.id}
        settled={session.settled}
        laptop
        side={<StatsPanel session={session} signals={signals} />}
      />
      {progress}
    </div>
  );
}
