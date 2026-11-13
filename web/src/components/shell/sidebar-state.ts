import { useSyncExternalStore } from "react";

/**
 * Whether the sidebar is collapsed to its icons (web/DESIGN.md "Navigation"): a per-browser convenience, kept in local
 * storage, never a server setting. Where storage is blocked it lasts until the page is closed.
 */
const KEY = "idleo-sidebar";
const listeners = new Set<() => void>();
let collapsed = read();

function read(): boolean {
  try {
    return localStorage.getItem(KEY) === "icons";
  } catch {
    return false;
  }
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => void listeners.delete(listener);
}

export function toggleSidebar(): void {
  collapsed = !collapsed;
  try {
    localStorage.setItem(KEY, collapsed ? "icons" : "wide");
  } catch {
    // Storage is blocked: the choice lasts until the page is closed.
  }
  listeners.forEach((listener) => listener());
}

/** The sidebar's width follows this: `md:pl-sidebar` when wide, `md:pl-sidebar-icons` when collapsed. */
export function useSidebarCollapsed(): boolean {
  return useSyncExternalStore(
    subscribe,
    () => collapsed,
    () => false,
  );
}

export const sidebarPush = (isCollapsed: boolean) => (isCollapsed ? "md:pl-sidebar-icons" : "md:pl-sidebar");
