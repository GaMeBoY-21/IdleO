import { useId } from "react";
import { cn } from "@/lib/cn";

/**
 * The authenticator code field (ADR 0027): 6 digits, offered by the phone's one-time-code keyboard, spaces and dashes
 * between groups dropped as they're typed. Or a recovery code, letters and digits.
 */
export function CodeField({
  kind,
  value,
  onChange,
  error,
}: {
  kind: "totp" | "recovery";
  value: string;
  onChange: (value: string) => void;
  error: string | null;
}) {
  const id = useId();
  const totp = kind === "totp";
  return (
    <div className="flex flex-col gap-2">
      <label htmlFor={id} className="text-small font-medium">
        {totp ? "Code from your authenticator app" : "Recovery code"}
      </label>
      <input
        id={id}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        inputMode={totp ? "numeric" : "text"}
        autoComplete={totp ? "one-time-code" : "off"}
        autoCapitalize="characters"
        spellCheck={false}
        maxLength={totp ? 9 : 16}
        placeholder={totp ? "123 456" : "K7Q2M-9XD4P"}
        aria-invalid={error ? true : undefined}
        aria-describedby={`${id}-message`}
        className={cn(
          "tabular min-h-14 rounded-control bg-panel px-4 text-title text-text placeholder:text-text-2",
          error && "ring-2 ring-danger",
        )}
      />
      <p id={`${id}-message`} className={cn("min-h-4.5 text-caption", error ? "text-danger" : "text-text-2")}>
        {error ?? (totp ? "It changes every 30 seconds." : "Each one works once.")}
      </p>
    </div>
  );
}
