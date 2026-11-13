import {
  forwardRef,
  useId,
  type ReactNode,
  type SelectHTMLAttributes,
  type TextareaHTMLAttributes,
} from "react";
import { cn } from "@/lib/cn";

/**
 * The form controls beside Field (web/DESIGN.md "Space and shape"): native elements, styled with the tokens, each with
 * its label and a line kept for its help or error, so a message appearing moves nothing. Native controls carry their
 * own keyboard and screen-reader behaviour, and add no code to the bundle.
 */
const control =
  "min-h-control rounded-control border border-control bg-surface-1 px-button text-body text-ink pointer-coarse:min-h-touch pointer-coarse:text-card";

function Message({ id, error, hint }: { id: string; error?: string | undefined; hint?: ReactNode }) {
  return (
    <p id={id} className={cn("min-h-4 text-caption", error ? "text-danger" : "text-ink-subtle")}>
      {error ?? hint ?? ""}
    </p>
  );
}

export const TextArea = forwardRef<
  HTMLTextAreaElement,
  TextareaHTMLAttributes<HTMLTextAreaElement> & {
    label: string;
    hideLabel?: boolean;
    error?: string | undefined;
    hint?: ReactNode;
  }
>(function TextArea({ label, hideLabel = false, error, hint, className, ...props }, ref) {
  const id = useId();
  return (
    <div className={cn("flex flex-col gap-2", className)}>
      <label htmlFor={id} className={hideLabel ? "sr-only" : "text-small font-medium"}>
        {label}
      </label>
      <textarea
        ref={ref}
        id={id}
        rows={4}
        aria-invalid={error ? true : undefined}
        aria-describedby={`${id}-message`}
        className={cn(control, "py-2", error && "border-danger ring-1 ring-danger")}
        {...props}
      />
      <Message id={`${id}-message`} error={error} hint={hint} />
    </div>
  );
});

export const Select = forwardRef<
  HTMLSelectElement,
  SelectHTMLAttributes<HTMLSelectElement> & {
    label: string;
    hideLabel?: boolean;
    error?: string | undefined;
    hint?: ReactNode;
  }
>(function Select({ label, hideLabel = false, error, hint, className, children, ...props }, ref) {
  const id = useId();
  return (
    <div className={cn("flex flex-col gap-2", className)}>
      <label htmlFor={id} className={hideLabel ? "sr-only" : "text-small font-medium"}>
        {label}
      </label>
      <select
        ref={ref}
        id={id}
        aria-invalid={error ? true : undefined}
        aria-describedby={`${id}-message`}
        className={cn(control, error && "border-danger ring-1 ring-danger")}
        {...props}
      >
        {children}
      </select>
      <Message id={`${id}-message`} error={error} hint={hint} />
    </div>
  );
});

/** A yes-or-no setting: a checkbox announced as a switch, with its label beside it. */
export const Switch = forwardRef<
  HTMLInputElement,
  React.InputHTMLAttributes<HTMLInputElement> & {
    label: string;
    error?: string | undefined;
    hint?: ReactNode;
  }
>(function Switch({ label, error, hint, className, ...props }, ref) {
  const id = useId();
  return (
    <div className={cn("flex flex-col gap-2", className)}>
      <label htmlFor={id} className="flex min-h-touch cursor-pointer items-center justify-between gap-4">
        <span className="min-w-0 text-small font-medium">{label}</span>
        <span className="relative inline-flex shrink-0 items-center">
          <input
            ref={ref}
            id={id}
            type="checkbox"
            role="switch"
            aria-describedby={`${id}-message`}
            className="peer h-5 w-9 cursor-pointer appearance-none rounded-full border border-control bg-surface-3 transition-colors duration-press checked:border-live checked:bg-live"
            {...props}
          />
          <span
            aria-hidden="true"
            className="pointer-events-none absolute left-1 size-3 rounded-full bg-control transition-transform duration-press peer-checked:translate-x-4 peer-checked:bg-canvas"
          />
        </span>
      </label>
      <Message id={`${id}-message`} error={error} hint={hint} />
    </div>
  );
});

/**
 * One of a few choices, all visible: a fieldset of radio buttons. `hideLegend` keeps the legend for screen readers
 * only, where a heading above already says what's chosen; `quiet`: a choice that never has help or an error, so
 * keeps no line for one.
 */
export function RadioGroup({
  legend,
  hideLegend = false,
  quiet = false,
  options,
  error,
  hint,
  inputProps,
  className,
}: {
  legend: string;
  hideLegend?: boolean;
  quiet?: boolean;
  options: { value: string; label: string }[];
  error?: string | undefined;
  hint?: ReactNode;
  inputProps: (value: string) => React.InputHTMLAttributes<HTMLInputElement>;
  className?: string;
}) {
  const id = useId();
  return (
    <fieldset
      className={cn("flex flex-col gap-2", className)}
      aria-describedby={quiet ? undefined : `${id}-message`}
    >
      <legend className={hideLegend ? "sr-only" : "mb-2 text-small font-medium"}>{legend}</legend>
      <div className="flex flex-wrap gap-2">
        {options.map((option) => (
          <label
            key={option.value}
            className="flex min-h-control cursor-pointer items-center gap-2 rounded-full border border-hairline px-4 text-small font-medium pointer-coarse:min-h-touch has-checked:border-hairline-tertiary has-checked:bg-surface-3"
          >
            <input
              type="radio"
              value={option.value}
              className="size-4 accent-ink"
              {...inputProps(option.value)}
            />
            {option.label}
          </label>
        ))}
      </div>
      {!quiet && <Message id={`${id}-message`} error={error} hint={hint} />}
    </fieldset>
  );
}
