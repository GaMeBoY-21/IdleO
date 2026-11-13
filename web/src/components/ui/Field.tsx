import { forwardRef, useId, type InputHTMLAttributes, type ReactNode } from "react";
import { cn } from "@/lib/cn";

/**
 * A labelled input: a field with no outline (web/DESIGN.md "Shapes"), pressed into its tile with the page's colour,
 * with room for its error, so a message appearing moves nothing (web/DESIGN.md "Motion"). An error rings the field
 * in red.
 */
export const Field = forwardRef<
  HTMLInputElement,
  InputHTMLAttributes<HTMLInputElement> & {
    label: string;
    /** For screen readers only, where a heading above already names the field. */
    hideLabel?: boolean;
    error?: string | undefined;
    hint?: ReactNode;
  }
>(function Field({ label, hideLabel = false, error, hint, className, ...props }, ref) {
  const id = useId();
  const messageId = `${id}-message`;
  return (
    <div className={cn("flex flex-col gap-2", className)}>
      <label htmlFor={id} className={hideLabel ? "sr-only" : "text-small font-medium"}>
        {label}
      </label>
      <input
        ref={ref}
        id={id}
        aria-invalid={error ? true : undefined}
        aria-describedby={messageId}
        className={cn(
          "min-h-control rounded-control border border-control bg-surface-1 px-button text-body text-ink pointer-coarse:min-h-touch pointer-coarse:text-card",
          "placeholder:text-ink-tertiary",
          error && "border-danger ring-1 ring-danger",
        )}
        {...props}
      />
      <p id={messageId} className={cn("min-h-4.5 text-caption", error ? "text-danger" : "text-ink-subtle")}>
        {error ?? hint ?? ""}
      </p>
    </div>
  );
});
