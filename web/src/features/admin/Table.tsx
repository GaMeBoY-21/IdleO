import { useVirtualizer } from "@tanstack/react-virtual";
import { NUMERIC } from "@/components/ui/DataTable";
import {
  Children,
  cloneElement,
  createContext,
  isValidElement,
  useCallback,
  useContext,
  useEffect,
  useLayoutEffect,
  useMemo,
  useRef,
  useState,
  type CSSProperties,
  type PointerEvent as ReactPointerEvent,
  type ReactElement,
  type ReactNode,
} from "react";
import { Money } from "@/components/ui/Money";
import { cn } from "@/lib/cn";
import { useBulkActions, BulkBar } from "./Bulk";
import { useListRegistration } from "./Keys";
import { parseSort, useTableView } from "./listView";
import { columnId, displayOrder, useTableSettings, type TableSettings } from "./tableSettings";

/**
 * The console's one table (ADR 0057). Every list gets the same things here: columns that resize, reorder and pin, a row
 * density, a sticky header, sorting by several columns, the list's keys, and windowed rendering, so 10,000 rows scroll
 * smoothly. How an admin arranges a table is remembered for them on the server; which columns show stays in the address,
 * with the rest of a view. A table with a `list` also selects rows for that list's bulk actions.
 */

/** From this many rows, only those near the view are in the page. */
export const WINDOW_FROM = 100;

/** The most rows one bulk action can take (BulkActions.MOST_ITEMS). */
const MOST_SELECTED = 500;

/** How far one scroll event moves for it to count as flicking past: more than a reader's screenful of rows at once. */
const FLICK_PX = 400;

const MIN_WIDTH = 48;

/** A row's tick box, its whole cell a touch target on a phone. */
const TICK = "flex min-h-touch min-w-touch cursor-pointer items-center justify-center md:min-h-8 md:min-w-8";
const WIDTH_STEP = 24;

type Shape = {
  order: readonly number[];
  pins: ReadonlyMap<number, number>;
  compact: boolean;
  /** The columns of numbers, aligned right in tabular figures, heading and cells (`numberColumns`). */
  numeric: ReadonlySet<number>;
};

const TableShape = createContext<Shape>({ order: [], pins: new Map(), compact: false, numeric: new Set() });

// A cell's text that is a number: money, a count, a share or a size ("₹1,200.00", "42", "−3", "12%", "4.5 GB").
const NUMBER_TEXT = /^[−+-]?₹?\d[\d,]*(?:\.\d+)?\s?(?:%|×|GB|MB|KB|min|h|s|ms)?$/;

/** What a cell holds, as far as aligning it goes: a number, nothing ("—" or empty), or anything else. */
function cellKind(node: ReactNode): "number" | "empty" | "other" {
  if (node === null || node === undefined || typeof node === "boolean") {
    return "empty";
  }
  if (typeof node === "number" || typeof node === "bigint") {
    return "number";
  }
  if (typeof node === "string") {
    const text = node.trim();
    return text === "" || text === "—" ? "empty" : NUMBER_TEXT.test(text) ? "number" : "other";
  }
  if (Array.isArray(node)) {
    const kinds = (node as ReactNode[]).map(cellKind);
    return kinds.includes("other") ? "other" : kinds.includes("number") ? "number" : "empty";
  }
  if (isValidElement(node)) {
    if (node.type === Money) {
      return "number";
    }
    const children = (node.props as { children?: ReactNode }).children;
    return children === undefined ? "other" : cellKind(children);
  }
  return "other";
}

/**
 * Which columns hold numbers, read from the rows the table is given, so every table aligns its numbers right without
 * each one saying so: a column is one when its cells are all money, counts or empty, and at least one isn't empty.
 */
