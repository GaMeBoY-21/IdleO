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
          <li key={index} className={cn("flex", compact ? "gap-4" : "gap-4")}>
            <div
              aria-hidden="true"
              className={cn("flex shrink-0 flex-col items-center", compact ? "w-4" : "w-5.5")}
            >
              <Marker state={stop.state} compact={compact} />
              {!last && <span className={cn("flex-1 bg-text", compact ? "min-h-4 w-0.5" : "min-h-10 w-1")} />}
            </div>
            <div className={cn("flex min-w-0 flex-col gap-1", !last && (compact ? "pb-4" : "pb-8"))}>
              {/* Compact, it's a one-glance recap (Book's summary on a phone): one line, so it never changes height. */}
              <span
                className={cn("font-semibold", compact ? "truncate text-small" : "text-card break-words")}
              >
                {stop.title}
              </span>
              {stop.detail && (
                <span className={cn("text-ink-subtle", compact ? "text-caption" : "text-small")}>
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
    return <span className={cn("bg-text", compact ? "mt-1 size-3.5" : "size-4.5")} />;
  }
  if (state === "done") {
    return (
      <span
        className={cn(
          "flex items-center justify-center rounded-full bg-text text-canvas",
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
        state === "current" ? "border-live bg-surface-3" : "border-ink bg-canvas",
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
          <li key={index} className="flex min-w-0 flex-1 basis-0 flex-col gap-2">
            <div aria-hidden="true" className="flex h-5.5 items-center">
              {stop.state === "end" ? (
                <span className="size-4.5 border-4 border-hairline" />
              ) : stop.state === "current" ? (
                <span className="size-5.5 shrink-0 rounded-full bg-live ring-6 ring-surface-3" />
              ) : (
                <Marker state={stop.state} compact={false} />
              )}
              {!last && (
                <span className={cn("h-1 flex-1", stop.state === "done" ? "bg-text" : "bg-hairline")} />
              )}
            </div>
            <div className="flex flex-col gap-1 pr-4">
              <span className={cn("text-body font-semibold", stop.state === "end" && "text-ink-subtle")}>
                {stop.title}
              </span>
              {stop.detail && <span className="text-small text-ink-subtle">{stop.detail}</span>}
            </div>
          </li>
        );
      })}
    </ol>
  );
}
