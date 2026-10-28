import type { Session } from "@/api/queries";
import { Tile } from "@/components/ui/Tile";
import { RelativeTime } from "@/components/ui/Time";
import { gigabytes, gpuName } from "@/lib/gpu";
import type { LogSignals } from "@/lib/log-signals";

/**
 * The GPU and the numbers about the run, as a panel beside the log (approved reference `Session`): its model and memory,
 * and four small tiles pressed into it: how busy and how hot the GPU is (from its host's heartbeats, "—" when they're
 * stale; §0.4 of the API), how many checkpoints are saved, and the step the job's log says it is at. Never the host's
 * identity: it says whose it is only as "someone's gaming laptop".
 */
export function StatsPanel({ session, signals }: { session: Session; signals: LogSignals }) {
  const gpu = session.gpu;
  const saved = session.checkpoints.length;
  const last = session.checkpoints.at(-1);
  return (
    <Tile as="section" aria-label="The GPU" size="lg" className="flex flex-col gap-3.5">
      {gpu && (
        <>
          <span className="flex items-baseline justify-between gap-3 text-caption text-text-2">
            <span>The GPU</span>
            <span>someone&apos;s gaming laptop</span>
          </span>
          <span className="text-section">
            {gpuName(gpu.model)} · {gigabytes(gpu.vramMb)}
          </span>
        </>
      )}
      <div className="grid grid-cols-2 gap-2.5 xl:grid-cols-4">
        {gpu && (
          <>
            <Stat label="GPU busy" value={gpu.utilPct == null ? "—" : `${gpu.utilPct}%`} />
            <Stat label="Temperature" value={gpu.tempC == null ? "—" : `${gpu.tempC}°C`} />
          </>
        )}
        <Stat label="Checkpoints" value={saved === 0 ? "None yet" : String(saved)} />
        {signals.step && <Stat label="Step" value={`${signals.step.at}/${signals.step.of}`} />}
      </div>
      {last && (
        <p className="text-caption text-text-2">
          Last checkpoint saved <RelativeTime value={last.completedAt} />
        </p>
      )}
      {gpu && (
        <p className="sr-only">
          {gpu.utilPct == null || gpu.tempC == null
            ? "Reading its use…"
            : `${gpu.utilPct}% busy · ${gpu.tempC}°C`}
        </p>
      )}
    </Tile>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-inner bg-panel-2 p-3.5">
      <span className="block text-caption text-text-3">{label}</span>
      <span className="text-figure tabular">{value}</span>
    </div>
  );
}
