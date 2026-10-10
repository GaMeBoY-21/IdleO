import type { ReactNode } from "react";
import { cn } from "@/lib/cn";

/**
 * A step's heading on a service's booking screen (docs/design/redesign/book-finetune-laptop.html): "1. Your model",
 * with "(optional)" when it is, and the catalog's help under it.
 */
export function StepTitle({
  id,
  number,
  laptop,
  optional = false,
  help,
  children,
}: {
  id: string;
  number: number | null;
  laptop: boolean;
  optional?: boolean;
  help?: string | null | undefined;
  children: ReactNode;
}) {
  return (
    <div className="flex flex-col gap-1">
      <h2 id={id} className={cn(laptop ? "text-section font-bold" : "text-option font-semibold")}>
        {number !== null && <span className="tabular">{number}. </span>}
        {children}
        {optional && <span className="font-medium text-text-muted"> (optional)</span>}
      </h2>
      {help && <p className="text-small text-text-muted">{help}</p>}
    </div>
  );
}
