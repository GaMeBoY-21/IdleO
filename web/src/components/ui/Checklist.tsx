import { Check } from "lucide-react";
import type { ReactNode } from "react";
import { AppLink } from "@/components/ui/AppLink";
import { buttonClasses } from "@/components/ui/Button";
import { SectionTitle, Tile } from "@/components/ui/Tile";
import { cn } from "@/lib/cn";

export interface Step {
  title: string;
  detail: string;
  /** Done, not yet, or still being found out (null). */
  done: boolean | null;
  action?: { href: string; label: string };
  /** In place of `action`: a control of its own (the renter's one-click test job). */
  control?: ReactNode;
}

/**
 * A first run's steps as one checklist (ADR 0058): each in order, ticked when done; the first not done carries a button,
 * the rest a plain link. Never the primary style: the screen it's on has its own one primary. Shown until every step is
 * done.
 */
export function Checklist({ id, title, steps }: { id: string; title: string; steps: Step[] }) {
  // Drawn only once every step is known, so it never appears and then goes for someone who is done: what a screen
  // kept from an earlier visit is enough, and a first visit draws it with the rest of the screen.
  if (steps.some((step) => step.done === null) || steps.every((step) => step.done === true)) {
    return null;
  }
  const next = steps.findIndex((step) => step.done !== true);
  const done = steps.filter((step) => step.done === true).length;
  return (
    <Tile as="section" aria-labelledby={id} className="flex flex-col gap-3 md:p-6 xl:p-7">
      <div className="flex items-baseline justify-between gap-3">
        <SectionTitle id={id}>{title}</SectionTitle>
        <span className="text-small text-text-2 tabular">
          {done} of {steps.length} done
        </span>
      </div>
      <ol className="flex flex-col divide-y divide-line">
        {steps.map((step, index) => (
          <li key={step.title} className="flex min-h-18 items-start gap-3 py-3.5">
            <span
              aria-hidden="true"
              className={cn(
                "flex size-8 shrink-0 items-center justify-center rounded-full text-small font-bold",
                step.done === true ? "bg-primary text-primary-fg" : "bg-raised text-text-2",
              )}
            >
              {step.done === true ? <Check className="size-4" strokeWidth={2.5} /> : index + 1}
            </span>
            <div className="flex min-w-0 flex-1 flex-col gap-0.5">
              <p className="text-option font-semibold">
                {step.title}
                <span className="sr-only">{step.done === true ? ": done" : ": to do"}</span>
              </p>
              <p className="text-small text-text-2">{step.detail}</p>
            </div>
            {step.control && step.done === false ? (
              <span className="shrink-0">{step.control}</span>
            ) : (
              step.action &&
              step.done === false && (
                <AppLink
                  href={step.action.href}
                  className={
                    index === next
                      ? cn(buttonClasses("ghost", false, false), "shrink-0")
                      : "min-h-touch shrink-0 self-center text-small font-semibold underline"
                  }
                >
                  {step.action.label}
                </AppLink>
              )
            )}
          </li>
        ))}
      </ol>
    </Tile>
  );
}
