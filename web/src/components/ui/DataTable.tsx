import type { ReactNode } from "react";
import { cn } from "@/lib/cn";

/**
 * A table (web/DESIGN.md "Tables"), one way on every site: a caption for screen readers, quiet 12 px headings, a
 * 44 px row with a hairline between rows and a quiet hover, and numbers aligned right in tabular figures, so their
 * digits line up. The console's dense table (features/admin/Table.tsx: columns to show, pin and resize, sorting, saved
 * views, bulk selection and long lists drawn as they scroll) is this one with those features on top: its headings and
 * cells take the classes below.
 */
export interface Column {
  label: ReactNode;
  /** A number: its heading and cells align right. */
  numeric?: boolean;
  /** Read out, not shown (an actions column). */
  hidden?: boolean;
}

export const HEADING = "py-2 pr-4 text-left text-caption font-medium text-ink-subtle last:pr-0";
export const NUMERIC = "text-right tabular";

export function DataTable({
  caption,
  columns,
  children,
  className,
}: {
  caption: string;
  columns: readonly Column[];
  children: ReactNode;
  className?: string;
}) {
  return (
    <div className={cn("overflow-x-auto", className)}>
      <table className="w-full border-collapse text-small">
        <caption className="sr-only">{caption}</caption>
        <thead>
          <tr className="border-b border-hairline">
            {columns.map((column, index) => (
              <th
                key={index}
                scope="col"
                className={cn(HEADING, column.numeric && NUMERIC, column.hidden && "sr-only")}
              >
                {column.label}
              </th>
            ))}
          </tr>
        </thead>
        <tbody className="divide-y divide-hairline">{children}</tbody>
      </table>
    </div>
  );
}

export function DataRow({ children, className }: { children: ReactNode; className?: string }) {
  return <tr className={cn("h-11 hover:bg-surface-2", className)}>{children}</tr>;
}

export function DataCell({
  children,
  numeric = false,
  className,
}: {
  children: ReactNode;
  numeric?: boolean;
  className?: string;
}) {
  return (
    <td className={cn("py-2 pr-4 align-middle last:pr-0", numeric && NUMERIC, className)}>{children}</td>
  );
}
