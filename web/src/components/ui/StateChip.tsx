import { cn } from "@/lib/cn";
import { sessionState, type Tone } from "@/lib/labels";

const tones: Record<Tone, string> = {
  live: "bg-accent-soft text-accent-text",
  success: "bg-success-soft text-success",
  danger: "bg-danger-soft text-danger",
  warning: "bg-warning-soft text-warning",
  neutral: "bg-sunken text-text-muted",
};

/** A session's state, always in words; the colour only repeats it (web/DESIGN.md "Colour"). */
export function StateChip({ state }: { state: string }) {
  const { label, tone } = sessionState(state);
  return (
    <span
      className={cn(
        "inline-flex items-center gap-2 rounded-full px-3 py-1 text-caption font-medium whitespace-nowrap",
        tones[tone],
      )}
    >
      {tone === "live" && (
        <span aria-hidden="true" className="size-2 rounded-full bg-accent motion-safe:animate-pulse" />
      )}
      {label}
    </span>
  );
}
