import type { Session } from "@/api/queries";
import { Tile } from "@/components/ui/Tile";
import { RelativeTime } from "@/components/ui/Time";
import { gigabytes, gpuName } from "@/lib/gpu";

/**
 * The GPU, as the white tile under the dark one: its model and memory, and two inset stats, how busy and how hot it
 * is, from its host's heartbeats (§0.4 of the API), "—" when they're stale. Never the host's identity: it says whose
 * it is only as "someone's gaming laptop".
 */
export function GpuStats({ gpu }: { gpu: NonNullable<Session["gpu"]> }) {
  return (
    <Tile size="lg" className="flex flex-col gap-3 xl:p-6">
      <span className="flex items-baseline justify-between gap-3 text-caption text-text-2">
        <span>The GPU</span>
        <span>someone&apos;s gaming laptop</span>
      </span>
      <span className="text-section">
        {gpuName(gpu.model)} · {gigabytes(gpu.vramMb)}
      </span>
      <div className="grid grid-cols-2 gap-2.5">
        <Stat label="Busy" value={gpu.utilPct == null ? "—" : `${gpu.utilPct}%`} />
        <Stat label="Temperature" value={gpu.tempC == null ? "—" : `${gpu.tempC}°C`} />
      </div>
      <p className="sr-only">
        {gpu.utilPct == null || gpu.tempC == null
          ? "Reading its use…"
          : `${gpu.utilPct}% busy · ${gpu.tempC}°C`}
      </p>
    </Tile>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-control bg-canvas px-3.5 py-3">
      <span className="block text-caption text-text-2">{label}</span>
      <span className="text-figure tabular">{value}</span>
    </div>
  );
}

/** The checkpoints, the lime tile: how many are saved and when the last one was. */
export function CheckpointsTile({ session }: { session: Session }) {
  const saved = session.checkpoints.length;
  const last = session.checkpoints.at(-1);
  return (
    <Tile tone="volt" size="lg" className="flex items-center justify-between gap-3 xl:p-6">
      <span className="flex flex-col">
        <span className="text-caption text-ink">Checkpoints</span>
        <span className="text-section tabular">{saved === 0 ? "None saved yet" : `${saved} saved`}</span>
      </span>
      {last && (
        <span className="text-caption text-ink">
          Last checkpoint saved <RelativeTime value={last.completedAt} />
        </span>
      )}
    </Tile>
  );
}
