import { useInfiniteQuery } from "@tanstack/react-query";
import { createContext, useCallback, useContext } from "react";
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

/** The columns this view leaves out, and how a table's header sorts. */
export function useTableView(): {
  hidden: ReadonlySet<string>;
  sort: string | null;
  sortBy: (key: string) => void;
} {
  const [params, write] = useAddress();
  const name = useScoped();
  const sort = params.get(name("sort"));
  const sortBy = useCallback(
    (key: string) =>
      write((next) => {
        const now = next.get(name("sort"));
        next.set(name("sort"), now === `${key}:desc` ? `${key}:asc` : `${key}:desc`);
      }),
    [name, write],
  );
  return { hidden: new Set(params.get(name("hide"))?.split(",") ?? []), sort, sortBy };
}
