import { Link } from "wouter";
import { useResume, useTelemetry, type Host } from "@/api/hosting";
import { Button } from "@/components/ui/Button";
import { Money } from "@/components/ui/Money";
import { Tile } from "@/components/ui/Tile";
import { Elapsed, RelativeTime } from "@/components/ui/Time";
import { useNow } from "@/lib/clock";
import { gigabytes, gpuName } from "@/lib/gpu";
import { machineState, rupeesAnHour } from "@/lib/hosting";
import { cn } from "@/lib/cn";

/**
 * One machine on a laptop's Hosting tab (docs/design/bento/laptop/hosting-laptop.html), a white tile: its state, with a dot,
 * and its temperature and use when it is online; its name, its GPU, memory and price an hour; and, at the foot,
 * what it is earning now, or the pause with a "Resume" pill. The top is the link into the machine. What it earns is
 * the server's, read again while it runs a session.
 */
export function MachineTile({ host, className }: { host: Host; className?: string }) {
  const now = useNow("half-minute");
  const state = machineState(host, now);
  const telemetry = useTelemetry(host.id, host.online);
  const resume = useResume(host.id);
  const gpu = host.gpus.find((candidate) => !candidate.gone);
  const reading = telemetry.data?.now?.gpus[0];
  const label = state.kind === "paused" ? state.detail : state.label;
  return (
    <Tile size="lg" className={cn("flex min-h-0 flex-col justify-between gap-4 xl:p-6", className)}>
      <Link
        href={`/hosting/machine/?id=${host.id}`}
        className="flex flex-1 flex-col justify-between gap-4 hover:underline"
      >
        <span className="flex items-center justify-between gap-3 text-caption">
          <span
            className={cn(
              "flex items-center gap-2 font-bold",
              state.kind === "running" ? "text-running" : "text-text-muted",
            )}
          >
            <span
              aria-hidden="true"
              className={cn(
                "size-2 rounded-full",
                state.kind === "running"
                  ? "bg-running motion-safe:animate-pulse"
                  : state.active
                    ? "bg-running"
                    : "bg-control",
              )}
            />
            {label}
          </span>
          <span className="text-text-muted tabular">
            {reading ? `${reading.tempC}°C · ${reading.utilPct}% busy` : host.online ? "online" : "offline"}
          </span>
        </span>
        <span className="flex flex-col gap-3">
          <span className="flex flex-col gap-1 text-small text-text-muted">
            {host.currentSession && (
              <span>
                {host.currentSession.runningLabel} · <Elapsed from={host.currentSession.startedAt} />
              </span>
            )}
            {host.online && host.onlineSince ? (
              <span>
                Online since <RelativeTime value={host.onlineSince} />
              </span>
            ) : host.lastSeenAt ? (
              <span>
                Last seen <RelativeTime value={host.lastSeenAt} />
              </span>
            ) : null}
            <span>
              {host.rating
                ? `${host.rating.averageStars.toFixed(1)} from ${host.rating.countAtLeast}+ ratings`
                : "Ratings appear after 5 sessions."}
            </span>
          </span>
        </span>
        <span className="flex flex-col gap-0.5">
          <span className="text-section break-words">{host.name}</span>
          <span className="text-small text-text-muted tabular">
            {gpu ? `${gpuName(gpu.model)} · ${gigabytes(gpu.vramMb)}` : "No GPU reported yet"}
            {host.listing && ` · ${rupeesAnHour(host.listing.batchPricePaisePerMin)}`}
          </span>
        </span>
      </Link>
      {host.currentSession ? (
        <span className="flex items-baseline justify-between text-text-muted">
          <span className="text-caption">Earning now</span>
          <Money paise={host.currentSession.earnedSoFarPaise} className="text-title text-text" />
        </span>
      ) : host.paused ? (
        <span className="flex items-center justify-between gap-3">
          <span className="text-caption text-text-muted">No new sessions while paused</span>
          <Button variant="secondary" busy={resume.isPending} onClick={() => resume.mutate()}>
            Resume
          </Button>
        </span>
      ) : (
        <span className="text-caption text-text-muted">{state.detail}</span>
      )}
    </Tile>
  );
}
