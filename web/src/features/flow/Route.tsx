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
 * solid. `across`: left to right, for the live session's panel on a laptop (docs/design/redesign/live-laptop.html),
 * where the line is solid only behind what's done and the end is hollow until it's reached.
 */
export function Route({
  stops,
  compact = false,
  across = false,
}: {
  stops: Stop[];
  compact?: boolean;
  across?: boolean;
}) {
  if (across) {
    return <RouteAcross stops={stops} />;
  }
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
              {/* Compact, it's a one-glance recap (Book's summary on a phone): one line, so it never changes height. */}
              <span
                className={cn("font-semibold", compact ? "truncate text-small" : "text-option break-words")}
              >
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

function RouteAcross({ stops }: { stops: Stop[] }) {
  return (
    <ol className="flex">
      {stops.map((stop, index) => {
        const last = index === stops.length - 1;
        return (
          <li key={index} className="flex min-w-0 flex-1 basis-0 flex-col gap-3">
            <div aria-hidden="true" className="flex h-5.5 items-center">
              {stop.state === "end" ? (
                <span className="size-4.5 border-4 border-divider" />
              ) : stop.state === "current" ? (
                <span className="size-5.5 shrink-0 rounded-full bg-running ring-6 ring-running-soft" />
              ) : (
                <Marker state={stop.state} compact={false} />
              )}
              {!last && (
                <span className={cn("h-1 flex-1", stop.state === "done" ? "bg-text" : "bg-divider")} />
              )}
            </div>
            <div className="flex flex-col gap-1 pr-3">
              <span className={cn("text-body font-semibold", stop.state === "end" && "text-text-muted")}>
                {stop.title}
              </span>
              {stop.detail && <span className="text-small text-text-muted">{stop.detail}</span>}
            </div>
          </li>
        );
      })}
    </ol>
  );
}
