import type { HTMLAttributes, ReactNode } from "react";
import { cn } from "@/lib/cn";

/** A surface on the page, separated by its border, never a shadow (web/DESIGN.md "Space and shape"). */
export function Card({ className, ...props }: HTMLAttributes<HTMLElement>) {
  return (
    <section
      className={cn("rounded-card border border-border bg-surface p-4 md:p-6", className)}
      {...props}
    />
  );
}

export function CardTitle({
  children,
  action,
  id,
}: {
  children: ReactNode;
  action?: ReactNode;
  id?: string;
}) {
  return (
    <div className="mb-4 flex min-h-touch items-center justify-between gap-4">
      <h2 id={id} className="text-title font-semibold">
        {children}
      </h2>
      {action}
    </div>
  );
}
