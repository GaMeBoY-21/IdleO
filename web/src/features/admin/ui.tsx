import type { UseInfiniteQueryResult, UseQueryResult } from "@tanstack/react-query";
import { useId, type ReactNode } from "react";
import { Link } from "wouter";
import { Button } from "@/components/ui/Button";
import { Skeleton } from "@/components/ui/Skeleton";
import { EmptyState, ErrorState, StaleNotice, staleSince } from "@/components/ui/States";
import { cn } from "@/lib/cn";
import { formatDateTime } from "@/lib/time";
import { useTitle } from "@/lib/title";

/**
 * The console's building blocks (ADR 0027, ADR 0043): the same tokens as the app, but dense (smaller type, tighter
 * tables), for one person's tool. Every list and record has the four states of web/DESIGN.md: a skeleton of its shape while it loads,
 * why it's empty, what went wrong with a retry, and a line when the figures shown are stale.
 */

export function AdminPage({ title, children }: { title: string; children: ReactNode }) {
  useTitle(`${title} · Admin`);
  return (
    <div className="flex flex-col gap-6">
      <h1 className="font-display text-headline uppercase">{title}</h1>
      {children}
    </div>
  );
}

export function Section({
  title,
  children,
  action,
}: {
  title: string;
  children: ReactNode;
  action?: ReactNode;
}) {
  const id = useId();
  return (
    <section aria-labelledby={id} className="flex flex-col gap-3">
      <div className="flex min-h-touch items-center justify-between gap-3">
        <h2 id={id} className="text-section">
          {title}
        </h2>
        {action}
      </div>
      {children}
    </section>
  );
}

/** A loading table: its header and `rows` grey rows, each a row's height, announced as loading `what`. */
export function TableSkeleton({ what, rows = 5 }: { what: string; rows?: number }) {
  return (
    <div role="status" aria-label={`Loading ${what}`} className="flex flex-col gap-1">
      {Array.from({ length: rows + 1 }, (_, index) => (
        <Skeleton key={index} className="h-11 w-full" />
      ))}
    </div>
  );
}

/**
 * One query's states around its content: loading, error with a retry, empty, and stale. `isEmpty` says when the data
 * has nothing to show.
 */
export function QueryView<T>({
  query,
  what,
  isEmpty,
  empty,
  skeleton,
  children,
}: {
  query: UseQueryResult<T>;
  what: string;
  isEmpty?: (data: T) => boolean;
  empty?: ReactNode;
  skeleton?: ReactNode;
  children: (data: T) => ReactNode;
}) {
  if (query.isPending) {
    return <>{skeleton ?? <TableSkeleton what={what} />}</>;
  }
  if (query.data === undefined) {
    return (
      <ErrorState
        error={query.error}
        what={what}
        onRetry={() => void query.refetch()}
        retrying={query.isFetching}
      />
    );
  }
  const stale = staleSince(query);
  return (
    <div className="flex flex-col gap-3">
      {stale !== null && <StaleNotice updatedAt={stale} />}
      {isEmpty?.(query.data) ? empty : children(query.data)}
    </div>
  );
}

/** A paged list: the pages so far, flattened, with "Show more" while there's a next page. */
export function PagedView<P, T>({
  query,
  what,
  items,
  empty,
  children,
}: {
  query: UseInfiniteQueryResult<{ pages: P[] }>;
  what: string;
  items: (page: P) => T[];
  empty: ReactNode;
  children: (rows: T[]) => ReactNode;
}) {
  if (query.isPending) {
    return <TableSkeleton what={what} />;
  }
  if (query.data === undefined) {
    return (
      <ErrorState
        error={query.error}
        what={what}
        onRetry={() => void query.refetch()}
        retrying={query.isFetching}
      />
    );
  }
  const rows = query.data.pages.flatMap(items);
  const stale = staleSince(query);
  return (
    <div className="flex flex-col gap-3">
      {stale !== null && <StaleNotice updatedAt={stale} />}
      {rows.length === 0 ? empty : children(rows)}
      {query.hasNextPage && (
        <Button
          variant="ghost"
          className="self-start"
          busy={query.isFetchingNextPage}
          onClick={() => void query.fetchNextPage()}
        >
          Show more
        </Button>
      )}
    </div>
  );
}

export function Empty({ title, children }: { title: string; children: ReactNode }) {
  return <EmptyState title={title}>{children}</EmptyState>;
}

/**
 * A dense table: a caption for screen readers, column headers, and a divider between rows. It scrolls sideways on a
 * phone, and holds its screen-reader-only words, which are positioned, inside it, so they never widen the page.
 */
