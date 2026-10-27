import { ChevronRight } from "lucide-react";
import { Link } from "wouter";
import type { Host } from "@/api/hosting";
import { cn } from "@/lib/cn";
import { useNow } from "@/lib/clock";
import { machineState } from "@/lib/hosting";

/**
 * One machine on the Hosting tab (docs/design/hosting/hosting-phone.html): its name, and its state in words. One that's
 * running a session is black and its dot pulses, as Home's bar is; the others are grey. The whole row opens it.
 */
export function MachineRow({ host }: { host: Host }) {
  const now = useNow("half-minute");
  const state = machineState(host, now);
  const running = state.kind === "running";
  return (
    <Link
      href={`/hosting/machine/?id=${host.id}`}
      className={cn(
        "flex min-h-18 items-center gap-3.5 rounded-panel px-4.5 py-3.5",
        "tile-motion",
        running ? "bg-night text-on-night" : state.kind === "idle" ? "bg-ember text-ink" : "bg-panel",
      )}
    >
      <span
        aria-hidden="true"
        className={cn(
          "size-3 shrink-0 rounded-full",
          running
            ? "bg-running-dot ring-4 ring-volt/25 motion-safe:animate-pulse"
            : state.active
              ? "bg-running"
              : "bg-control",
        )}
      />
      <span className="flex min-w-0 flex-1 flex-col gap-0.5">
        <span className="text-body font-semibold break-words">{host.name}</span>
        <span
          className={cn(
            "text-caption tabular",
            running ? "text-on-night-2" : state.kind === "idle" ? "text-ink" : "text-text-2",
          )}
        >
          {state.detail}
        </span>
      </span>
      <ChevronRight aria-hidden="true" className="size-5 shrink-0" strokeWidth={2} />
    </Link>
  );
}
