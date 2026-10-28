import { ScrollText } from "lucide-react";
import { lazy, Suspense, type ReactNode } from "react";
import { Tile } from "@/components/ui/Tile";
import { useAfterPaint } from "@/lib/after-first-screen";

const Logs = lazy(() => import("./Logs").then((module) => ({ default: module.Logs })));

/**
 * A session's logs, whose code (the log panel and its reader) is fetched only once the screen has painted, so the
 * session's own figures never wait for it (web/README.md "The budgets"). Until then, the panel or the phone's row
 * is there at its own size, so nothing moves when the logs arrive. On a laptop the log is the right column of a pair of
 * panels, and `side` (the GPU's panel) the left one, over the loss chart the log may add.
 */
export function LogsLater({
  id,
  settled,
  laptop,
  side = null,
}: {
  id: string;
  settled: boolean;
  laptop: boolean;
  side?: ReactNode;
}) {
  const painted = useAfterPaint();
  const placeholder = laptop ? (
    <div className="grid cols-panels items-stretch gap-4">
      <div className="flex min-w-0 flex-col gap-4">{side}</div>
      <Tile
        as="section"
        aria-label="Logs"
        className="flex min-h-session-row flex-col gap-4 px-6 py-5 xl:px-7"
      >
        <h2 className="text-section">Logs</h2>
      </Tile>
    </div>
  ) : (
    <div className="flex min-h-14 items-center gap-3 rounded-panel bg-panel px-4.5 py-3.5 text-body">
      <ScrollText aria-hidden="true" className="size-5 shrink-0" strokeWidth={1.75} />
      <span className="font-semibold">Logs</span>
    </div>
  );
  return painted ? (
    <Suspense fallback={placeholder}>
      <Logs id={id} settled={settled} laptop={laptop} side={side} />
    </Suspense>
  ) : (
    placeholder
  );
}
