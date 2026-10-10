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
