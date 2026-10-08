import { useSyncExternalStore } from "react";
import { layout } from "@/design/tokens";

/**
 * Whether the window is at least a laptop's width (web/DESIGN.md "the layout grid", 1280 px). For the few screens
 * whose order differs by size, so the reading and focus order always match what's on screen; pre-rendered frames
 * never hold those screens' content, so the server's answer is never shown.
 */
const QUERY = `(min-width: ${layout.breakpoints.xl}px)`;

function subscribe(listener: () => void): () => void {
  const media = window.matchMedia(QUERY);
  media.addEventListener("change", listener);
  return () => media.removeEventListener("change", listener);
}

export function useLaptop(): boolean {
  return useSyncExternalStore(
    subscribe,
    () => window.matchMedia(QUERY).matches,
    () => false,
  );
}
