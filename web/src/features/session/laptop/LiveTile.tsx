import type { ReactNode } from "react";
import type { Session } from "@/api/queries";
import { Money } from "@/components/ui/Money";
import { Tile } from "@/components/ui/Tile";
import { Elapsed, RelativeTime } from "@/components/ui/Time";
import { cn } from "@/lib/cn";
import type { LogSignals } from "@/lib/log-signals";
import { sessionState } from "@/lib/labels";
import { costSoFar } from "@/lib/sessions";
import { StopAction } from "../Actions";

/**
 * A running session's dark tile on a laptop (docs/design/bento/laptop/live-session-laptop.html): its state, and the epoch
 * when the job's log says one, in lime; the service's name; the time at 60 px with "of about N min"; the cost so far in
 * lime and what it stops at; the rate, how it is charged and what is held; and the Stop button. Money changes in place
 * and never counts up. Moving to another host and saving its outputs keep the tile, without Stop.
 */
export function LiveTile({
  session,
  serviceName,
  about,
  status,
  signals,
}: {
  session: Session;
  serviceName: string;
  about: string | null;
  status: ReactNode;
  signals: LogSignals;
}) {
  const running = session.state === "RUNNING";
  const label = sessionState(session.state).label;
  const price = session.pricePaisePerMin == null ? null : <Money paise={session.pricePaisePerMin * 60} />;
  return (
    <Tile tone="dark" size="lg" className="flex flex-col gap-4.5">
      <span className="flex items-center gap-2 text-caption font-semibold text-live" data-testid="state-chip">
        <span
          aria-hidden="true"
          data-pulse={running ? "" : undefined}
          className={cn("size-2 rounded-full bg-live", running && "motion-safe:animate-pulse")}
        />
        {label}
        {running && signals.epoch && (
          <span className="tabular">
            {" · epoch "}
            {signals.epoch.at} of {signals.epoch.of}
          </span>
        )}
      </span>
      <div className="flex flex-col gap-1.5">
        <h1 className="text-headline break-words">{serviceName}</h1>
        <p className="min-h-5 text-caption text-dark-muted" aria-live="polite">
          {session.startedAt ? (
            <RelativeTime value={session.startedAt} prefix="Started" />
          ) : (
            <RelativeTime value={session.createdAt} prefix="Booked" />
          )}
          {status && <> · {status}</>}
        </p>
      </div>
      <div className="flex items-end justify-between gap-4">
        <div className="flex flex-col gap-0.5">
          <p className="text-timer-lg">
            <span className="sr-only">Running for </span>
            {session.startedAt ? <Elapsed from={session.startedAt} /> : <span className="tabular">0:00</span>}
          </p>
          <p className="min-h-4.5 text-caption text-dark-muted">{about && `of ${about}`}</p>
        </div>
        <div className="flex flex-col items-end gap-0.5 text-right">
          <Money paise={costSoFar(session)} className="text-title text-live" />
          <span className="text-caption text-dark-muted tabular">
            <span className="sr-only">so far, </span>stops at <Money paise={session.maxSpendPaise} />
          </span>
        </div>
      </div>
      <p className="min-h-4.5 text-caption text-dark-muted tabular">
        {price && <>{price} an hour · </>}charged per started minute
        {session.reservedPaise > 0 && (
          <>
            {" · "}
            <Money paise={session.reservedPaise} /> held
          </>
        )}
      </p>
      {running ? (
        <StopAction session={session} laptop inside />
      ) : (
        <p className="flex min-h-14 items-center justify-center text-body text-dark-muted">
          {session.state === "FINISHING"
            ? "Saving a last checkpoint and its outputs…"
            : "Moving to another host…"}
        </p>
      )}
    </Tile>
  );
}