function numberColumns(rows: readonly ReactNode[], width: number): ReadonlySet<number> {
  const kinds: ("number" | "empty" | "other")[] = Array.from({ length: width }, () => "empty");
  for (const row of rows.slice(0, 200)) {
    if (!isValidElement(row) || row.type !== Row) {
      continue;
    }
    Children.toArray((row.props as { children?: ReactNode }).children).forEach((cell, index) => {
      if (index >= width || kinds[index] === "other") {
        return;
      }
      const kind = cellKind(cell);
      if (kind !== "empty") {
        kinds[index] = kind;
      }
    });
  }
  return new Set(kinds.flatMap((kind, index) => (kind === "number" ? [index] : [])));
}

type RowState = {
  rowKey: string | null;
  index: number;
  active: boolean;
  selectable: boolean;
  selected: boolean;
  toggle: () => void;
};

const ThisRow = createContext<RowState>({
  rowKey: null,
  index: -1,
  active: false,
  selectable: false,
  selected: false,
  toggle: () => undefined,
});

/** A row's React key, which is its record's id, without what Children.toArray adds. */
function keyOf(child: ReactNode): string | null {
  return isValidElement(child) && typeof child.key === "string" ? child.key.replace(/^\.\$?/, "") : null;
}

export function Table({
  caption,
  columns,
  sorts,
  list,
  children,
}: {
  caption: string;
  columns: string[];
  /** A column's order on the server, by its heading: its header then sorts, and the address keeps it. */
  sorts?: Record<string, string>;
  /** The server's name for this list, when it's one of the list engine's: its bulk actions, and its settings' name. */
  list?: string;
  children: ReactNode;
}) {
  const view = useTableView();
  const [settings, setSettings] = useTableSettings(list ?? caption);
  const order = useMemo(() => displayOrder(columns, settings, view.hidden), [columns, settings, view.hidden]);
  const rows = useMemo(() => Children.toArray(children), [children]);
  const windowed = rows.length >= WINDOW_FROM;
  const compact = settings.density === "compact";

  const scroller = useRef<HTMLDivElement>(null);
  const headers = useRef(new Map<number, HTMLTableCellElement>());
  const [pinWidths, setPinWidths] = useState<ReadonlyMap<number, number>>(new Map());
  const pinned = useMemo(() => {
    const ids = columns.map(columnId);
    return order.filter((index) => settings.pinned.includes(ids[index] ?? ""));
  }, [columns, order, settings.pinned]);
  useLayoutEffect(() => {
    const next = new Map<number, number>();
    let left = 0;
    pinned.forEach((index) => {
      next.set(index, left);
      left += headers.current.get(index)?.offsetWidth ?? 0;
    });
    setPinWidths((now) =>
      now.size === next.size && [...next].every(([index, at]) => now.get(index) === at) ? now : next,
    );
  }, [pinned, settings.widths, rows.length]);
  const numeric = useMemo(() => numberColumns(rows, columns.length), [rows, columns.length]);
  const shape = useMemo<Shape>(
    () => ({ order, pins: pinWidths, compact, numeric }),
    [order, pinWidths, compact, numeric],
  );

  // While a long list flicks past faster than anyone can read, its rows are plain placeholders: building a whole screen
  // of real rows every frame is what makes a slow machine stutter. They come back as soon as it slows.
  const [flicking, setFlicking] = useState(false);
  const lastOffset = useRef(0);
  // React Compiler skips this component for it, which is what it needs: the virtualizer changes on every scroll.
  // eslint-disable-next-line react-hooks/incompatible-library
  const virtual = useVirtualizer({
    count: rows.length,
    getScrollElement: () => scroller.current,
    estimateSize: () => (compact ? 36 : 56),
    overscan: 6,
    enabled: windowed,
    onChange: (instance) => {
      const offset = instance.scrollOffset ?? 0;
      const moved = Math.abs(offset - lastOffset.current);
      lastOffset.current = offset;
      setFlicking(instance.isScrolling && moved > FLICK_PX);
    },
  });

  const actions = useBulkActions(list);
  const selectable = actions.length > 0;
  const [selected, setSelected] = useState<ReadonlySet<string>>(new Set());
  const [active, setActive] = useState(-1);
  const anchor = useRef<number | null>(null);
  const keys = useMemo(() => rows.map(keyOf), [rows]);

  const toggle = useCallback((key: string) => {
    setSelected((now) => {
      const next = new Set(now);
      if (next.has(key)) {
        next.delete(key);
      } else if (next.size < MOST_SELECTED) {
        next.add(key);
      }
      return next;
    });
  }, []);

  const press = (key: string) => {
    if (rows.length === 0) {
      return false;
    }
    const move = (to: number) => {
      setActive(to);
      if (windowed) {
        virtual.scrollToIndex(to, { align: "auto" });
      } else {
        scroller.current?.querySelector(`[data-row-index="${to}"]`)?.scrollIntoView({ block: "nearest" });
      }
    };
    switch (key) {
      case "j":
        move(Math.min(active + 1, rows.length - 1));
        return true;
      case "k":
        move(Math.max(active - 1, 0));
        return true;
      case "x": {
        const at = Math.max(active, 0);
        const rowKey = keys[at];
        if (selectable && rowKey) {
          setActive(at);
          toggle(rowKey);
          anchor.current = at;
          return true;
        }
        return false;
      }
      case "X": {
        if (selectable && anchor.current !== null && active >= 0) {
          const [from, to] = anchor.current < active ? [anchor.current, active] : [active, anchor.current];
          setSelected(
            (now) =>
              new Set([
                ...now,
                ...keys
                  .slice(from, to + 1)
                  .filter((each): each is string => each !== null)
                  .slice(0, MOST_SELECTED - now.size),
              ]),
          );
          return true;
        }
        return false;
      }
      case "Enter":
      case "r": {
        const link = scroller.current?.querySelector<HTMLAnchorElement>(
          `[data-row-index="${Math.max(active, 0)}"] a`,
        );
        link?.click();
        return Boolean(link);
      }
      default:
        return false;
    }
  };
  const pressRef = useRef(press);
  useEffect(() => {
    pressRef.current = press;
  });
  const handle = useMemo(
    () => ({ element: () => scroller.current, press: (key: string) => pressRef.current(key) }),
    [],
  );
  const { touch } = useListRegistration(handle);

  const sortOrder = parseSort(view.sort);
  const everyKey = keys.filter((key): key is string => key !== null);
  const allSelected = everyKey.length > 0 && everyKey.every((key) => selected.has(key));

  const startResize = (event: ReactPointerEvent, index: number, name: string) => {
    event.preventDefault();
    event.stopPropagation();
    const start = event.clientX;
    const from = headers.current.get(index)?.offsetWidth ?? MIN_WIDTH;
    const header = headers.current.get(index);
    let width = from;
    const moved = (move: PointerEvent) => {
      width = Math.max(MIN_WIDTH, from + move.clientX - start);
      if (header) {
        header.style.width = `${width}px`;
        header.style.minWidth = `${width}px`;
      }
    };
    const done = () => {
      window.removeEventListener("pointermove", moved);
      window.removeEventListener("pointerup", done);
      setSettings({ ...settings, widths: { ...settings.widths, [columnId(name, index)]: width } });
    };
    window.addEventListener("pointermove", moved);
    window.addEventListener("pointerup", done);
  };

  const wrap = (child: ReactNode, index: number) => {
    const rowKey = keys[index] ?? null;
    return (
      <ThisRow.Provider
        key={rowKey ?? index}
        value={{
          rowKey,
          index,
          active: index === active,
          selectable,
          selected: rowKey !== null && selected.has(rowKey),
          toggle: () => {
            if (rowKey !== null) {
              anchor.current = index;
              toggle(rowKey);
            }
          },
        }}
      >
        {child}
      </ThisRow.Provider>
    );
  };

  const items = windowed ? virtual.getVirtualItems() : [];
  const firstStart = items[0]?.start ?? 0;
  const lastEnd = items[items.length - 1]?.end ?? 0;
  const colSpan = order.length + (selectable ? 1 : 0);

  return (
    <div className="flex min-w-0 flex-col gap-2">
      {selected.size > 0 && list !== undefined && (
        <BulkBar list={list} actions={actions} ids={[...selected]} onDone={() => setSelected(new Set())} />
      )}
      {columns.filter((column) => column !== "").length >= 3 && (
        <TableControls columns={columns} settings={settings} onChange={setSettings} caption={caption} />
      )}
      <div
        ref={scroller}
        className={cn(
          "relative overflow-auto rounded-card border border-hairline bg-surface-1 px-4 py-2",
          windowed ? "admin-table-window" : "md:admin-table-window",
        )}
        // A wide or long table scrolls; the keyboard must reach it.
        // eslint-disable-next-line jsx-a11y/no-noninteractive-tabindex
        tabIndex={0}
        role="region"
        aria-label={caption}
        onFocusCapture={touch}
        onPointerDownCapture={touch}
      >
        <table
          className="admin-table tabular w-full min-w-max border-collapse text-small"
          {...(windowed ? { "aria-rowcount": rows.length + 1 } : {})}
        >
          <caption className="sr-only">{caption}</caption>
          <thead>
            <tr className="border-b border-hairline text-left text-caption text-ink-tertiary">
              {selectable && (
                <th scope="col" className="sticky top-0 z-sticky bg-surface-1 px-0 py-0">
                  <label className={TICK}>
                    <input
                      type="checkbox"
                      aria-label={`Select every row shown, up to ${MOST_SELECTED}`}
                      checked={allSelected}
                      onChange={() =>
                        setSelected(allSelected ? new Set() : new Set(everyKey.slice(0, MOST_SELECTED)))
                      }
                    />
                  </label>
                </th>
              )}
              {order.map((index) => {
                const column = columns[index] ?? "";
                const key = sorts?.[column];
                const at = key ? sortOrder.findIndex((part) => part.key === key) : -1;
                const now = at >= 0 ? sortOrder[at]?.direction : undefined;
                const width = settings.widths[columnId(column, index)];
                const pin = pinWidths.get(index);
                return (
                  <th
                    key={`${column}-${index}`}
                    ref={(element) => {
                      if (element) {
                        headers.current.set(index, element);
                      } else {
                        headers.current.delete(index);
                      }
                    }}
                    scope="col"
                    aria-sort={at === 0 ? (now === "asc" ? "ascending" : "descending") : undefined}
                    style={{
                      ...(width === undefined ? {} : { width, minWidth: width }),
                      ...(pin === undefined ? {} : { left: pin }),
                    }}
                    className={cn(
                      "group sticky top-0 bg-surface-1 px-2 py-2 font-semibold",
                      numeric.has(index) && "text-right",
                      pin === undefined ? "z-raised" : "z-sticky",
                    )}
                  >
                    {key ? (
                      <button
                        type="button"
                        title="Shift-click to sort by this as well"
                        onClick={(event) => view.sortBy(key, event.shiftKey)}
                        className="-my-2 inline-flex min-h-touch items-center underline md:min-h-0"
                      >
                        {column}
                        {now === "asc" ? " ↑" : now === "desc" ? " ↓" : ""}
                        {at >= 0 && sortOrder.length > 1 ? ` ${at + 1}` : ""}
                      </button>
                    ) : (
                      column
                    )}
                    {column !== "" && (
                      <span
                        aria-hidden="true"
                        onPointerDown={(event) => startResize(event, index, column)}
                        className="absolute inset-y-0 right-0 w-2 cursor-col-resize opacity-0 group-hover:bg-hairline group-hover:opacity-100"
                      />
                    )}
                  </th>
                );
              })}
            </tr>
          </thead>
          <TableShape.Provider value={shape}>
            {windowed ? (
              <>
                <tbody aria-hidden="true">
                  <tr style={{ height: firstStart }} />
                </tbody>
                {items.map((item) =>
                  flicking ? (
                    <tbody key={item.key} aria-hidden="true">
                      <tr className="border-b border-hairline" style={{ height: item.size }}>
                        <td colSpan={colSpan} />
                      </tr>
                    </tbody>
                  ) : (
                    <tbody key={item.key} data-index={item.index} ref={virtual.measureElement}>
                      {wrap(rows[item.index], item.index)}
                    </tbody>
                  ),
                )}
                <tbody aria-hidden="true">
                  <tr style={{ height: Math.max(0, virtual.getTotalSize() - lastEnd) }}>
                    <td colSpan={colSpan} />
                  </tr>
                </tbody>
              </>
            ) : (
              <tbody>{rows.map((child, index) => wrap(child, index))}</tbody>
            )}
          </TableShape.Provider>
        </table>
      </div>
    </div>
  );
}

