import { ChevronRight } from "lucide-react";
import type { ReactNode } from "react";
import { Link } from "wouter";

/** A white tile that opens another screen about the machine: Price and limits, Sessions it ran. */
export function MachineLink({ href, title, detail }: { href: string; title: string; detail: ReactNode }) {
  return (
    <Link
      href={href}
      className="tile-motion flex min-h-18 w-full items-center gap-3.5 rounded-panel bg-panel px-4.5 py-3.5"
    >
      <span className="flex min-w-0 flex-1 flex-col gap-0.5">
        <span className="text-body font-semibold">{title}</span>
        <span className="text-small text-text-2 tabular">{detail}</span>
      </span>
      <ChevronRight aria-hidden="true" className="size-5 shrink-0" strokeWidth={2} />
    </Link>
  );
}
