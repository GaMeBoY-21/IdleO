import type { ReactNode } from "react";
import type { Session } from "@/api/queries";
import { StateChip } from "@/components/ui/StateChip";
import { RelativeTime } from "@/components/ui/Time";
import { ActionFoot, StopAction } from "./Actions";
import { Banners } from "./Banners";
import { GpuCard, RunningFigures } from "./Figures";
import { Logs } from "./Logs";
import { LastCheckpoint } from "./Outputs";

/**
 * A session that has started and not ended (docs/design/redesign/live-phone.html, live-laptop.html): the green
 * "Running" pill, what it's doing on which GPU, the big timer, the cost against the cap, the GPU's use and
 * temperature, the last checkpoint, and "Stop session". Moving to another host and saving its outputs keep the same
 * screen, without Stop. On a phone the logs are a row under the checkpoint; on a laptop they're in the right panel.
 */
export function Running({
  session,
  title,
  templateName,
  about,
  status,
  laptop,
}: {
  session: Session;
  title: string;
  templateName: string;
  about: string | null;
  status: ReactNode;
  laptop: boolean;
}) {
  return (
    <>
      <div className={laptop ? "flex flex-col gap-3" : "flex flex-col gap-2"}>
        <span className="self-start">
          <StateChip state={session.state} />
        </span>
        <h1 className={laptop ? "text-question break-words" : "text-title break-words"}>{title}</h1>
        <p className="text-small text-text-muted">
          {session.startedAt ? (
            <RelativeTime value={session.startedAt} prefix="Started" />
          ) : (
            <RelativeTime value={session.createdAt} prefix="Booked" />
          )}
          {laptop && ` · ${templateName}`}
        </p>
        {/* Its status has a line of its own, always there, so nothing below moves as it changes. */}
        <p className="min-h-5 text-small text-text-muted" aria-live="polite">
          {status}
        </p>
      </div>
      <Banners session={session} />
      <RunningFigures session={session} about={about} laptop={laptop} />
      {session.gpu && <GpuCard gpu={session.gpu} laptop={laptop} />}
      {laptop ? (
        <LastCheckpoint session={session} />
      ) : (
        <div className="flex flex-col">
          <LastCheckpoint session={session} className="min-h-14 border-b border-divider py-3.5" />
          <Logs id={session.id} settled={session.settled} laptop={false} />
        </div>
      )}
      {session.state === "RUNNING" ? (
        <StopAction session={session} laptop={laptop} />
      ) : (
        <ActionFoot laptop={laptop} note={null}>
          <p className="flex min-h-14 items-center justify-center text-body text-text-muted">
            {session.state === "FINISHING"
              ? "Saving a last checkpoint and its outputs…"
              : "Moving to another host…"}
          </p>
        </ActionFoot>
      )}
    </>
  );
}
