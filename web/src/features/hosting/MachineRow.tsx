import { ChevronRight } from "lucide-react";
import { Link } from "wouter";
import type { Host } from "@/api/hosting";
import { cn } from "@/lib/cn";
import { useNow } from "@/lib/clock";
import { machineState } from "@/lib/hosting";
import { laptopPath } from "@/sites/host/paths";

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
      href={laptopPath(host.id)}
      className={cn(
        "flex min-h-18 items-center gap-4 rounded-card px-4 py-4",
        "tile-motion",
        "border border-hairline",
        running ? "bg-surface-2" : "bg-surface-1",
      )}
    >
      <span
        aria-hidden="true"
        className={cn(
          "size-3 shrink-0 rounded-full",
          running
            ? "bg-live ring-4 ring-live/25 motion-safe:animate-pulse"
            : state.active
              ? "bg-live"
              : "bg-control",
        )}
      />
      <span className="flex min-w-0 flex-1 flex-col gap-1">
        <span className="text-body font-semibold break-words">{host.name}</span>
        <span className={cn("text-caption tabular", "text-ink-subtle")}>{state.detail}</span>
      </span>
      <ChevronRight aria-hidden="true" className="size-5 shrink-0" strokeWidth={2} />
    </Link>
  );
}
