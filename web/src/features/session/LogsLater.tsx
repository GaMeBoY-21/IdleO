import { ScrollText } from "lucide-react";
import { lazy, Suspense } from "react";
import { useAfterPaint } from "@/lib/after-first-screen";

const Logs = lazy(() => import("./Logs").then((module) => ({ default: module.Logs })));

/**
 * A session's logs, whose code (the log panel and its reader) is fetched only once the screen has painted, so the
 * session's own figures never wait for it (web/README.md "The budgets"). Until then, the panel or the phone's row
 * is there at its own size, so nothing moves when the logs arrive.
 */
export function LogsLater({ id, settled, laptop }: { id: string; settled: boolean; laptop: boolean }) {
  const painted = useAfterPaint();
  const placeholder = laptop ? (
    <section
      aria-label="Logs"
      className="flex min-h-0 flex-1 flex-col gap-4 rounded-panel bg-log-ground px-7 py-6 text-log-text"
    >
      <h2 className="text-body font-semibold text-log-text">Logs</h2>
    </section>
  ) : (
    <div className="flex min-h-14 items-center gap-3 border-b border-divider py-3.5 text-body">
      <ScrollText aria-hidden="true" className="size-5 shrink-0" strokeWidth={1.75} />
      <span className="font-semibold">Logs</span>
    </div>
  );
  return painted ? (
    <Suspense fallback={placeholder}>
      <Logs id={id} settled={settled} laptop={laptop} />
    </Suspense>
  ) : (
    placeholder
  );
}
