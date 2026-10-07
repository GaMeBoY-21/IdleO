import { forwardRef, useId, type InputHTMLAttributes } from "react";
import { cn } from "@/lib/cn";

/** A labelled input with room for its error, so a message appearing moves nothing (web/DESIGN.md "Motion"). */
export const Field = forwardRef<
  HTMLInputElement,
  InputHTMLAttributes<HTMLInputElement> & { label: string; error?: string | undefined; hint?: string }
>(function Field({ label, error, hint, className, ...props }, ref) {
  const id = useId();
  const messageId = `${id}-message`;
  return (
    <div className={cn("flex flex-col gap-2", className)}>
      <label htmlFor={id} className="text-small font-medium">
        {label}
      </label>
      <input
        ref={ref}
        id={id}
        aria-invalid={error ? true : undefined}
        aria-describedby={messageId}
        className={cn(
          "min-h-touch rounded-control border bg-surface px-3 text-body text-text",
          "placeholder:text-text-muted",
          error ? "border-danger" : "border-border-strong",
        )}
        {...props}
      />
      <p id={messageId} className={cn("min-h-4 text-caption", error ? "text-danger" : "text-text-muted")}>
        {error ?? hint ?? ""}
      </p>
    </div>
  );
});
