import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { Sheet } from "@/components/ui/Sheet";
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useId,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { useLocation } from "wouter";
import { Banner } from "@/components/ui/Banner";
import { Button } from "@/components/ui/Button";
import { Skeleton } from "@/components/ui/Skeleton";
import { problemMessage } from "@/lib/problems";
import { cn } from "@/lib/cn";
import { ADMIN, adminApi, adminData, type AdminSearchHit } from "./api";
import { recordHref } from "./links";
import { useCan } from "./permissions";

/** Each group's heading, in the words the console uses. */
const GROUP_NAMES: Record<string, string> = {
  user: "Users",
  host: "Hosts",
  session: "Sessions",
  payment: "Payments",
  refund: "Refunds",
  invoice: "Invoices",
  payout: "Payouts",
  ticket: "Tickets",
  image: "Images",
  upi: "UPI IDs",
  pan: "PANs",
};

/** An action the palette offers: going somewhere, or what the page behind it can do. */
export type PaletteAction = {
  label: string;
  /** What it needs; an admin without it doesn't see it. Empty for what's everyone's. */
  permission: string;
  run: () => void;
};

const PageActions = createContext<(actions: PaletteAction[]) => () => void>(() => () => undefined);

/**
 * What a page offers in the palette while it's open, by the same handlers its own buttons call: the same action sheet,
 * with its reason, step-up and approval, never a way round them.
 */
export function usePaletteActions(actions: PaletteAction[]): void {
  const register = useContext(PageActions);
  useEffect(() => register(actions), [register, actions]);
}

const RECENT_KEY = "idleo.admin.recent";
const MOST_RECENT = 8;

type Recent = Pick<AdminSearchHit, "page" | "id" | "title" | "detail">;

/** What this admin opened lately, as the server masked it, in this browser only. */
function readRecent(): Recent[] {
  try {
    const kept = JSON.parse(localStorage.getItem(RECENT_KEY) ?? "[]") as unknown;
    return Array.isArray(kept) ? (kept as Recent[]).slice(0, MOST_RECENT) : [];
  } catch {
    return [];
  }
}

function keepRecent(item: Recent): void {
  try {
    const others = readRecent().filter((other) => other.page !== item.page || other.id !== item.id);
    localStorage.setItem(RECENT_KEY, JSON.stringify([item, ...others].slice(0, MOST_RECENT)));
  } catch {
    // A browser that won't keep it just has no recent items.
  }
}

export function forgetRecent(): void {
  try {
    localStorage.removeItem(RECENT_KEY);
  } catch {
    // Nothing kept, then.
  }
}

/** Waits until typing has paused, so each pause is one search, and one line in the access log. */
function useSettled(text: string, wait: number): string {
  const [settled, setSettled] = useState(text);
  useEffect(() => {
    const timer = setTimeout(() => setSettled(text), wait);
    return () => clearTimeout(timer);
  }, [text, wait]);
  return settled;
}

type Option = { key: string; group: string; label: string; detail?: string | null; run: () => void };

/**
 * The one search box (ADR 0052): Cmd-K or Ctrl-K, keyboard all the way (arrows, enter, esc), grouped by kind, with recent
 * items and the console's actions. The server decides what's found, and masks it; a phone gets it as a full-screen sheet.
 */
export function PaletteProvider({
  screens,
  children,
}: {
  screens: readonly { href: string; label: string; needs: string }[];
  children: ReactNode;
}) {
  const [open, setOpen] = useState(false);
  const [pageActions, setPageActions] = useState<PaletteAction[]>([]);
  const register = useCallback((actions: PaletteAction[]) => {
    setPageActions(actions);
    return () => setPageActions((current) => (current === actions ? [] : current));
  }, []);
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "k") {
        event.preventDefault();
        setOpen((was) => !was);
      }
    };
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, []);
  return (
    <PageActions.Provider value={register}>
      <PaletteOpener.Provider value={() => setOpen(true)}>{children}</PaletteOpener.Provider>
      <Sheet open={open} onOpenChange={setOpen} title="Search the console" titleHidden fullOnPhone>
        {open && <PaletteBox screens={screens} pageActions={pageActions} close={() => setOpen(false)} />}
      </Sheet>
    </PageActions.Provider>
  );
}

const PaletteOpener = createContext<() => void>(() => undefined);

/** The button that opens it, for a phone or a mouse. */
export function PaletteButton() {
  const openPalette = useContext(PaletteOpener);
  return (
    <Button variant="secondary" onClick={openPalette} className="justify-between gap-4">
      Find anything
      <kbd className="hidden text-caption text-ink-tertiary md:inline">Ctrl K</kbd>
    </Button>
  );
}

