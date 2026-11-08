import { useInfiniteQuery } from "@tanstack/react-query";
import { createContext, useCallback, useContext, useMemo } from "react";
import { useLocation, useSearch } from "wouter";

/**
 * A list's address (ADR 0052): its filters, order and columns live in the URL, so any view of any list can be linked,
 * saved and shared. A value that's the list's default stays out of the address. A filter is never called `id` or `tab`:
 * those say which record's page, and which of its tabs, is open.
 */
export function useAddress(): [URLSearchParams, (change: (params: URLSearchParams) => void) => void] {
  const search = useSearch();
  const [path, navigate] = useLocation();
  const write = useCallback(
    (change: (params: URLSearchParams) => void) => {
      const params = new URLSearchParams(window.location.search);
      change(params);
      const query = params.toString();
      navigate(query ? `${path}?${query}` : path, { replace: true });
    },
    [navigate, path],
  );
  return [new URLSearchParams(search), write];
}

/**
 * Which list of the screen these are, when it has more than one: each keeps its order, columns and tag in the address
 * under its own name (`pans.sort`), so they don't fight over one `sort`.
 */
export const ListScope = createContext("");

/** A name in the address, for the list that's asking. */
function useScoped(): (name: string) => string {
  const scope = useContext(ListScope);
  return useCallback((name: string) => (scope ? `${scope}.${name}` : name), [scope]);
}

export { useScoped as useListName };

/**
 * A list whose rows come with what's around them (its runs, the roles): each page's answer, merged into one, for a
 * `QueryView`, which then offers "Show more".
 */
export function useListQuery<P extends { nextCursor?: string | null }>(
  key: readonly unknown[],
  field: keyof NoInfer<P>,
  read: (cursor: string | undefined) => Promise<P>,
  options: { enabled?: boolean; refetchInterval?: number } = {},
) {
  return useInfiniteQuery({
    queryKey: key,
    queryFn: ({ pageParam }) => read(pageParam === "" ? undefined : pageParam),
    initialPageParam: "",
    getNextPageParam: (page: P) => page.nextCursor ?? undefined,
    select: (data): P => ({
      ...data.pages[0]!,
      [field]: data.pages.flatMap((page) => page[field] as unknown[]),
    }),
    ...options,
  });
}

/** One filter in the address, used like `useState`. */
export function useListParam(name: string, initial: string): [string, (value: string) => void] {
  const [params, write] = useAddress();
  const set = useCallback(
    (value: string) =>
      write((next) => {
        if (value === initial) {
          next.delete(name);
        } else {
          next.set(name, value);
        }
      }),
    [initial, name, write],
  );
  return [params.get(name) ?? initial, set];
}

/** A whole search form's asked values in the address, used like `useState` of an object. */
export function useListParams<T extends Record<string, string>>(initial: T): [T, (values: T) => void] {
  const [params, write] = useAddress();
  const asked = Object.fromEntries(
    Object.entries(initial).map(([key, value]) => [key, params.get(key) ?? value]),
  ) as T;
  const set = useCallback(
    (values: T) =>
      write((next) => {
        Object.entries(values).forEach(([key, value]) => {
          if (value === initial[key]) {
            next.delete(key);
          } else {
            next.set(key, value);
          }
        });
      }),
    [initial, write],
  );
  return [asked, set];
}

/** What every list adds to its request: its order and its tag. */
export function useListView(): { sort?: string; tag?: string } {
  const [params] = useAddress();
  const name = useScoped();
  const sort = params.get(name("sort"));
  const tag = params.get(name("tag"));
  return {
    ...(sort ? { sort } : {}),
    ...(tag ? { tag } : {}),
  };
}

/** An order in the address: `state:asc,createdAt:desc`, most important first. */
export function parseSort(sort: string | null): { key: string; direction: "asc" | "desc" }[] {
  return (sort ?? "")
    .split(",")
    .filter(Boolean)
    .map((part) => {
      const [key = "", direction] = part.split(":");
      return { key, direction: direction === "asc" ? "asc" : "desc" };
    });
}

/** The most columns one order may hold (AdminListing.MOST_SORTS). */
const MOST_SORTS = 3;

/** The columns this view leaves out, and how a table's header sorts: alone, or, with Shift, as well as the others. */
export function useTableView(): {
  hidden: ReadonlySet<string>;
  sort: string | null;
  sortBy: (key: string, also?: boolean) => void;
  toggleColumn: (column: string) => void;
} {
  const [params, write] = useAddress();
  const name = useScoped();
  const sort = params.get(name("sort"));
  const hide = params.get(name("hide")) ?? "";
  const hidden = useMemo(() => new Set(hide.split(",").filter(Boolean)), [hide]);
  const sortBy = useCallback(
    (key: string, also = false) =>
      write((next) => {
        const parts = parseSort(next.get(name("sort")));
        const now = parts.find((part) => part.key === key);
        const flipped = now?.direction === "desc" ? ("asc" as const) : ("desc" as const);
        const order = also
          ? now
            ? parts.map((part) => (part.key === key ? { key, direction: flipped } : part))
            : [...parts, { key, direction: "desc" as const }].slice(0, MOST_SORTS)
          : [{ key, direction: flipped }];
        next.set(name("sort"), order.map((part) => `${part.key}:${part.direction}`).join(","));
      }),
    [name, write],
  );
  const toggleColumn = useCallback(
    (column: string) =>
      write((next) => {
        const now = new Set(next.get(name("hide"))?.split(",").filter(Boolean) ?? []);
        if (now.has(column)) {
          now.delete(column);
        } else {
          now.add(column);
        }
        if (now.size === 0) {
          next.delete(name("hide"));
        } else {
          next.set(name("hide"), [...now].join(","));
        }
      }),
    [name, write],
  );
  return { hidden, sort, sortBy, toggleColumn };
}
