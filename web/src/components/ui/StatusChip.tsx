import type { ReactNode } from "react";
import { cn } from "@/lib/cn";
import { stateOf, type StateDomain, type Tone } from "@/lib/states";

/**
 * A state as a chip (web/DESIGN.md "States and chips"), from the one map in src/lib/states.ts: a surface-2 pill with 12 px
 * text, a dot in its tone's colour (a pulsing volt one for live) and its words, so a state never rests on colour alone.
 * `label` replaces the map's words only where a screen says more of the same state ("Paused until 6 pm"); `exact` takes
 * the console's words. A label that isn't a state is a `Tag`.
 */
const DOTS: Record<Tone, string> = {
  live: "bg-live",
  success: "bg-success",
  warning: "bg-warning",
  danger: "bg-danger",
  neutral: "bg-ink-tertiary",
};

/** A live state's dot pulses only while the work runs, the one continuous motion allowed (none under reduced motion). */
function Mark({ tone, running }: { tone: Tone; running: boolean }) {
  return (
    <span
      aria-hidden="true"
      data-pulse={tone === "live" && running ? "" : undefined}
      className={cn(
        "size-1.5 shrink-0 rounded-full",
        DOTS[tone],
        tone === "live" && running && "motion-safe:animate-pulse",
      )}
    />
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
        "inline-flex items-center gap-2 self-start rounded-full bg-surface-2 text-caption font-medium whitespace-nowrap text-ink",
        size === "md" ? "px-2 py-1" : "px-2",
      )}
    >
      <Mark tone={word.tone} running={state === "RUNNING"} />
      {/* In a span, so a label's own spaces ("Past its target, 2 h ago") aren't trimmed as the chip's flex items. */}
      <span>{label ?? (exact ? (word.exact ?? word.label) : word.label)}</span>
    </span>
  );
}

/** A label that isn't a state ("Pinned", "A law sets this", an admin's tag), always neutral. */
export function Tag({ children }: { children: ReactNode }) {
  return (
    <span className="inline-flex items-center self-start rounded-tag bg-surface-2 px-2 text-caption font-medium whitespace-nowrap text-ink-muted">
      {children}
    </span>
  );
}
