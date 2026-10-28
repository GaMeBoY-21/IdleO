import type { components } from "@/api/schema.gen";
import { useAvailability } from "@/api/queries";
import { Tile } from "@/components/ui/Tile";
import { cn } from "@/lib/cn";
import { formatPaiseRange } from "@/lib/money";
import { classMb, GPU_CLASSES_GB } from "@/features/book/vram";

type Gpu = components["schemas"]["GpuAvailability"];

/** How often the figures are read again while the tab is visible. */
export const AVAILABILITY_EVERY_MS = 30_000;

export interface ClassRow {
  gb: number;
  free: number;
  /** Paise a minute, the cheapest and the dearest among the GPUs of the class that are online. */
  range: { min: number; max: number } | null;
}

/**
 * The GPUs by class (8, 12, 16, 24 GB): each GPU counts toward the largest class its memory covers; a class's free
 * count is the idle hosts of its GPUs, its price the range of theirs. A class with no GPU online isn't listed, and
 * a GPU below the smallest class is left out. Only what the API said.
 */
export function classRows(gpus: Gpu[]): ClassRow[] {
  return GPU_CLASSES_GB.flatMap((gb, index) => {
    const upper = GPU_CLASSES_GB[index + 1];
    const of = gpus.filter(
      (gpu) => gpu.vramMb >= classMb(gb) && (upper === undefined || gpu.vramMb < classMb(upper)),
    );
    if (of.every((gpu) => gpu.hostsOnline === 0)) {
      return [];
    }
    const idle = of.filter((gpu) => gpu.hostsIdle > 0);
    return [
      {
        gb,
        free: idle.reduce((sum, gpu) => sum + gpu.hostsIdle, 0),
        range:
          idle.length === 0
            ? null
            : {
                min: Math.min(...idle.map((gpu) => gpu.pricePaisePerMin.min)),
                max: Math.max(...idle.map((gpu) => gpu.pricePaisePerMin.max)),
              },
      },
    ];
  });
}

/**
 * "GPUs free right now", a panel: a row for each class with how
 * many are free and their price an hour; a class with none free is dimmed. It is read again every 30 s while the tab is
 * visible, and not at all until the screen has painted. With nothing to show it isn't drawn, and nothing is invented.
 */
export function FreeGpus({ enabled }: { enabled: boolean }) {
  const availability = useAvailability(enabled, AVAILABILITY_EVERY_MS);
  const rows = availability.data ? classRows(availability.data.gpus) : [];
  if (availability.data && rows.length === 0) {
    return null;
  }
  return (
    <Tile size="lg" aria-labelledby="free-gpus" className="flex min-h-0 flex-1 flex-col gap-3">
      <div className="flex items-center justify-between text-caption text-text-2">
        <h2
          id="free-gpus"
          className="flex items-center gap-2 text-caption font-bold tracking-wider uppercase"
        >
          <span aria-hidden="true" className="size-2 rounded-full bg-running-dot" />
          GPUs free right now
        </h2>
        <span>live</span>
      </div>
      <ul className="flex flex-1 flex-col">
        {availability.data ? (
          rows.map((row) => (
            <li
              key={row.gb}
              className={cn(
                "flex flex-1 items-center gap-3 border-b border-line py-2 text-body tabular last:border-b-0",
                row.free === 0 && "text-text-2",
              )}
            >
              <span className="w-16 font-bold">{row.gb} GB</span>
              <span className="flex-1">{row.free === 0 ? "none free" : `${row.free} free`}</span>
              <span className={cn(row.free > 0 && "font-bold text-running")}>
                {row.range ? `${formatPaiseRange(row.range.min * 60, row.range.max * 60)}/h` : "—"}
              </span>
            </li>
          ))
        ) : (
          <li aria-hidden="true" className="h-24 skeleton rounded-control" />
        )}
      </ul>
    </Tile>
  );
}
