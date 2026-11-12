import type { ReactNode } from "react";
import { cn } from "@/lib/cn";
import { stateOf, type StateDomain, type Tone } from "@/lib/states";

/**
 * A state as a chip (web/DESIGN.md "States and chips"), from the one map in src/lib/states.ts: its words, its tone's
 * colour and its tone's mark (a pulsing dot for live, a tick, a warning sign, a cross, a ring), so a state never rests on
 * colour alone. `label` replaces the map's words only where a screen says more of the same state ("Paused until 6 pm");
 * `exact` takes the console's words. A label that isn't a state is a `Tag`.
 */
const TONES: Record<Tone, string> = {
  live: "bg-ok text-running",
  success: "bg-raised text-text",
  warning: "bg-warn text-warn-fg",
  danger: "bg-danger-soft text-danger",
  neutral: "bg-raised text-text-2",
};

function Mark({ tone }: { tone: Tone }) {
  if (tone === "live") {
    return (
      <span
        aria-hidden="true"
        className="size-2 shrink-0 rounded-full bg-running motion-safe:animate-pulse"
      />
    );
  }
  const path = {
    success: "M3 8.5l3 3 7-7",
    warning: "M8 2.5l6 11H2zM8 7v3M8 12v.01",
    danger: "M4 4l8 8M12 4l-8 8",
    neutral: "",
  }[tone];
  return (
    <svg
      aria-hidden="true"
      viewBox="0 0 16 16"
      className="size-3 shrink-0"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
    >
      {tone === "neutral" ? (
        <circle cx="8" cy="8" r="4.5" />
      ) : (
        <path d={path} strokeLinecap="round" strokeLinejoin="round" />
      )}
    </svg>
  );
}

export function StatusChip({
  domain,
  state,
  label,
  exact = false,
  size = "md",
  testId = "state-chip",
}: {
  domain: StateDomain;
  state: string;
  label?: ReactNode;
  exact?: boolean;
  /** `md` for a screen's own state, `sm` in a table's row. */
  size?: "md" | "sm";
  testId?: string;
}) {
  const word = stateOf(domain, state);
  return (
    <span
      data-testid={testId}
      data-tone={word.tone}
      className={cn(
        "inline-flex items-center gap-1 self-start rounded-full font-semibold whitespace-nowrap",
        size === "md" ? "px-2 py-1 text-small" : "px-2 text-caption",
        TONES[word.tone],
      )}
    >
      <Mark tone={word.tone} />
      {label ?? (exact ? (word.exact ?? word.label) : word.label)}
    </span>
  );
}

/** A label that isn't a state ("Pinned", "A law sets this", an admin's tag), always neutral. */
export function Tag({ children }: { children: ReactNode }) {
  return (
    <span className="inline-flex items-center self-start rounded-full bg-raised px-2 text-caption font-medium whitespace-nowrap text-text">
      {children}
    </span>
  );
}
