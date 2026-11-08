import { useQuery, useQueryClient } from "@tanstack/react-query";
import { useCallback } from "react";
import { ADMIN, adminApi, adminData, type Schemas } from "./api";

export type TableSettings = Schemas["TableSettings"];

export const NO_SETTINGS: TableSettings = { order: [], widths: {}, pinned: [], density: "comfortable" };

const KEY = [ADMIN, "table-settings"] as const;

type Tables = Schemas["AdminTableSettings"];

/** A column's name in the settings: its heading, or for one without, its place. */
export const columnId = (name: string, index: number) => name || `#${index}`;

/**
 * How this admin likes this table (ADR 0057), from the server, so it follows them from browser to browser. A change shows
 * at once and is saved behind it; if the save fails the table stays as it is until the next load.
 */
export function useTableSettings(table: string): [TableSettings, (next: TableSettings) => void] {
  const client = useQueryClient();
  const tables = useQuery({
    queryKey: KEY,
    queryFn: () => adminData(adminApi.GET("/admin/v1/table-settings")),
    staleTime: Number.POSITIVE_INFINITY,
    retry: false,
  });
  const settings = tables.data?.tables[table] ?? NO_SETTINGS;
  const change = useCallback(
    (next: TableSettings) => {
      client.setQueryData<Tables>(KEY, (now) => ({ tables: { ...now?.tables, [table]: next } }));
      void adminApi
        .PUT("/admin/v1/table-settings/{table}", { params: { path: { table } }, body: next })
        .catch(() => undefined);
    },
    [client, table],
  );
  return [settings, change];
}

/**
 * The columns in the order they show: pinned first, then as this admin ordered them, then any they haven't placed, as the
 * screen wrote them. Each is its place among the screen's own columns.
 */
export function displayOrder(
  columns: readonly string[],
  settings: TableSettings,
  hidden: ReadonlySet<string>,
): number[] {
  const ids = columns.map(columnId);
  const placed = [...settings.pinned, ...settings.order];
  const order: number[] = [];
  placed.forEach((id) => {
    const index = ids.indexOf(id);
    if (index >= 0 && !order.includes(index)) {
      order.push(index);
    }
  });
  columns.forEach((_, index) => {
    if (!order.includes(index)) {
      order.push(index);
    }
  });
  return order.filter((index) => !hidden.has(columns[index] ?? ""));
}
