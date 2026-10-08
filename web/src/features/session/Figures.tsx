import { Cpu } from "lucide-react";
import type { Session } from "@/api/queries";
import { ProgressBar } from "@/components/ui/Inputs";
import { Money } from "@/components/ui/Money";
import { Elapsed } from "@/components/ui/Time";
import { cn } from "@/lib/cn";
import { gigabytes, gpuName } from "@/lib/gpu";
import { costSoFar } from "@/lib/sessions";

/**
 * A running session's figures (docs/design/redesign/live-phone.html, live-laptop.html): the big timer, ticking, with
 * "of about 45 min" when the template's typical runtime is known; the cost so far against the spending cap, with a
 * bar; and the price an hour. Money changes in place and never counts up. On a phone the timer and the cost share
 * a row; on a laptop each has its own.
 */
export function RunningFigures({
  session,
  about,
  laptop,
}: {
  session: Session;
  about: string | null;
  laptop: boolean;
}) {
  const cost = costSoFar(session);
  const percent = Math.min(100, Math.floor((cost * 100) / Math.max(1, session.maxSpendPaise)));
  const price = session.pricePaisePerMin == null ? null : <Money paise={session.pricePaisePerMin * 60} />;
  const timer = (
    <div className="flex flex-col gap-1">
      <p className={laptop ? "text-timer-xl" : "text-timer"}>
        <span className="sr-only">Running for </span>
        {session.startedAt ? <Elapsed from={session.startedAt} /> : <span className="tabular">0:00</span>}
      </p>
      {/* Always there, so the figures under it never move. */}
      <p className={cn("min-h-5 text-text-muted", laptop ? "text-body" : "text-small")}>
        {about && `of ${about}`}
      </p>
    </div>
  );
  const bar = <ProgressBar percent={percent} label="Cost so far against your spending cap" />;

  if (!laptop) {
    return (
      <div className="flex flex-col gap-5">
        <div className="flex items-end justify-between gap-4">
          {timer}
          <div className="flex flex-col items-end gap-1">
            <Money paise={cost} className="text-title" />
            <span className="min-h-5 text-small text-text-muted">so far</span>
          </div>
        </div>
        <div className="flex flex-col gap-2">
          {bar}
          <p className="text-caption text-text-muted tabular">
            Stops at <Money paise={session.maxSpendPaise} />
            {price && <> · {price} an hour</>}
          </p>
        </div>
      </div>
    );
  }
  return (
    <div className="flex flex-col gap-7">
      {timer}
      <div className="flex flex-col gap-3">
        <div className="flex items-baseline justify-between gap-4 tabular">
          <p>
            <Money paise={cost} className="text-title" />{" "}
            <span className="text-body font-medium text-text-muted">so far</span>
          </p>
          <p className="text-body text-text-muted">
            stops at <Money paise={session.maxSpendPaise} />
          </p>
        </div>
        {bar}
        <p className="text-small text-text-muted tabular">
          {price && <>{price} an hour · </>}charged per started minute
        </p>
      </div>
    </div>
  );
}

/**
 * The GPU it's on, with how busy and how hot it is (§0.4 of the API: from its host's heartbeats, or unknown when
 * those are stale). Never the host's identity.
 */
export function GpuCard({ gpu, laptop }: { gpu: NonNullable<Session["gpu"]>; laptop: boolean }) {
  const reading =
    gpu.utilPct == null || gpu.tempC == null ? "Reading its use…" : `${gpu.utilPct}% busy · ${gpu.tempC}°C`;
  return (
    <div className={cn("flex items-center rounded-control bg-fill", laptop ? "gap-4 p-4" : "gap-3.5 p-3.5")}>
      <span
        aria-hidden="true"
        className={cn(
          "flex shrink-0 items-center justify-center rounded-icon bg-primary text-primary-fg",
          laptop ? "size-12" : "size-11",
        )}
      >
        <Cpu className={laptop ? "size-7" : "size-6"} strokeWidth={1.5} />
      </span>
      <span className="flex min-w-0 flex-1 flex-col gap-0.5">
        <span className={cn("font-semibold break-words", laptop ? "text-option" : "text-body")}>
          {gpuName(gpu.model)} · {gigabytes(gpu.vramMb)}
        </span>
        <span className={cn("text-text-muted tabular", laptop ? "text-small" : "text-caption")}>
          {reading}
        </span>
      </span>
    </div>
  );
}
