import type { HTMLAttributes, ReactNode } from "react";
import { cn } from "@/lib/cn";

/**
 * Surfaces (web/DESIGN.md "Shapes"): no bordered boxes. A `Panel` is a white card with big corners, used inside the
 * laptop's grey right panel; a `Fill` is a grey block, for a row of facts or a summary. Lists use dividers between
 * rows, and sections are separated by white space.
 */
export function Panel({ className, ...props }: HTMLAttributes<HTMLElement>) {
  return <section className={cn("rounded-panel bg-ground p-6 md:p-10", className)} {...props} />;
}

export function Fill({ className, ...props }: HTMLAttributes<HTMLDivElement>) {
  return <div className={cn("rounded-control bg-fill px-4 py-3.5", className)} {...props} />;
}

/** A section's title, with an optional action at its end. */
export function SectionTitle({
  children,
  action,
  id,
  className,
}: {
  children: ReactNode;
  action?: ReactNode;
  id?: string;
  className?: string;
}) {
  return (
    <div className={cn("flex min-h-touch items-center justify-between gap-4", className)}>
      <h2 id={id} className="text-section font-bold">
        {children}
      </h2>
      {action}
    </div>
  );
}

/** A small grey pill of fact: "₹7.20–10.80 an hour", "Stops at ₹500". */
export function Chip({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <span
      className={cn(
        "inline-flex items-center rounded-full bg-fill px-3 py-2 text-small font-medium",
        className,
      )}
    >
      {children}
    </span>
  );
}

/** A block of content on a grey fill, with big corners: no border (web/DESIGN.md "Shapes"). */
export function Card({ className, ...props }: HTMLAttributes<HTMLElement>) {
  return <section className={cn("rounded-panel bg-fill p-4 md:p-6", className)} {...props} />;
}

/** A card's title, with an optional action at its end. */
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
    <SectionTitle id={id} action={action} className="mb-4">
      {children}
    </SectionTitle>
  );
}
