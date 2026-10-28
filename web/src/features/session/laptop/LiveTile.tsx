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
 * A running session's night band on a laptop (approved reference `Session`), across the top: its state, and the epoch
 * when the job's log says one, in volt; the service's name in Archivo; when it started and the live status; and on the
 * right the time with "of about N min", the cost so far in volt with what it stops at, and the Stop button. Under the
 * name, the rate, how it is charged and what is held. Money changes in place and never counts up. Moving to another
 * host and saving its outputs keep the band, without Stop.
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
    <Tile tone="night" size="feature" className="flex flex-wrap items-center justify-between gap-x-8 gap-y-6">
      <div className="flex min-w-0 flex-col gap-2.5">
        <span
          className="flex items-center gap-2.5 text-caption font-bold tracking-wider text-volt uppercase"
          data-testid="state-chip"
        >
          <span
            aria-hidden="true"
            data-pulse={running ? "" : undefined}
            className={cn("size-2.5 rounded-full bg-running-dot", running && "motion-safe:animate-pulse")}
          />
          {label}
          {running && signals.epoch && (
            <span className="tabular">
              {" · epoch "}
              {signals.epoch.at} of {signals.epoch.of}
            </span>
          )}
        </span>
        <h1 className="font-display text-display-page break-words uppercase">{serviceName}</h1>
        <p className="min-h-5 text-small text-on-night-2" aria-live="polite">
          {session.startedAt ? (
            <RelativeTime value={session.startedAt} prefix="Started" />
          ) : (
            <RelativeTime value={session.createdAt} prefix="Booked" />
          )}
          {status && <> · {status}</>}
        </p>
        <p className="min-h-4.5 text-caption text-on-night-3 tabular">
          {price && <>{price} an hour · </>}charged per started minute
          {session.reservedPaise > 0 && (
            <>
              {" · "}
              <Money paise={session.reservedPaise} /> held
            </>
          )}
        </p>
      </div>
      <div className="flex flex-wrap items-center gap-x-7 gap-y-4">
        <div className="flex flex-col gap-0.5">
          <span className="text-caption text-on-night-3">Time</span>
          <p className="font-title text-display-figure">
            <span className="sr-only">Running for </span>
            {session.startedAt ? <Elapsed from={session.startedAt} /> : <span className="tabular">0:00</span>}
          </p>
          <p className="min-h-4.5 text-caption text-on-night-3">{about && `of ${about}`}</p>
        </div>
        <div className="flex flex-col gap-0.5">
          <span className="text-caption text-on-night-3">
            Spent<span className="sr-only"> so far</span> · stops at <Money paise={session.maxSpendPaise} />
          </span>
          <Money paise={costSoFar(session)} className="font-title text-display-figure text-volt" />
          <span className="min-h-4.5" />
        </div>
        {running ? (
          <StopAction session={session} laptop inside />
        ) : (
          <p className="flex min-h-14 items-center text-body text-on-night-2">
            {session.state === "FINISHING"
              ? "Saving a last checkpoint and its outputs…"
              : "Moving to another host…"}
          </p>
        )}
      </div>
    </Tile>
  );
}
