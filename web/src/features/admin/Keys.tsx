import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { useLocation } from "wouter";
import { KeyValueList } from "@/components/ui/KeyValues";
import { Button } from "@/components/ui/Button";
import { HANDBOOK, RUNBOOKS, runbookUrl } from "./runbooks";
import { Sheet } from "@/components/ui/Sheet";

/**
 * Keyboard-first (ADR 0057): `g` then a letter goes to a screen, `/` searches the list you're on, `?` says every key on
 * this screen. A list's keys work on whichever table was last touched, or the first on the page; a screen adds its own
 * with `useScreenKeys`. None of them fire while you're typing or while a sheet is open.
 */

/** Where `g` then a letter goes. */
export const GO_KEYS: Record<string, { href: string; label: string }> = {
  i: { href: "/admin/inbox/", label: "the inbox" },
  u: { href: "/admin/users/", label: "users" },
  h: { href: "/admin/hosts/", label: "hosts" },
  s: { href: "/admin/sessions/", label: "sessions" },
  m: { href: "/admin/money/", label: "money" },
  c: { href: "/admin/", label: "the control room" },
  a: { href: "/admin/approvals/", label: "approvals" },
};

/** What every list answers. */
export const LIST_KEYS: [string, string][] = [
  ["j, k", "Next, previous row"],
  ["Enter, r", "Open the row"],
  ["x", "Select the row, on a list with bulk actions"],
  ["Shift-x", "Select from the last one selected to here"],
];

export type ScreenKey = { key: string; what: string; run: () => void };

/** A table, as the keys drive it. */
export type ListHandle = {
  element: () => HTMLElement | null;
  press: (key: string) => boolean;
};

type Registry = {
  addList: (list: ListHandle) => () => void;
  touch: (list: ListHandle) => void;
  addScreenKeys: (keys: ScreenKey[]) => () => void;
  openHelp: () => void;
};

const Keys = createContext<Registry | null>(null);

export function isTyping(target: EventTarget | null): boolean {
  return (
    target instanceof HTMLElement &&
    (target.isContentEditable || ["INPUT", "TEXTAREA", "SELECT"].includes(target.tagName))
  );
}

/** Puts the cursor in the current list's search, or, on a screen with none, its first choice. */
function focusSearch(): boolean {
  const field =
    document.querySelector<HTMLElement>('main form[role="search"] input, main form[role="search"] select') ??
    document.querySelector<HTMLElement>('main select, main input:not([type="checkbox"])');
  field?.focus();
  return field !== null;
}

export function KeysProvider({ children }: { children: ReactNode }) {
  const [, navigate] = useLocation();
  const [help, setHelp] = useState(false);
  const lists = useRef<ListHandle[]>([]);
  const touched = useRef<ListHandle | null>(null);
  const [screenKeys, setScreenKeys] = useState<ScreenKey[]>([]);
  const going = useRef(false);
  const goingTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  const registry = useMemo<Registry>(
    () => ({
      addList: (list) => {
        lists.current = [...lists.current, list];
        return () => {
          lists.current = lists.current.filter((each) => each !== list);
          if (touched.current === list) {
            touched.current = null;
          }
        };
      },
      touch: (list) => {
        touched.current = list;
      },
      addScreenKeys: (keys) => {
        setScreenKeys((now) => [...now, ...keys]);
        return () => setScreenKeys((now) => now.filter((each) => !keys.includes(each)));
      },
      openHelp: () => setHelp(true),
    }),
    [],
  );

  const currentList = useCallback((): ListHandle | undefined => {
    if (touched.current) {
      return touched.current;
    }
    return [...lists.current].sort((one, other) => {
      const first = one.element();
      const second = other.element();
      if (!first || !second) {
        return 0;
      }
      return first.compareDocumentPosition(second) & Node.DOCUMENT_POSITION_FOLLOWING ? -1 : 1;
    })[0];
  }, []);

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.ctrlKey || event.metaKey || event.altKey || isTyping(event.target)) {
        return;
      }
      // A sheet has the keyboard while it's open; it answers Esc itself.
      if (document.querySelector('[role="dialog"]')) {
        return;
      }
      if (going.current) {
        going.current = false;
        clearTimeout(goingTimer.current);
        const where = GO_KEYS[event.key];
        if (where) {
          event.preventDefault();
          navigate(where.href);
        }
        return;
      }
      if (event.key === "g") {
        going.current = true;
        goingTimer.current = setTimeout(() => {
          going.current = false;
        }, 1_500);
        return;
      }
      if (event.key === "?") {
        event.preventDefault();
        setHelp(true);
        return;
      }
      if (event.key === "/") {
        if (focusSearch()) {
          event.preventDefault();
        }
        return;
      }
      const own = screenKeys.find((each) => each.key === event.key);
      if (own) {
        event.preventDefault();
        own.run();
        return;
      }
      // Enter on a focused control is that control's, not the row's.
      if (event.key === "Enter" && event.target instanceof HTMLElement && event.target !== document.body) {
        const focused = event.target.closest("a, button, summary, [role='button'], [role='tab']");
        if (focused) {
          return;
        }
      }
      if (currentList()?.press(event.key)) {
        event.preventDefault();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => {
      window.removeEventListener("keydown", onKey);
      clearTimeout(goingTimer.current);
    };
  }, [currentList, navigate, screenKeys]);

  return (
    <Keys.Provider value={registry}>
      {children}
      <KeysPanel open={help} onOpenChange={setHelp} screenKeys={screenKeys} />
    </Keys.Provider>
  );
}

