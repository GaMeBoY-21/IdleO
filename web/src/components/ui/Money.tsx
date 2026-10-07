import { formatPaise } from "@/lib/money";
import { cn } from "@/lib/cn";

/** An amount of money: exact, in tabular figures, from integer paise (web/DESIGN.md principle 2). */
export function Money({ paise, className }: { paise: number; className?: string }) {
  return <span className={cn("tabular", className)}>{formatPaise(paise)}</span>;
}
