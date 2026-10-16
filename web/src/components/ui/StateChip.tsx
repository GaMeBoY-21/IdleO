import { cn } from "@/lib/cn";
import { sessionState, type Tone } from "@/lib/labels";

const tones: Record<Tone, string> = {
  live: "bg-running-soft text-running",
  success: "bg-ground text-text",
  danger: "bg-danger-soft text-danger",
  warning: "bg-warning-soft text-warning",
  neutral: "bg-ground text-text-muted",
};

/**
 * A session's state, always in words; the colour only repeats it (web/DESIGN.md "Colour"). While it's RUNNING its dot
 * pulses slowly, the one continuous motion allowed, and none under reduced motion.
 */
export function StateChip({ state }: { state: string }) {
  const { label, tone } = sessionState(state);
  return (
    <span
      data-testid="state-chip"
      className={cn(
        "inline-flex items-center gap-2 rounded-full px-3 py-1.5 text-small font-semibold whitespace-nowrap",
        tones[tone],
      )}
    >
      {tone === "live" && (
        <span
          aria-hidden="true"
          data-pulse={state === "RUNNING" ? "" : undefined}
          className={cn("size-2 rounded-full bg-running", state === "RUNNING" && "motion-safe:animate-pulse")}
        />
      )}
      {label}
    </span>
  );
}
