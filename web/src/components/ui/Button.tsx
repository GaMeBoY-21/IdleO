import { useId, type ButtonHTMLAttributes, type ReactNode } from "react";
import { cn } from "@/lib/cn";

/**
 * Buttons (web/DESIGN.md "Buttons"): 32 px tall (44 on a touch screen), 8 px corners, 14 px at weight 500, and one
 * primary per screen.
 * - `primary`: volt with canvas text, the screen's one next step;
 * - `secondary`: surface-1 with a hairline border, for the other choices;
 * - `ghost`: no fill, for everyday and quiet actions;
 * - `danger`: a ghost button with danger text; it is solid only in the confirm dialog (`solid`);
 * - `quiet`: a text action, like "Change".
 * `large` is the 40 px button at the foot of a flow. A link that looks like a button uses buttonClasses(). A pressed
 * button that's waiting shows a small spinner inside it.
 */
export type ButtonVariant = "primary" | "secondary" | "ghost" | "quiet" | "danger";

const variants: Record<ButtonVariant, string> = {
  primary: "bg-volt text-on-volt hover:bg-volt-hover active:bg-volt-pressed",
  secondary: "border border-hairline-strong bg-surface-1 text-ink hover:bg-surface-2",
  ghost: "text-ink hover:bg-surface-2",
  quiet: "text-ink underline-offset-4 hover:underline",
  danger: "text-danger hover:bg-surface-2",
};

/** The confirm dialog's danger button: solid, the only place danger is a fill. */
const SOLID_DANGER = "bg-danger text-danger-fg hover:bg-danger/90";

export function buttonClasses(
  variant: ButtonVariant = "primary",
  block = false,
  large = false,
  solid = false,
) {
  return cn(
    "inline-flex items-center justify-center gap-2 text-button",
    "transition-colors duration-press select-none disabled:cursor-not-allowed disabled:opacity-60",
    // One least height each: with both, the stylesheet's order, not this line, would decide which wins.
    large
      ? "min-h-10 rounded-control px-4 pointer-coarse:min-h-touch"
      : "min-h-control rounded-control px-button pointer-coarse:min-h-touch",
    variant === "danger" && solid ? SOLID_DANGER : variants[variant],
    block && "w-full",
  );
}

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant;
  block?: boolean;
  large?: boolean;
  /** A danger button as a fill: the confirm dialog's. */
  solid?: boolean;
  busy?: boolean;
  /** While disabled, why: shown on hover and read with the button (web/DESIGN.md "Feedback"). */
  why?: string;
  children: ReactNode;
}

export function Button({
  variant = "primary",
  block = false,
  large = false,
  solid = false,
  busy = false,
  className,
  children,
  disabled,
  why,
  type,
  ...props
}: ButtonProps) {
  const whyId = useId();
  const saysWhy = disabled === true && why !== undefined;
  return (
    <>
      <button
        type={type ?? "button"}
        className={cn(buttonClasses(variant, block, large, solid), className)}
        disabled={disabled || busy}
        aria-busy={busy || undefined}
        title={saysWhy ? why : undefined}
        aria-describedby={saysWhy ? whyId : undefined}
        {...props}
      >
        {busy && <Spinner />}
        {children}
      </button>
      {/* Beside the button, not in it: its description, never part of its name. */}
      {saysWhy && (
        <span id={whyId} className="sr-only">
          {why}
        </span>
      )}
    </>
  );
}

/** Only ever inside a pressed button (web/DESIGN.md principle 4). */
function Spinner() {
  return (
    // Shown only once a press has taken 300 ms, so a quick answer never flashes it; the button is disabled at once.
    <svg className="appear-late size-4 animate-spin" viewBox="0 0 16 16" aria-hidden="true" fill="none">
      <circle cx="8" cy="8" r="6.5" stroke="currentColor" strokeOpacity="0.3" strokeWidth="2" />
      <path d="M14.5 8A6.5 6.5 0 0 0 8 1.5" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />
    </svg>
  );
}
