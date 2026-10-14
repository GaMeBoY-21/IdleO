import type { ButtonHTMLAttributes, ReactNode } from "react";
import { cn } from "@/lib/cn";

/**
 * Buttons (web/DESIGN.md principle 1): one primary per screen, black (white in the dark theme), then the rest.
 * - `primary`: the screen's one next step;
 * - `outline`: a weighty second choice, like "Stop session" or "Cancel request" (a 2 px border);
 * - `secondary`: a grey fill, for everyday actions;
 * - `pill`: a grey pill, for small options beside something;
 * - `quiet`: a bold text action, like "Change";
 * - `danger`: confirming something destructive, in the one dialog.
 * `large` is the 56 px button at the foot of a flow (60 px on a laptop). Every button is at least 44 px tall. A link
 * that looks like a button uses buttonClasses(). A pressed button that's waiting shows a small spinner inside it.
 */
export type ButtonVariant = "primary" | "outline" | "secondary" | "pill" | "quiet" | "danger";

const variants: Record<ButtonVariant, string> = {
  primary: "rounded-control bg-primary text-primary-fg hover:bg-primary-hover",
  outline: "rounded-control border-2 border-text bg-ground text-text hover:bg-fill",
  secondary: "rounded-control bg-fill text-text hover:bg-fill-selected",
  pill: "rounded-full bg-fill text-text hover:bg-fill-selected",
  quiet: "rounded-control text-text underline-offset-4 hover:underline",
  // Hovering adds a ring outside the button rather than fading it, so its text keeps its contrast.
  danger:
    "rounded-control bg-danger text-danger-fg hover:ring-2 hover:ring-danger hover:ring-offset-2 hover:ring-offset-ground",
};

export function buttonClasses(variant: ButtonVariant = "primary", block = false, large = false) {
  return cn(
    "inline-flex items-center justify-center gap-2 font-semibold",
    "transition-colors duration-120 select-none disabled:cursor-not-allowed disabled:opacity-60",
    // One least height each: with both, the stylesheet's order, not this line, would decide which wins.
    large ? "min-h-14 px-6 text-option md:min-h-15" : "min-h-touch px-4 text-small",
    variants[variant],
    block && "w-full",
  );
}

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant;
  block?: boolean;
  large?: boolean;
  busy?: boolean;
  children: ReactNode;
}

export function Button({
  variant = "primary",
  block = false,
  large = false,
  busy = false,
  className,
  children,
  disabled,
  type,
  ...props
}: ButtonProps) {
  return (
    <button
      type={type ?? "button"}
      className={cn(buttonClasses(variant, block, large), className)}
      disabled={disabled || busy}
      aria-busy={busy || undefined}
      {...props}
    >
      {busy && <Spinner />}
      {children}
    </button>
  );
}

/** Only ever inside a pressed button (web/DESIGN.md principle 4). */
function Spinner() {
  return (
    <svg className="size-4 animate-spin" viewBox="0 0 16 16" aria-hidden="true" fill="none">
      <circle cx="8" cy="8" r="6.5" stroke="currentColor" strokeOpacity="0.3" strokeWidth="2" />
      <path d="M14.5 8A6.5 6.5 0 0 0 8 1.5" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
    </svg>
  );
}
