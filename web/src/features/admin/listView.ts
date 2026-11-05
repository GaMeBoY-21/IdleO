import { useCallback } from "react";
import { useLocation, useSearch } from "wouter";

/**
 * A list's address (ADR 0052): its filters, order and columns live in the URL, so any view of any list can be linked,
 * saved and shared. A value that's the list's default stays out of the address.
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
  return {
    ...(params.get("sort") ? { sort: params.get("sort")! } : {}),
    ...(params.get("tag") ? { tag: params.get("tag")! } : {}),
  };
}

/** The columns this view leaves out, and how a table's header sorts. */
export function useTableView(): {
  hidden: ReadonlySet<string>;
  sort: string | null;
  sortBy: (key: string) => void;
} {
  const [params, write] = useAddress();
  const sort = params.get("sort");
  const sortBy = useCallback(
    (key: string) =>
      write((next) => {
        const now = next.get("sort");
        next.set("sort", now === `${key}:desc` ? `${key}:asc` : `${key}:desc`);
      }),
    [write],
  );
  return { hidden: new Set(params.get("hide")?.split(",") ?? []), sort, sortBy };
}