function PaletteBox({
  screens,
  pageActions,
  close,
}: {
  screens: readonly { href: string; label: string; needs: string }[];
  pageActions: PaletteAction[];
  close: () => void;
}) {
  const can = useCan();
  const [, navigate] = useLocation();
  const [text, setText] = useState("");
  const [activeKey, setActiveKey] = useState<string | null>(null);
  const listId = useId();
  const list = useRef<HTMLDivElement>(null);
  const settled = useSettled(text.trim(), 150);
  const searching = settled.length >= 2;
  const found = useQuery({
    queryKey: [ADMIN, "search", settled],
    queryFn: () => adminData(adminApi.GET("/admin/v1/search", { params: { query: { q: settled } } })),
    enabled: searching,
    placeholderData: keepPreviousData,
    staleTime: 15_000,
    retry: false,
  });

  const options = useMemo<Option[]>(() => {
    const opening = (item: Recent) => () => {
      keepRecent(item);
      close();
      navigate(recordHref(item.page, item.id));
    };
    const wanted = text.trim().toLowerCase();
    const actions: Option[] = [
      ...pageActions
        .filter((action) => can(action.permission))
        .map((action) => ({
          key: `page:${action.label}`,
          group: "This page",
          label: action.label,
          run: () => {
            close();
            // The palette's focus goes back first, so the action's own sheet can take it.
            setTimeout(action.run, 0);
          },
        })),
      ...screens
        .filter((screen) => can(screen.needs))
        .map((screen) => ({
          key: `go:${screen.href}`,
          group: "Go to",
          label: screen.href === "/admin/approvals/" ? "Open approvals" : `Go to ${screen.label}`,
          run: () => {
            close();
            navigate(screen.href);
          },
        })),
    ].filter((action) => wanted === "" || action.label.toLowerCase().includes(wanted));
    if (wanted === "") {
      const recent = readRecent().map((item) => ({
        key: `recent:${item.page}:${item.id}`,
        group: "Recent",
        label: item.title,
        detail: item.detail,
        run: opening(item),
      }));
      return [...recent, ...actions];
    }
    const hits = (searching ? (found.data?.groups ?? []) : []).flatMap((group) =>
      group.hits.map((hit) => ({
        key: `${group.type}:${hit.page}:${hit.id}`,
        group: GROUP_NAMES[group.type] ?? group.type,
        label: hit.title,
        detail: hit.detail,
        run: opening(hit),
      })),
    );
    return [...hits, ...actions];
  }, [can, close, found.data, navigate, pageActions, screens, searching, text]);

  // A list without the one chosen starts at its top.
  const current = Math.max(
    options.findIndex((option) => option.key === activeKey),
    0,
  );
  const move = (by: number) => {
    const next = options[(current + by + options.length) % Math.max(options.length, 1)];
    setActiveKey(next?.key ?? null);
  };
  useEffect(() => {
    list.current?.querySelector('[aria-selected="true"]')?.scrollIntoView({ block: "nearest" });
  }, [current]);

  const groups = useMemo(() => {
    const byName = new Map<string, Option[]>();
    options.forEach((option) => byName.set(option.group, [...(byName.get(option.group) ?? []), option]));
    return [...byName.entries()];
  }, [options]);
  const unanswered = searching ? (found.data?.unanswered ?? []) : [];

  return (
    <>
      <input
        role="combobox"
        aria-expanded="true"
        aria-controls={listId}
        aria-activedescendant={options[current] ? `${listId}-${current}` : undefined}
        aria-label="Search the console"
        placeholder="A person, a host, a session, a payment…"
        value={text}
        onChange={(event) => setText(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === "ArrowDown") {
            event.preventDefault();
            move(1);
          } else if (event.key === "ArrowUp") {
            event.preventDefault();
            move(-1);
          } else if (event.key === "Enter") {
            event.preventDefault();
            options[current]?.run();
          }
        }}
        className="min-h-touch border-b border-hairline bg-transparent px-2 text-card text-ink placeholder:text-ink-tertiary md:min-h-10"
      />
      {found.isError && searching && <Banner tone="danger">{problemMessage(found.error)}</Banner>}
      {unanswered.length > 0 && (
        <p className="text-caption text-ink-subtle">
          These didn&apos;t answer in time, so there may be more:{" "}
          {unanswered.map((type) => GROUP_NAMES[type] ?? type).join(", ")}.
        </p>
      )}
      <div
        ref={list}
        id={listId}
        role="listbox"
        aria-label="Results"
        className="min-h-0 flex-1 overflow-y-auto"
      >
        {searching && found.isPending ? (
          <div role="status" aria-label="Searching" className="flex flex-col gap-2">
            <Skeleton className="h-11 w-full" />
            <Skeleton className="h-11 w-full" />
          </div>
        ) : options.length === 0 ? (
          <p className="px-4 py-4 text-small text-ink-subtle">Nothing found for that.</p>
        ) : (
          groups.map(([name, members]) => (
            <div key={name} role="group" aria-label={name} className="flex flex-col pb-2">
              <p aria-hidden="true" className="px-2 pt-2 pb-1 text-eyebrow text-ink-tertiary">
                {name}
              </p>
              {members.map((option) => {
                const index = options.indexOf(option);
                return (
                  <button
                    type="button"
                    tabIndex={-1}
                    key={option.key}
                    id={`${listId}-${index}`}
                    role="option"
                    aria-selected={index === current}
                    onMouseMove={() => setActiveKey(option.key)}
                    onClick={option.run}
                    className={cn(
                      "flex min-h-touch w-full cursor-pointer flex-col justify-center rounded-control px-2 text-left text-button md:min-h-9",
                      index === current ? "bg-surface-3 text-ink" : "text-ink-muted",
                    )}
                  >
                    {option.label}
                    {option.detail && <span className="text-caption text-ink-subtle">{option.detail}</span>}
                  </button>
                );
              })}
            </div>
          ))
        )}
      </div>
      <p className="hidden items-center gap-4 border-t border-hairline pt-2 text-caption text-ink-tertiary md:flex">
        <span>
          <kbd>↑</kbd> <kbd>↓</kbd> move
        </span>
        <span>
          <kbd>↵</kbd> open
        </span>
        <span>
          <kbd>Esc</kbd> close
        </span>
      </p>
    </>
  );
}
