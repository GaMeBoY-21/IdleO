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
const control = "min-h-touch rounded-control border bg-surface px-3 text-body text-text";

function Message({ id, error, hint }: { id: string; error?: string | undefined; hint?: ReactNode }) {
  return (
    <p id={id} className={cn("min-h-4 text-caption", error ? "text-danger" : "text-text-muted")}>
      {error ?? hint ?? ""}
    </p>
  );
}

export const TextArea = forwardRef<
  HTMLTextAreaElement,
  TextareaHTMLAttributes<HTMLTextAreaElement> & {
    label: string;
    error?: string | undefined;
    hint?: ReactNode;
  }
>(function TextArea({ label, error, hint, className, ...props }, ref) {
  const id = useId();
  return (
    <div className={cn("flex flex-col gap-2", className)}>
      <label htmlFor={id} className="text-small font-medium">
        {label}
      </label>
      <textarea
        ref={ref}
        id={id}
        rows={4}
        aria-invalid={error ? true : undefined}
        aria-describedby={`${id}-message`}
        className={cn(control, "py-2", error ? "border-danger" : "border-border-strong")}
        {...props}
      />
      <Message id={`${id}-message`} error={error} hint={hint} />
    </div>
  );
});

export const Select = forwardRef<
  HTMLSelectElement,
  SelectHTMLAttributes<HTMLSelectElement> & { label: string; error?: string | undefined; hint?: ReactNode }
>(function Select({ label, error, hint, className, children, ...props }, ref) {
  const id = useId();
  return (
    <div className={cn("flex flex-col gap-2", className)}>
      <label htmlFor={id} className="text-small font-medium">
        {label}
      </label>
      <select
        ref={ref}
        id={id}
        aria-invalid={error ? true : undefined}
        aria-describedby={`${id}-message`}
        className={cn(control, error ? "border-danger" : "border-border-strong")}
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
        <span className="text-small font-medium">{label}</span>
        <span className="relative inline-flex shrink-0 items-center">
          <input
            ref={ref}
            id={id}
            type="checkbox"
            role="switch"
            aria-describedby={`${id}-message`}
            className="peer h-6 w-12 cursor-pointer appearance-none rounded-full border border-border-strong bg-sunken transition-colors duration-120 checked:border-accent checked:bg-accent"
            {...props}
          />
          <span
            aria-hidden="true"
            className="pointer-events-none absolute left-1 size-4 rounded-full bg-border-strong transition-transform duration-120 peer-checked:translate-x-6 peer-checked:bg-accent-fg"
          />
        </span>
      </label>
      <Message id={`${id}-message`} error={error} hint={hint} />
    </div>
  );
});

/** One of a few choices, all visible: a fieldset of radio buttons. */
export function RadioGroup({
  legend,
  options,
  error,
  hint,
  inputProps,
  className,
}: {
  legend: string;
  options: { value: string; label: string }[];
  error?: string | undefined;
  hint?: ReactNode;
  inputProps: (value: string) => React.InputHTMLAttributes<HTMLInputElement>;
  className?: string;
}) {
  const id = useId();
  return (
    <fieldset className={cn("flex flex-col gap-2", className)} aria-describedby={`${id}-message`}>
      <legend className="mb-2 text-small font-medium">{legend}</legend>
      <div className="flex flex-wrap gap-2">
        {options.map((option) => (
          <label
            key={option.value}
            className="flex min-h-touch cursor-pointer items-center gap-2 rounded-control border border-border-strong px-3 text-small has-checked:border-accent has-checked:bg-accent-soft has-checked:text-accent-text"
          >
            <input
              type="radio"
              value={option.value}
              className="size-4 accent-accent"
              {...inputProps(option.value)}
            />
            {option.label}
          </label>
        ))}
      </div>
      <Message id={`${id}-message`} error={error} hint={hint} />
    </fieldset>
  );
}

/** How far something has got, as a thin bar; the figure beside it says the same in words. */
export function ProgressBar({ percent, label }: { percent: number; label: string }) {
  return (
    <div
      role="progressbar"
      aria-label={label}
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={percent}
      className="h-2 w-full overflow-hidden rounded-full bg-sunken"
    >
      <div className="h-full rounded-full bg-accent" style={{ width: `${percent}%` }} />
    </div>
  );
}
