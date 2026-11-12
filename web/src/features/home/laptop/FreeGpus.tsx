import type { components } from "@/api/schema.gen";
import { useAvailability } from "@/api/queries";
import { Tile } from "@/components/ui/Tile";
import { cn } from "@/lib/cn";
import { siteLink } from "@/lib/site-links";
import { PRICING } from "@/site/paths";
import { formatPaiseRange } from "@/lib/money";
import { classMb, GPU_CLASSES_GB } from "@/features/book/vram";

type Gpu = components["schemas"]["GpuAvailability"];

/** How often the figures are read again while the tab is visible. */
export const AVAILABILITY_EVERY_MS = 30_000;

export interface ClassRow {
  gb: number;
  /** Hosts of the class online now, and those of them free. */
  online: number;
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
        online: of.reduce((sum, gpu) => sum + gpu.hostsOnline, 0),
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
 * "GPUs online now", a panel: a row for each size (8, 12, 16, 24 GB) with how many are online and free, and their price
 * an hour; a size with none free is dimmed. It is read again every 30 s while the tab is visible, and not at all until
 * the screen has painted. With none online it says so, and nothing is invented.
 */
export function FreeGpus({ enabled }: { enabled: boolean }) {
  const availability = useAvailability(enabled, AVAILABILITY_EVERY_MS);
  const rows = availability.data ? classRows(availability.data.gpus) : [];
  return (
    <Tile size="lg" aria-labelledby="free-gpus" className="flex min-h-0 flex-1 flex-col gap-2">
      <div className="flex items-center justify-between text-caption text-text-2">
        <h2
          id="free-gpus"
          className="flex items-center gap-2 text-caption font-bold tracking-wider uppercase"
        >
          <span aria-hidden="true" className="size-2 rounded-full bg-running-dot" />
          GPUs online now
        </h2>
        <span>live</span>
      </div>
      <ul className="flex flex-1 flex-col">
        {availability.data && rows.length === 0 ? (
          <li className="flex flex-1 flex-col justify-center gap-1 text-small text-text-2">
            <span className="text-body font-semibold text-text">No GPU is online right now</span>
            <span>
              Hosts&apos; laptops come and go through the day; this updates by itself.{" "}
              <a href={siteLink(PRICING)} className="font-semibold underline">
                Typical prices by size
              </a>
            </span>
          </li>
        ) : availability.data ? (
          rows.map((row) => (
            <li
              key={row.gb}
              className={cn(
                "flex flex-1 items-center gap-2 border-b border-line py-2 text-body tabular last:border-b-0",
                row.free === 0 && "text-text-2",
              )}
            >
              <span className="w-16 font-bold">{row.gb} GB</span>
              <span className="flex-1">
                {row.online} online · {row.free === 0 ? "none free" : `${row.free} free`}
              </span>
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
