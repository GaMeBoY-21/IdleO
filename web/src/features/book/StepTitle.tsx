import type { ReactNode } from "react";

/**
 * A step's heading on a service's booking screen (docs/design/redesign/book-finetune-laptop.html): "1. Your model",
 * with "(optional)" when it is, and the catalog's help under it.
 */
export function StepTitle({
  id,
  number,
  optional = false,
  help,
  children,
}: {
  id: string;
  number: number | null;
  optional?: boolean;
  help?: string | null | undefined;
  children: ReactNode;
}) {
  return (
    <div className="flex flex-col gap-1">
      <h2 id={id} className="text-section">
        {number !== null && <span className="tabular">{number}. </span>}
        {children}
        {optional && <span className="font-medium text-text-2"> (optional)</span>}
      </h2>
      {help && <p className="text-small text-text-2">{help}</p>}
    </div>
  );
}