export function Row({ children, className }: { children: ReactNode; className?: string }) {
  const { order, pins, numeric } = useContext(TableShape);
  const row = useContext(ThisRow);
  const cells = Children.toArray(children);
  // A row that doesn't fill the table's columns (one cell spanning them) stays as it was written.
  const shown =
    cells.length === 0 || order.some((index) => index >= cells.length)
      ? cells
      : order.map((index) => {
          const cell = cells[index];
          const pin = pins.get(index);
          if ((pin === undefined && !numeric.has(index)) || !isValidElement(cell)) {
            return cell;
          }
          const element = cell as ReactElement<{ className?: string; style?: CSSProperties }>;
          return cloneElement(element, {
            className: cn(
              element.props.className,
              numeric.has(index) && NUMERIC,
              pin !== undefined && "sticky z-raised bg-surface-1",
            ),
            style: pin === undefined ? element.props.style : { ...element.props.style, left: pin },
          });
        });
  return (
    <tr
      {...(row.rowKey !== null ? { "data-row-key": row.rowKey } : {})}
      {...(row.index >= 0 ? { "data-row-index": row.index } : {})}
      aria-current={row.active ? "true" : undefined}
      className={cn(
        "border-b border-hairline align-top last:border-b-0",
        row.active && "bg-surface-3 outline-2 -outline-offset-2 outline-live",
        className,
      )}
    >
      {row.selectable && (
        <td className="px-0 py-0">
          <label className={TICK}>
            <input
              type="checkbox"
              aria-label={`Select this row${row.rowKey ? ` …${row.rowKey.slice(-8)}` : ""}`}
              checked={row.selected}
              onChange={row.toggle}
            />
          </label>
        </td>
      )}
      {shown}
    </tr>
  );
}