export function Table({
  caption,
  columns,
  children,
}: {
  caption: string;
  columns: string[];
  children: ReactNode;
}) {
  return (
    <div
      className="relative overflow-x-auto rounded-panel border border-edge bg-panel px-3 py-1.5"
      // a wide table scrolls; the keyboard must reach it
      // eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex
      tabIndex={0}
      role="region"
      aria-label={caption}
    >
      <table className="w-full min-w-max border-collapse text-small">
        <caption className="sr-only">{caption}</caption>
        <thead>
          <tr className="border-b border-line text-left text-caption tracking-wider text-text-3 uppercase">
            {columns.map((column) => (
              <th key={column} scope="col" className="px-2 py-2.5 font-semibold">
                {column}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>{children}</tbody>
      </table>
    </div>
  );
}

export function Row({ children, className }: { children: ReactNode; className?: string }) {
  return <tr className={cn("border-b border-line align-top last:border-b-0", className)}>{children}</tr>;
}

export function Cell({ children, className }: { children: ReactNode; className?: string }) {
  return <td className={cn("px-2 py-2", className)}>{children}</td>;
}

/** A row's link to its record: a 44 px target, like every control (web/DESIGN.md "Shapes and space"). */
export function RowLink({ href, children }: { href: string; children: ReactNode }) {
  return (
    <Link
      href={href}
      className="inline-flex min-h-touch items-center font-semibold underline-offset-4 hover:underline"
    >
      {children}
    </Link>
  );
}

/** The way back from a record to its list. */
export function BackLink({ href, children }: { href: string; children: ReactNode }) {
  return (
    <Link
      href={href}
      className="inline-flex min-h-touch items-center self-start text-small font-semibold underline-offset-4 hover:underline"
    >
      {children}
    </Link>
  );
}

/** A record's fields, as label and value pairs. */
export function Fields({ items }: { items: [string, ReactNode][] }) {
  return (
    <dl className="flex flex-col gap-2 text-small">
      {items.map(([label, value]) => (
        <div key={label} className="flex flex-col md:flex-row md:gap-6">
          <dt className="text-text-2 md:w-40 md:shrink-0">{label}</dt>
          <dd className="min-w-0 break-all">{value}</dd>
        </div>
      ))}
    </dl>
  );
}

/** An id, shortened to its last 8 characters for reading, whole in its title and for screen readers. */
export function Id({ value }: { value: string }) {
  return (
    <span className="tabular" title={value}>
      <span aria-hidden="true">…{value.slice(-8)}</span>
      <span className="sr-only">{value}</span>
    </span>
  );
}

/** A small word for a state, in a pill; never colour alone. */
export function Pill({
  children,
  tone = "plain",
}: {
  children: ReactNode;
  tone?: "plain" | "danger" | "running";
}) {
  return (
    <span
      className={cn(
        "inline-flex rounded-full px-2.5 py-0.5 text-caption font-medium",
        tone === "plain" && "bg-raised text-text",
        tone === "running" && "bg-ok text-ok-fg",
        tone === "danger" && "bg-danger-soft text-danger",
      )}
    >
      {children}
    </span>
  );
}

/** A search form's row of fields and its button, submitted as a whole. */
export function Filters({ onSubmit, children }: { onSubmit: () => void; children: ReactNode }) {
  return (
    <form
      role="search"
      className="flex flex-wrap items-end gap-3"
      onSubmit={(event) => {
        event.preventDefault();
        onSubmit();
      }}
    >
      {children}
      <Button type="submit" variant="ghost">
        Search
      </Button>
    </form>
  );
}

/** One small labelled field of a search form, or of a sheet. */
export function SmallField({
  label,
  value,
  onChange,
  placeholder,
  type = "text",
  inputMode,
  width = "w-56",
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  placeholder?: string;
  type?: string;
  inputMode?: "text" | "numeric" | "decimal";
  width?: string;
}) {
  const id = useId();
  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={id} className="text-caption font-medium">
        {label}
      </label>
      <input
        id={id}
        type={type}
        value={value}
        inputMode={inputMode}
        placeholder={placeholder}
        onChange={(event) => onChange(event.target.value)}
        className={cn(
          "min-h-touch rounded-control border border-edge bg-panel px-3 text-small text-text placeholder:text-text-2",
          width,
        )}
      />
    </div>
  );
}

/** One small labelled choice of a search form. */
export function SmallSelect({
  label,
  value,
  onChange,
  options,
}: {
  label: string;
  value: string;
  onChange: (value: string) => void;
  options: [string, string][];
}) {
  const id = useId();
  return (
    <div className="flex flex-col gap-1">
      <label htmlFor={id} className="text-caption font-medium">
        {label}
      </label>
      <select
        id={id}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        className="min-h-touch rounded-control border border-edge bg-panel px-3 text-small text-text"
      >
        {options.map(([option, text]) => (
          <option key={option} value={option}>
            {text}
          </option>
        ))}
      </select>
    </div>
  );
}

/** A session's changes of state, oldest first, each with when and why, from the append-only timeline. */
export function Timeline({
  events,
}: {
  events: { at: string; from?: string | null; to: string; reason: string }[];
}) {
  return (
    <ol className="flex flex-col gap-2 text-small">
      {events.map((event, index) => (
        <li key={`${event.at}-${index}`} className="flex flex-wrap gap-x-3">
          <time dateTime={event.at} className="tabular text-text-2">
            {formatDateTime(event.at)}
          </time>
          <span className="font-medium">
            {event.from ? `${event.from} → ` : ""}
            {event.to}
          </span>
          <span className="text-text-2">{event.reason}</span>
        </li>
      ))}
    </ol>
  );
}
