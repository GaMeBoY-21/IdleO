import type { ButtonHTMLAttributes, ReactNode } from "react";
import { cn } from "@/lib/cn";

/**
 * Buttons (web/DESIGN.md principle 1): one primary per screen, then secondary and quiet. Every button is at least
 * 44 px tall. A link that looks like a button uses buttonClasses(). A pressed button that's waiting shows a small spinner inside it and stays the same size.
 */
export type ButtonVariant = "primary" | "secondary" | "quiet" | "danger";

const variants: Record<ButtonVariant, string> = {
  primary: "bg-accent text-accent-fg hover:bg-accent-hover",
  secondary: "border border-border-strong bg-surface text-text hover:bg-sunken",
  quiet: "text-accent-text hover:bg-accent-soft",
  danger: "bg-danger text-danger-fg hover:opacity-90",
};

export function buttonClasses(variant: ButtonVariant = "primary", block = false) {
  return cn(
    "inline-flex min-h-touch items-center justify-center gap-2 rounded-control px-4 text-small font-medium",
    "transition-colors duration-120 select-none disabled:cursor-not-allowed disabled:opacity-60",
    variants[variant],
    block && "w-full",
  );
}

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: ButtonVariant;
  block?: boolean;
  busy?: boolean;
  children: ReactNode;
}

export function Button({
  variant = "primary",
  block = false,
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
      className={cn(buttonClasses(variant, block), className)}
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
