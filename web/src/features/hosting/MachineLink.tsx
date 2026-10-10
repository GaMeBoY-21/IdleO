import { ChevronRight } from "lucide-react";
import type { ReactNode } from "react";
import { Link } from "wouter";

/** A grey row that opens another screen about the machine: Price and limits, Sessions it ran. */
export function MachineLink({ href, title, detail }: { href: string; title: string; detail: ReactNode }) {
  return (
    <Link
      href={href}
      className="flex min-h-18 w-full items-center gap-3.5 rounded-control bg-fill px-4 py-3.5 hover:bg-fill-selected"
    >
      <span className="flex min-w-0 flex-1 flex-col gap-0.5">
        <span className="text-body font-semibold">{title}</span>
        <span className="text-small text-text-muted tabular">{detail}</span>
      </span>
      <ChevronRight aria-hidden="true" className="size-5 shrink-0" strokeWidth={2} />
    </Link>
  );
}
