import type { Session } from "@/api/queries";
import { Tile } from "@/components/ui/Tile";
import type { Stop } from "@/features/flow/Route";
import { progressStops } from "@/features/flow/progress";
import { useNow } from "@/lib/clock";
import { cn } from "@/lib/cn";
import type { InputFact } from "@/lib/inputs";
import type { LogSignals } from "@/lib/log-signals";

/**
 * How far it has got: four columns (uploaded, GPU found, running, outputs), each a 6 px bar, a title and a line
 * (approved reference `Session`). A step done is a full bar; the running one fills by progress,
 * the job's own `step 84/120` when its log says one, else how much of the template's typical time has passed, else it
 * is only marked as under way; the rest are empty. All of it the route's words (src/features/flow/progress.ts).
 */
export function StepsTile({
  session,
  signals,
  typicalRuntimeSeconds,
  inputs,
}: {
  session: Session;
  signals: LogSignals;
  typicalRuntimeSeconds: number | null | undefined;
  /** Every input, with its upload's size once read. */
  inputs: InputFact[];
}) {
  const now = useNow("half-minute");
  const stops = progressStops(session, {
    now,
    typicalRuntimeSeconds,
    inputs,
  });
  const elapsed = session.startedAt ? (now - Date.parse(session.startedAt)) / 1000 : null;
  const percent = (stop: Stop): number | null => {
    if (stop.state !== "current") {
      return null;
    }
    if (signals.step && signals.step.of > 0) {
      return Math.min(100, Math.floor((signals.step.at * 100) / signals.step.of));
    }
    return elapsed !== null && typicalRuntimeSeconds
      ? Math.min(95, Math.max(0, Math.floor((elapsed * 100) / typicalRuntimeSeconds)))
      : null;
  };
  return (
    <Tile as="section" aria-label="Progress" size="lg">
      <ol className="grid cols-steps gap-4">
        {stops.map((stop, index) => {
          const fill = percent(stop);
          const running = stop.title === "Running" && stop.state === "current";
          const detail =
            running && signals.step ? `step ${signals.step.at} of ${signals.step.of}` : (stop.detail ?? "");
          return (
            <li
              key={index}
              aria-current={stop.state === "current" ? "step" : undefined}
              className="flex flex-col gap-2"
            >
              <span aria-hidden="true" className="relative h-1.5 overflow-hidden rounded-full bg-line">
                <span
                  className={cn(
                    "absolute inset-y-0 left-0 rounded-full bg-progress",
                    stop.state === "done" && "w-full",
                    stop.state === "current" && fill === null && "w-full opacity-35",
                  )}
                  style={stop.state === "current" && fill !== null ? { width: `${fill}%` } : undefined}
                />
              </span>
              <span
                className={cn(
                  "text-small font-bold",
                  (stop.state === "next" || stop.state === "end") && "text-text-2",
                )}
              >
                {stop.title}
              </span>
              <span className="text-caption text-text-2">{detail}</span>
            </li>
          );
        })}
      </ol>
    </Tile>
  );
}
