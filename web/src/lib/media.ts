import { useSyncExternalStore } from "react";
import { layout } from "@/design/tokens";

/**
 * Whether the window is at least a laptop's width (web/DESIGN.md "Laptop layout", 1200 px), or at least a tablet's
 * (768 px). For the screens whose composition differs by size, so the reading and focus order always match what's on
 * screen; pre-rendered frames never hold those screens' content, so the server's answer is never shown.
 */
const subscribeTo = (text: string) => (listener: () => void) => {
  const media = window.matchMedia(text);
  media.addEventListener("change", listener);
  return () => media.removeEventListener("change", listener);
};

const LAPTOP = `(min-width: ${layout.breakpoints.xl}px)`;
const TABLET = `(min-width: ${layout.breakpoints.md}px)`;
const subscribeLaptop = subscribeTo(LAPTOP);
const subscribeTablet = subscribeTo(TABLET);

export function useLaptop(): boolean {
  return useSyncExternalStore(
    subscribeLaptop,
    () => window.matchMedia(LAPTOP).matches,
    () => false,
  );
}

/** A tablet or wider: the screens that are tiles on a grid rather than a phone's column. */
export function useWide(): boolean {
  return useSyncExternalStore(
    subscribeTablet,
    () => window.matchMedia(TABLET).matches,
    () => false,
  );
}
