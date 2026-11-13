import { ChevronRight } from "lucide-react";
import type { ReactNode } from "react";
import { Link } from "wouter";

/** A card that opens another screen about the machine: Price and limits, Sessions it ran. */
export function MachineLink({ href, title, detail }: { href: string; title: string; detail: ReactNode }) {
  return (
    <Link
      href={href}
      className="tile-motion flex min-h-18 w-full items-center gap-4 rounded-card bg-surface-1 px-4 py-4"
    >
      <span className="flex min-w-0 flex-1 flex-col gap-1">
        <span className="text-body font-semibold">{title}</span>
        <span className="text-small text-ink-subtle tabular">{detail}</span>
      </span>
      <ChevronRight aria-hidden="true" className="size-5 shrink-0" strokeWidth={2} />
    </Link>
  );
}
