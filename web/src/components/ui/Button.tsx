import type { ButtonHTMLAttributes, ReactNode } from "react";
import { cn } from "@/lib/cn";

/**
 * Buttons (web/DESIGN.md "Buttons"): 14 px corners, and one primary per screen: volt at night, the page's ink with volt text
 * by day.
 * - `primary`: the screen's one next step;
 * - `secondary`: ion-deep, white text, for a weighty second choice like "Stop session";
 * - `ghost`: the raised fill (white with an edge by day), for everyday actions; inside a tile it is pressed in with the tile's own step;
 * - `live`: volt in either theme, the one button that sits inside a night tile ("Request a GPU");
 * - `ink`: the page's dark with volt text in either theme, the one button that sits inside a volt tile ("Download outputs");
 * - `quiet`: a bold text action, like "Change";
 * - `danger`: confirming something destructive, in the one dialog.
 * `large` is the 56 px button at the foot of a flow (60 px on a laptop). Every button is at least 44 px tall. A link
 * that looks like a button uses buttonClasses(). A pressed button that's waiting shows a small spinner inside it.
 */
export type ButtonVariant = "primary" | "secondary" | "ghost" | "live" | "ink" | "quiet" | "danger";

const variants: Record<ButtonVariant, string> = {
  primary: "bg-primary font-bold text-primary-fg hover:bg-primary-hover",
  secondary: "bg-ion-deep text-on-ion-deep hover:bg-ion-deep-hover",
  ghost:
    "border border-edge bg-ghost text-text hover:bg-ghost-hover tiled:bg-panel-2 tiled:hover:bg-ghost-hover",
  live: "bg-volt font-bold text-ink hover:bg-volt-hover",
  ink: "bg-ink font-bold text-volt hover:bg-ink/85",
  quiet: "text-text underline-offset-4 hover:underline",
  // Hovering adds a ring outside the button rather than fading it, so its text keeps its contrast.
  danger:
    "bg-danger text-danger-fg hover:ring-2 hover:ring-danger hover:ring-offset-2 hover:ring-offset-panel",
};

export function buttonClasses(variant: ButtonVariant = "primary", block = false, large = false) {
  return cn(
    "inline-flex items-center justify-center gap-2 font-semibold",
    "transition-colors duration-120 select-none disabled:cursor-not-allowed disabled:opacity-60",
    // One least height each: with both, the stylesheet's order, not this line, would decide which wins.
    large
      ? "min-h-14 rounded-control-lg px-6 text-option md:min-h-15"
      : "min-h-touch rounded-control px-4 text-small",
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
