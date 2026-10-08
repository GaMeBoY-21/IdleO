import { Check } from "lucide-react";
import { Link } from "wouter";
import { cn } from "@/lib/cn";

export type Step = "pick" | "configure" | "review";

const STEPS: { step: Step; label: string }[] = [
  { step: "pick", label: "Pick a template" },
  { step: "configure", label: "Configure" },
  { step: "review", label: "Review and book" },
];

/** Where the renter is in booking; the steps behind them are links back. */
export function StepNav({ current, templateId }: { current: Step; templateId: string | null }) {
  const index = STEPS.findIndex((s) => s.step === current);
  return (
    <nav aria-label="Booking steps">
      <ol className="flex flex-wrap items-center gap-x-4 gap-y-2 text-small">
        {STEPS.map(({ step, label }, i) => {
          const done = i < index;
          const href =
            step === "pick"
              ? "/book/"
              : `/book/?template=${encodeURIComponent(templateId ?? "")}&step=${step}`;
          const content = (
            <>
              <span
                aria-hidden="true"
                className={cn(
                  "flex size-6 items-center justify-center rounded-full text-caption font-semibold",
                  i === index
                    ? "bg-accent text-accent-fg"
                    : done
                      ? "bg-accent-soft text-accent-text"
                      : "bg-sunken text-text-muted",
                )}
              >
                {done ? <Check className="size-4" strokeWidth={2} /> : i + 1}
              </span>
              {/* On a phone only the current step is named; the others are numbers. */}
              <span className={i === index ? undefined : "sr-only md:not-sr-only"}>{label}</span>
            </>
          );
          return (
            <li key={step} aria-current={i === index ? "step" : undefined}>
              {done && (step === "pick" || templateId !== null) ? (
                <Link href={href} className="flex min-h-touch items-center gap-2 text-accent-text">
                  {content}
                </Link>
              ) : (
                <span
                  className={cn(
                    "flex min-h-touch items-center gap-2",
                    i === index ? "font-medium" : "text-text-muted",
                  )}
                >
                  {content}
                </span>
              )}
            </li>
          );
        })}
      </ol>
    </nav>
  );
}
