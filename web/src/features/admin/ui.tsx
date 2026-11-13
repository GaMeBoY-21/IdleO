import { useQueryClient, type UseInfiniteQueryResult, type UseQueryResult } from "@tanstack/react-query";
import { Check, Copy } from "lucide-react";
import { PageHeader, SectionHeader, type Crumb } from "@/components/ui/Headers";
import { useId, useState, type ReactNode } from "react";
import { Link } from "wouter";
import { Banner } from "@/components/ui/Banner";
import { Button } from "@/components/ui/Button";
import { Skeleton } from "@/components/ui/Skeleton";
import { EmptyState, ErrorState, StaleNotice, staleSince } from "@/components/ui/States";
import { cn } from "@/lib/cn";
import { formatDateTime } from "@/lib/time";
import { recordHref } from "./links";
import { prefetchRecord } from "./recordQueries";
import { refusal } from "./ActionSheet";
import { word } from "./words";

export { Cell, Row, Table } from "./Table";
export { ErrorState };

/**
 * The console's building blocks (ADR 0027, ADR 0043): the same tokens as the app, but dense (smaller type, tighter
 * tables), for one person's tool. Every list and record has the four states of web/DESIGN.md: a skeleton of its shape while it loads,
 * why it's empty, what went wrong with a retry, and a line when the figures shown are stale.
 */

export function AdminPage({
  title,
  name,
  description,
  trail,
  action,
  children,
}: {
  /** The page's name, or a record's kind ("Host"). */
  title: string;
  /**
   * A record's own name, under its kind: what the page is titled and its tab named. `null` while the record loads, which
   * holds the name's line, so nothing moves when it arrives.
   */
  name?: string | null;
  description?: ReactNode;
  /** The pages above a record's: its list, as breadcrumbs. */
  trail?: Crumb[];
  action?: ReactNode;
  children: ReactNode;
}) {
  const record = name !== undefined;
  return (
    <div className="flex flex-col gap-6">
      <PageHeader
        dense
        title={
          record ? (
            <>
              <span className="block font-sans text-caption font-semibold text-ink-subtle">{title}</span>{" "}
              <span className="block normal-case">{name ?? "…"}</span>
            </>
          ) : (
            title
          )
        }
        tabTitle={record ? `${name ?? title} · ${title} · Admin` : `${title} · Admin`}
        crumb={record ? (name ?? title) : title}
        description={description}
        trail={trail}
        action={action}
      />
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
    <section aria-labelledby={id} className="flex flex-col gap-4">
      <SectionHeader id={id} action={action}>
        {title}
      </SectionHeader>
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
/**
 * What a screen shows under its main list or record, drawn once that has arrived (or failed), so it never sits under the
 * loading skeleton and then jumps down the page.
 */
export function Below({
  query,
  children,
}: {
  query: UseQueryResult | UseInfiniteQueryResult;
  children: ReactNode;
}) {
  return query.isPending ? null : <>{children}</>;
}

export function QueryView<T>({
  query,
  what,
  isEmpty,
  empty,
  skeleton,
  children,
}: {
  query: UseQueryResult<T> | UseInfiniteQueryResult<T>;
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
    <div className="flex flex-col gap-2">
      {stale !== null && <StaleNotice updatedAt={stale} />}
      {isEmpty?.(query.data) ? empty : children(query.data)}
      {"hasNextPage" in query && query.hasNextPage && (
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
    <div className="flex flex-col gap-2">
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

/** What went wrong, in the console's one way of saying it; nothing while there's no problem. */
export function Problem({ error }: { error: unknown }) {
  return error === null || error === undefined ? null : <Banner tone="danger">{refusal(error)}</Banner>;
}

export function Empty({ title, children }: { title: string; children: ReactNode }) {
  return <EmptyState title={title}>{children}</EmptyState>;
}

/** A row's link to its record: a 44 px target, like every control (web/DESIGN.md "Shapes and space"). */
export function RowLink({ href, children }: { href: string; children: ReactNode }) {
  const ask = usePrefetchRecord(href);
  return (
    <Link
      href={href}
      onPointerEnter={ask}
      onFocus={ask}
      className="inline-flex min-h-touch items-center font-semibold underline-offset-4 hover:underline"
    >
      {children}
    </Link>
  );
}

/** Asks for the record a link opens as it's pointed at or focused, so it's usually there when it opens. */
function usePrefetchRecord(href: string): () => void {
  const client = useQueryClient();
  return () => prefetchRecord(client, href);
}

/** An id's link to its record. */
function RecordLink({ href, children }: { href: string; children: ReactNode }) {
  const ask = usePrefetchRecord(href);
  return (
    <Link
      href={href}
      onPointerEnter={ask}
      onFocus={ask}
      className="inline-flex min-h-6 min-w-6 items-center underline"
    >
      {children}
    </Link>
  );
}

/** Copies an id with one click, and says it has. */
function CopyId({ value }: { value: string }) {
  const [copied, setCopied] = useState(false);
  return (
    <button
      type="button"
      aria-label={copied ? "Copied" : `Copy the id …${value.slice(-8)}`}
      title="Copy the whole id"
      onClick={() => {
        void navigator.clipboard.writeText(value).then(() => {
          setCopied(true);
          setTimeout(() => setCopied(false), 1_500);
        });
      }}
      className="ml-1 inline-flex min-h-6 min-w-6 items-center justify-center rounded-control align-middle text-ink-tertiary hover:text-ink"
    >
      {copied ? <Check aria-hidden="true" size={14} /> : <Copy aria-hidden="true" size={14} />}
    </button>
  );
}

/** An id, shortened to its last 8 characters for reading, whole in its title and for screen readers, copied in one click. */
export function Id({ value, page, label }: { value: string; page?: string; label?: string }) {
  const shown = (
    <span className="tabular" title={value}>
      <span aria-hidden={label ? undefined : true}>{label ?? `…${value.slice(-8)}`}</span>
      {!label && <span className="sr-only">{value}</span>}
    </span>
  );
  return (
    <span className="inline-flex items-center whitespace-nowrap">
      {page ? <RecordLink href={recordHref(page, value)}>{shown}</RecordLink> : shown}
      <CopyId value={value} />
    </span>
  );
}

/** One small labelled field of a search form, or of a sheet: its width at most, never wider than the screen. */
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
    <div className="flex min-w-0 max-w-full flex-col gap-1">
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
          "min-h-touch max-w-full rounded-control border border-hairline bg-surface-1 px-4 text-small text-ink placeholder:text-ink-subtle",
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
        className="min-h-touch rounded-control border border-hairline bg-surface-1 px-4 text-small text-ink"
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
        <li key={`${event.at}-${index}`} className="flex flex-wrap gap-x-2">
          <time dateTime={event.at} className="tabular text-ink-subtle">
            {formatDateTime(event.at)}
          </time>
          <span className="font-medium">
            {event.from ? `${word(event.from)} → ` : ""}
            {word(event.to)}
          </span>
          <span className="text-ink-subtle">{word(event.reason)}</span>
        </li>
      ))}
    </ol>
  );
}
