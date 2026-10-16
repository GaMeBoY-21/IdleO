import type { ReactNode } from "react";
import type { Session } from "@/api/queries";
import { ActionFoot, StopAction } from "./Actions";
import { Banners } from "./Banners";
import { GpuTile, RunningTile, SmallTiles } from "./Figures";
import { LogsLater } from "./LogsLater";
import { CheckpointTile } from "./Outputs";

/**
 * A session that has started and not ended (web/DESIGN.md "The session screen"): the dark tile with its state, the
 * time and the cost, then tiles for the GPU (use and temperature), the checkpoints, the log and Stop. Moving to
 * another host and saving its outputs keep the same screen, without Stop. On a phone they stack, the logs a row that
 * opens; on a laptop the tiles are in a column beside the log, with the session's progress under them.
 */
export function Running({
  session,
  title,
  templateName,
  about,
  status,
  laptop,
  progress,
}: {
  session: Session;
  title: string;
  templateName: string;
  about: string | null;
  status: ReactNode;
  laptop: boolean;
  /** The route of the session's progress, in a tile of its own; shown on a laptop. */
  progress: ReactNode;
}) {
  const stop =
    session.state === "RUNNING" ? (
      <StopAction session={session} laptop={laptop} />
    ) : (
      <ActionFoot laptop={laptop} note={null}>
        <p className="flex min-h-14 items-center justify-center text-body text-text-muted">
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
  return (
    <div className="grid grid-cols-12 items-stretch gap-3.5">
      <div className="col-span-6 flex flex-col gap-3.5">
        {tiles}
        {progress}
        {stop}
      </div>
      <div className="col-span-6 flex min-h-panel">
        <LogsLater id={session.id} settled={session.settled} laptop />
      </div>
    </div>
  );
}
