import { forwardRef, useId, type InputHTMLAttributes, type ReactNode } from "react";
import { cn } from "@/lib/cn";

/**
 * A labelled input: a grey field, no outline (web/DESIGN.md "Shapes"), with room for its error, so a message
 * appearing moves nothing (web/DESIGN.md "Motion"). An error rings the field in red.
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
          "min-h-14 rounded-control bg-fill px-4 text-body text-text",
          "placeholder:text-text-muted",
          error && "ring-2 ring-danger",
        )}
        {...props}
      />
      <p id={messageId} className={cn("min-h-4.5 text-caption", error ? "text-danger" : "text-text-muted")}>
        {error ?? hint ?? ""}
      </p>
    </div>
  );
});
