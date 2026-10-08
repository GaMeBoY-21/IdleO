import { Check } from "lucide-react";
import type { ReactNode } from "react";
import { cn } from "@/lib/cn";

/** One stop on a session's route: done, the current one, one still to come, or where it ends. */
export interface Stop {
  title: ReactNode;
  detail?: ReactNode;
  state: "done" | "current" | "next" | "end";
}

/**
 * A session's route, top to bottom, like a ride's pickup and drop-off (web/DESIGN.md): your data, a GPU picks it
 * up, it runs, your outputs. A done stop is a filled circle with a tick, the end a square, the line between them
 * solid.
 */
export function Route({ stops, compact = false }: { stops: Stop[]; compact?: boolean }) {
  return (
    <ol className="flex flex-col">
      {stops.map((stop, index) => {
        const last = index === stops.length - 1;
        return (
          <li key={index} className={cn("flex", compact ? "gap-3.5" : "gap-5")}>
            <div
              aria-hidden="true"
              className={cn("flex shrink-0 flex-col items-center", compact ? "w-4" : "w-5.5")}
            >
              <Marker state={stop.state} compact={compact} />
              {!last && <span className={cn("flex-1 bg-text", compact ? "min-h-4 w-0.5" : "min-h-10 w-1")} />}
            </div>
            <div className={cn("flex min-w-0 flex-col gap-1", !last && (compact ? "pb-3.5" : "pb-7"))}>
              <span className={cn("font-semibold break-words", compact ? "text-small" : "text-option")}>
                {stop.title}
              </span>
              {stop.detail && (
                <span className={cn("text-text-muted", compact ? "text-caption" : "text-small")}>
                  {stop.detail}
                </span>
              )}
            </div>
          </li>
        );
      })}
    </ol>
  );
}

function Marker({ state, compact }: { state: Stop["state"]; compact: boolean }) {
  if (state === "end") {
    return <span className={cn("bg-text", compact ? "mt-0.5 size-3.5" : "size-4.5")} />;
  }
  if (state === "done") {
    return (
      <span
        className={cn(
          "flex items-center justify-center rounded-full bg-text text-ground",
          compact ? "size-4" : "size-5.5",
        )}
      >
        <Check className="size-3" strokeWidth={3.5} />
      </span>
    );
  }
  return (
    <span
      className={cn(
        "rounded-full border-4",
        state === "current" ? "border-running bg-running-soft" : "border-text bg-ground",
        compact ? "size-4" : "size-3.5",
      )}
    />
  );
}
