import { Check } from "lucide-react";
import type { ReactNode } from "react";
import { cn } from "@/lib/cn";

/**
 * One of the add-a-machine screen's four numbered steps (docs/design/hosting/add-machine-laptop.html): a number, or a tick
 * once it's done; its title; and, while it's the step in hand, what to do. A step to come shows its title only.
 */
export function Step({
  number,
  title,
  state,
  summary,
  children,
}: {
  number: number;
  title: string;
  state: "done" | "active" | "later";
  summary?: ReactNode;
  children?: ReactNode;
}) {
  return (
    <li
      aria-current={state === "active" ? "step" : undefined}
      className="flex flex-col gap-4 border-t border-divider py-5 first:border-t-0 first:pt-0"
    >
      <div className="flex min-h-touch items-center gap-3">
        <span
          aria-hidden="true"
          className={cn(
            "flex size-9 shrink-0 items-center justify-center rounded-full text-small font-semibold",
            state === "done" && "bg-running-soft text-running",
            state === "active" && "bg-primary text-primary-fg",
            state === "later" && "bg-fill text-text-muted",
          )}
        >
          {state === "done" ? <Check className="size-4.5" strokeWidth={2.5} /> : number}
        </span>
        <div className="flex min-w-0 flex-1 flex-col">
          <h2 className={cn("text-section font-bold", state === "later" && "text-text-muted")}>
            <span className="sr-only">Step {number}: </span>
            {title}
          </h2>
          {summary && <p className="text-small text-text-muted">{summary}</p>}
        </div>
      </div>
      {state === "active" && children}
    </li>
  );
}
