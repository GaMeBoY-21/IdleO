import type { ReactNode } from "react";
import { cn } from "@/lib/cn";

/**
 * Labels and their values (web/DESIGN.md "Key-value lists and figures"), the one way on every site.
 *
 * `KeyValueList`: a record's fields, label beside value from 768 px (`columns`, the console's), or label at the start and
 * value at the end of one line (`split`, a request's or a price's breakdown), optionally with a line between rows.
 * `StatGrid` and `StatTile`: a few figures side by side, each a label, its figure in tabular numbers and a line on it, the
 * same height loading and loaded.
 */
export type KeyValue = readonly [label: ReactNode, value: ReactNode];

export function KeyValueList({
  items,
  layout = "columns",
  divided = false,
  size = "small",
  className,
}: {
  items: readonly KeyValue[];
  layout?: "columns" | "split";
  divided?: boolean;
  size?: "small" | "body";
  className?: string;
}) {
  return (
    <dl
      className={cn(
        "flex flex-col",
        divided ? "divide-y divide-hairline" : "gap-2",
        size === "small" ? "text-small" : "text-body",
        className,
      )}
    >
      {items.map(([label, value], index) => (
        <div
          key={typeof label === "string" ? label : index}
          className={cn(
            layout === "columns"
              ? "flex flex-col md:flex-row md:gap-6"
              : "flex min-h-touch items-center justify-between gap-4",
            divided && "py-2",
          )}
        >
          <dt className={cn("text-ink-subtle", layout === "columns" ? "md:w-40 md:shrink-0" : "shrink-0")}>
            {label}
          </dt>
          <dd
            className={cn(
              "min-w-0",
              layout === "split" ? "flex-1 text-right font-semibold break-words" : "break-words",
            )}
          >
            {value}
          </dd>
        </div>
      ))}
    </dl>
  );
}

/** Figures side by side: two a row, each a `StatTile`. */
export function StatGrid({ children, className }: { children: ReactNode; className?: string }) {
  return <dl className={cn("grid grid-cols-2 gap-2", className)}>{children}</dl>;
}

export function StatTile({
  label,
  children,
  note,
  size = "md",
  className,
}: {
  label: ReactNode;
  /** The figure: tabular, never counted up. */
  children: ReactNode;
  /** One line on it, which holds its place even when empty, so tiles in a row line up. */
  note?: ReactNode;
  /** `sm` in a narrow panel: the figure in small type, with room kept for it on two lines. */
  size?: "md" | "sm";
  className?: string;
}) {
  return (
    <div
      className={cn(
        "flex flex-col gap-1 rounded-card px-4",
        size === "md" ? "min-h-18 justify-center py-4" : "min-h-20 justify-start py-2",
        "border border-hairline bg-surface-2",
        className,
      )}
    >
      <dt className={"text-small text-ink-subtle"}>{label}</dt>
      <dd className="flex flex-col gap-1">
        <div className={cn("font-semibold tabular", size === "md" ? "text-card" : "text-small")}>
          {children}
        </div>
        {note !== undefined && <span className={"min-h-9 text-caption text-ink-subtle"}>{note}</span>}
      </dd>
    </div>
  );
}