function KeyLines({ title, lines }: { title: string; lines: [string, string][] }) {
  return (
    <section className="flex flex-col gap-1">
      <h3 className="text-small font-semibold">{title}</h3>
      <KeyValueList items={lines} />
    </section>
  );
}

/** The `?` panel: every key on this screen, and the runbook behind it. */
function KeysPanel({
  open,
  onOpenChange,
  screenKeys,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  screenKeys: ScreenKey[];
}) {
  const [path] = useLocation();
  const runbook = RUNBOOKS[path];
  return (
    <Sheet open={open} onOpenChange={onOpenChange} title="Keys and help">
      {screenKeys.length > 0 && (
        <KeyLines title="This screen" lines={screenKeys.map((each) => [each.key, each.what])} />
      )}
      <KeyLines title="Any list" lines={LIST_KEYS} />
      <KeyLines
        title="Anywhere"
        lines={[
          ...Object.entries(GO_KEYS).map(([key, where]): [string, string] => [
            `g then ${key}`,
            `Go to ${where.label}`,
          ]),
          ["/", "Search the current list"],
          ["Ctrl K", "Find anything"],
          ["?", "This panel"],
        ]}
      />
      <section className="flex flex-col gap-1 text-small">
        <h3 className="font-semibold">Runbooks</h3>
        {runbook && (
          <a href={runbookUrl(runbook.file)} className="underline underline-offset-4">
            For this screen: {runbook.title}
          </a>
        )}
        <a href={runbookUrl(HANDBOOK.file)} className="underline underline-offset-4">
          {HANDBOOK.title}
        </a>
      </section>
      <Button variant="ghost" onClick={() => onOpenChange(false)}>
        Close
      </Button>
    </Sheet>
  );
}

/** The button that opens the panel, for a phone or a mouse. */
export function HelpButton() {
  const keys = useContext(Keys);
  return (
    <Button variant="ghost" onClick={() => keys?.openHelp()} className="justify-between">
      Keys and help
      <kbd className="hidden text-caption text-text-3 md:inline">?</kbd>
    </Button>
  );
}

/** The record the list's cursor is on, for a screen's own keys. */
export function activeRowKey(): string | null {
  return document.querySelector<HTMLElement>('main tr[aria-current="true"]')?.dataset.rowKey ?? null;
}

/** A screen's own keys, while it's showing. The array should be stable, or be remade only when its actions change. */
export function useScreenKeys(keys: ScreenKey[]): void {
  const registry = useContext(Keys);
  useEffect(() => registry?.addScreenKeys(keys), [registry, keys]);
}

/** Registers a table with the keys. */
export function useListRegistration(list: ListHandle): { touch: () => void } {
  const registry = useContext(Keys);
  useEffect(() => registry?.addList(list), [registry, list]);
  return useMemo(() => ({ touch: () => registry?.touch(list) }), [registry, list]);
}