export function Cell({
  children,
  numeric = false,
  className,
  style,
}: {
  children: ReactNode;
  /** A number: aligned right in tabular figures, as every site's tables have them (components/ui/DataTable.tsx). */
  numeric?: boolean;
  className?: string;
  style?: CSSProperties;
}) {
  const { compact } = useContext(TableShape);
  return (
    <td style={style} className={cn("px-2", compact ? "py-1" : "py-2", numeric && NUMERIC, className)}>
      {children}
    </td>
  );
}

/** Which columns show (in the address, with the view), their order, widths and pins, and the rows' density. */
function TableControls({
  columns,
  settings,
  onChange,
  caption,
}: {
  columns: string[];
  settings: TableSettings;
  onChange: (next: TableSettings) => void;
  caption: string;
}) {
  const view = useTableView();
  const [open, setOpen] = useState(false);
  const details = useRef<HTMLDetailsElement>(null);
  const ids = columns.map(columnId);
  // The menu always shows every column, in the order it's shown, so a hidden one can come back.
  const order = displayOrder(columns, settings, new Set());
  const named = order.filter((index) => columns[index] !== "");
  const move = (index: number, by: -1 | 1) => {
    const at = order.indexOf(index);
    const next = [...order];
    const other = next[at + by];
    if (other === undefined) {
      return;
    }
    next[at + by] = index;
    next[at] = other;
    onChange({ ...settings, order: next.map((each) => ids[each] ?? "") });
  };
  const resize = (index: number, by: number) => {
    const id = ids[index] ?? "";
    const current = settings.widths[id] ?? 160;
    onChange({ ...settings, widths: { ...settings.widths, [id]: Math.max(MIN_WIDTH, current + by) } });
  };
  const pin = (index: number) => {
    const id = ids[index] ?? "";
    onChange({
      ...settings,
      pinned: settings.pinned.includes(id)
        ? settings.pinned.filter((each) => each !== id)
        : [...settings.pinned, id],
    });
  };
  const small = "min-h-6 min-w-6 rounded-control bg-surface-3 px-1 text-caption font-medium";
  return (
    <div className="flex flex-wrap items-center gap-2 text-small">
      <details ref={details} className="relative" onToggle={(event) => setOpen(event.currentTarget.open)}>
        <summary className="flex min-h-touch cursor-pointer items-center rounded-control px-4 font-medium md:min-h-9">
          Columns
        </summary>
        {/* Only while it's open, so a closed menu's column names aren't labels on the page beside the real fields. */}
        {open && (
          <fieldset className="absolute z-popover mt-1 flex flex-col gap-1 rounded-card border border-hairline bg-surface-1 p-4">
            <legend className="sr-only">Columns of {caption}</legend>
            {named.map((index) => {
              const column = columns[index] ?? "";
              const pinnedNow = settings.pinned.includes(ids[index] ?? "");
              return (
                <div key={ids[index]} className="flex items-center gap-1 whitespace-nowrap">
                  <label className="flex min-h-touch min-w-40 items-center gap-2 md:min-h-0">
                    <input
                      type="checkbox"
                      aria-label={`Show the ${column} column`}
                      checked={!view.hidden.has(column)}
                      onChange={() => view.toggleColumn(column)}
                    />
                    {column}
                  </label>
                  <button
                    type="button"
                    className={small}
                    aria-label={`Move ${column} earlier`}
                    onClick={() => move(index, -1)}
                  >
                    ↑
                  </button>
                  <button
                    type="button"
                    className={small}
                    aria-label={`Move ${column} later`}
                    onClick={() => move(index, 1)}
                  >
                    ↓
                  </button>
                  <button
                    type="button"
                    className={small}
                    aria-label={`Make ${column} narrower`}
                    onClick={() => resize(index, -WIDTH_STEP)}
                  >
                    −
                  </button>
                  <button
                    type="button"
                    className={small}
                    aria-label={`Make ${column} wider`}
                    onClick={() => resize(index, WIDTH_STEP)}
                  >
                    +
                  </button>
                  <button
                    type="button"
                    className={small}
                    aria-pressed={pinnedNow}
                    aria-label={`Pin ${column}`}
                    onClick={() => pin(index)}
                  >
                    {pinnedNow ? "Pinned" : "Pin"}
                  </button>
                </div>
              );
            })}
            <button
              type="button"
              className={cn(small, "mt-1 self-start")}
              onClick={() => onChange({ order: [], widths: {}, pinned: [], density: settings.density })}
            >
              Put the columns back
            </button>
          </fieldset>
        )}
      </details>
      <button
        type="button"
        aria-pressed={settings.density === "compact"}
        onClick={() =>
          onChange({ ...settings, density: settings.density === "compact" ? "comfortable" : "compact" })
        }
        className="flex min-h-touch items-center rounded-control px-4 font-medium md:min-h-9"
      >
        Compact rows
      </button>
    </div>
  );
}
