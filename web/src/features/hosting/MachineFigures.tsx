import { Star } from "lucide-react";
import { useTelemetry, type Host } from "@/api/hosting";
import { Money } from "@/components/ui/Money";
import { Skeleton } from "@/components/ui/Skeleton";
import { Elapsed, RelativeTime } from "@/components/ui/Time";
import { COMMISSION_PERCENT } from "@/lib/hosting";
import { gpuName, gigabytes } from "@/lib/gpu";

/**
 * The machine's GPU, and how hot and busy it is against the limit its owner set (docs/design/hosting/machine-phone.html),
 * from the telemetry the agent reports every few seconds (for this screen only; it's never used for billing).
 */
export function GpuLine({ host }: { host: Host }) {
  const telemetry = useTelemetry(host.id, host.online);
  const gpu = host.gpus.find((candidate) => !candidate.gone);
  const reading = telemetry.data?.now?.gpus[0];
  const cap = host.listing?.gpuTempCapC;
  const hour = telemetry.data?.lastHour ?? [];
  const peak = Math.max(0, ...hour.flatMap((point) => point.gpus.map((r) => r.tempC)));
  return (
    <section aria-label="Your GPU" className="flex min-h-18 flex-col gap-1">
      <p className="text-section font-bold">
        {gpu ? `${gpuName(gpu.model)} · ${gigabytes(gpu.vramMb)}` : "No GPU reported yet"}
      </p>
      {!host.online ? (
        <p className="text-small text-text-muted">
          {host.lastSeenAt ? (
            <>
              Offline · last seen <RelativeTime value={host.lastSeenAt} />
            </>
          ) : (
            "Offline · waiting for the host app"
          )}
        </p>
      ) : reading ? (
        <p className="text-small text-text-muted tabular">
          {reading.tempC}°C{cap ? ` of ${cap}°C limit` : ""} · {reading.utilPct}% busy
          {peak > 0 && ` · peak ${peak}°C in the last hour`}
        </p>
      ) : telemetry.isLoading ? (
        <Skeleton className="h-5 w-2/3" />
      ) : (
        <p className="text-small text-text-muted">No reading yet</p>
      )}
    </section>
  );
}

/** Ratings show once this many renters have given one: a host's average never singles out one renter's rating. */
export const RATINGS_SHOWN_FROM = 5;

/**
 * What renters rated this machine (ADR 0028): "4.6 from 20+ ratings" once 5 or more have, from the last 90 days, else
 * when they will. The control plane works it out once a day and rounds the count down to a multiple of 5, so a change
 * after one session never shows one renter's stars. A line that's always there, so one arriving moves nothing. Never
 * who rated it or what they wrote.
 */
export function RatingLine({ host }: { host: Host }) {
  const rating = host.rating;
  return (
    <p className="flex min-h-5 items-center gap-1.5 text-small text-text-muted">
      {rating ? (
        <>
          <Star aria-hidden="true" className="size-4 text-text" fill="currentColor" strokeWidth={1.75} />
          <span className="tabular">
            <span className="sr-only">Rated </span>
            {rating.averageStars.toFixed(1)}
            <span className="sr-only"> out of 5</span> from {rating.countAtLeast}+ ratings
          </span>
        </>
      ) : (
        `Ratings appear after ${RATINGS_SHOWN_FROM} sessions.`
      )}
    </p>
  );
}

/**
 * "Right now": the session running on the machine and what the owner has earned from it so far, as the server last
 * said (read again every 5 seconds, never counted up here); never who it's for.
 */
export function RightNow({ host }: { host: Host }) {
  const session = host.currentSession;
  return (
    <section aria-labelledby="right-now" className="flex flex-col gap-2 rounded-panel bg-fill p-4 md:p-6">
      <h2 id="right-now" className="text-small font-semibold text-text-muted">
        Right now
      </h2>
      {session ? (
        <>
          <p className="text-option font-semibold">
            {session.runningLabel} · <Elapsed from={session.startedAt} />
          </p>
          <Money paise={session.earnedSoFarPaise} className="block text-title" />
          <p className="text-small text-text-muted">earned so far · you keep {100 - COMMISSION_PERCENT}%</p>
        </>
      ) : (
        <p className="text-small text-text-muted">
          Nothing is running. You earn as soon as a renter&apos;s job starts here.
        </p>
      )}
    </section>
  );
}

/** What every owner is told on their machine's screen: who they are stays private, and so do their files. */
export function PrivacyLine() {
  return (
    <p className="text-small text-text-muted">
      Renters never see who you are, and their work never touches your files.
    </p>
  );
}
