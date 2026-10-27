import { cn } from "@/lib/cn";
import type { MachineKind, MachineState } from "@/lib/hosting";

const tones: Record<MachineKind, string> = {
  running: "bg-ok text-running",
  idle: "bg-panel text-text",
  paused: "bg-warn text-warn-fg",
  update: "bg-warn text-warn-fg",
  suspended: "bg-danger-soft text-danger",
  offline: "bg-panel text-text-2",
  pending: "bg-panel text-text-2",
};

/** A machine's state, always in words; the colour only repeats it. A running one's dot pulses (web/DESIGN.md "Motion"). */
export function MachineChip({ state }: { state: MachineState }) {
  return (
    <span
      data-testid="machine-chip"
      className={cn(
        "inline-flex items-center gap-2 self-start rounded-full px-3 py-1.5 text-small font-semibold whitespace-nowrap",
        tones[state.kind],
      )}
    >
      {state.kind === "running" && (
        <span aria-hidden="true" className="size-2 rounded-full bg-running motion-safe:animate-pulse" />
      )}
      {state.label}
    </span>
  );
}
