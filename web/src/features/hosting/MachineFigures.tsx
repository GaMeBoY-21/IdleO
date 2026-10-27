import { Star } from "lucide-react";
import { useTelemetry, type Host } from "@/api/hosting";
import { Money } from "@/components/ui/Money";
import { Skeleton } from "@/components/ui/Skeleton";
import { Tile } from "@/components/ui/Tile";
import { Elapsed, RelativeTime } from "@/components/ui/Time";
import { COMMISSION_PERCENT, reliabilityLowers, reliabilityWord, uploadLine } from "@/lib/hosting";
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
    <Tile as="section" aria-label="Your GPU" className="flex min-h-18 flex-col gap-1">
      <p className="text-section">
        {gpu ? `${gpuName(gpu.model)} · ${gigabytes(gpu.vramMb)}` : "No GPU reported yet"}
      </p>
      {/* Two lines are always kept for the reading, which can wrap on a phone, so it arriving moves nothing. */}
      {!host.online ? (
        <p className="min-h-10 text-small text-text-2">
          {host.lastSeenAt ? (
            <>
              Offline · last seen <RelativeTime value={host.lastSeenAt} />
            </>
          ) : (
            "Offline · waiting for the host app"
          )}
        </p>
      ) : reading ? (
        <p className="min-h-10 text-small text-text-2 tabular">
          {reading.tempC}°C{cap ? ` of ${cap}°C limit` : ""} · {reading.utilPct}% busy
          {peak > 0 && ` · peak ${peak}°C in the last hour`}
        </p>
      ) : telemetry.isLoading ? (
        <div className="min-h-10">
          <Skeleton className="h-5 w-2/3" />
        </div>
      ) : (
        <p className="min-h-10 text-small text-text-2">No reading yet</p>
      )}
    </Tile>
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
    <p className="flex min-h-5 items-center gap-1.5 text-small text-on-night-2">
      {rating ? (
        <>
          <Star aria-hidden="true" className="size-4 text-volt" fill="currentColor" strokeWidth={1.75} />
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
 * How reliable this machine has been (the control plane's host score, which also helps renters' jobs find it): "Reliability:
 * Good", "Fair" or "New" while there's little to go on, and what is holding it down in plain sentences. The word comes
 * with the machine, so it arrives with the rest of the tile and moves nothing. Renters never see it.
 */
export function ReliabilityLine({ host }: { host: Host }) {
  const lowers = reliabilityLowers(host.reliability.lowers);
  return (
    <div className="flex flex-col gap-1 text-small text-on-night-2">
      <p>
        Reliability:{" "}
        <span className="font-semibold text-on-night">{reliabilityWord(host.reliability.label)}</span>
      </p>
      {lowers.length > 0 && (
        <ul className="flex flex-col gap-0.5" aria-label="What lowers it">
          {lowers.map((sentence) => (
            <li key={sentence}>{sentence}</li>
          ))}
        </ul>
      )}
    </div>
  );
}

/**
 * "Right now": the session running on the machine and what the owner has earned from it so far, as the server last
 * said (read again every 5 seconds, never counted up here); never who it's for.
 */
export function RightNow({ host }: { host: Host }) {
  const session = host.currentSession;
  return (
    <Tile as="section" aria-labelledby="right-now" className="flex flex-col gap-2">
      <h2 id="right-now" className="text-small font-semibold text-text-2">
        Right now
      </h2>
      {session ? (
        <>
          <p className="text-option font-semibold">
            {session.runningLabel} · <Elapsed from={session.startedAt} />
          </p>
          <Money paise={session.earnedSoFarPaise} className="block text-title" />
          <p className="text-small text-text-2">earned so far · you keep {100 - COMMISSION_PERCENT}%</p>
        </>
      ) : (
        <p className="text-small text-text-2">
          Nothing is running. You earn as soon as a renter&apos;s job starts here.
        </p>
      )}
    </Tile>
  );
}

/**
 * What every owner is told on their machine's screen: who they are stays private, and so do their files; and how much of
 * their upload a job may use (ADR 0038), always a line, so a speed check arriving moves nothing.
 */
export function PrivacyLine({ host }: { host: Host }) {
  return (
    <div className="flex flex-col gap-1.5 text-small text-text-2">
      <p>Renters never see who you are, and their work never touches your files.</p>
      <p>{uploadLine(host.uploadCapKbps)}</p>
    </div>
  );
}
