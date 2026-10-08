import { useSyncExternalStore } from "react";

/**
 * One clock for the whole app (web/DESIGN.md "Times"): every relative time on screen moves on together every 30
 * seconds, and every elapsed time every second, from a single timer, not one per component. The timer runs only
 * while something on screen reads the clock, at the finest resolution anything asks for, and lands on the boundary
 * (the next whole second, the next half minute) so every reader changes in the same frame.
 */
export type Resolution = "second" | "half-minute";

const PERIOD: Record<Resolution, number> = { second: 1_000, "half-minute": 30_000 };

const listeners: Record<Resolution, Set<() => void>> = { second: new Set(), "half-minute": new Set() };
const snapshots: Record<Resolution, number> = { second: 0, "half-minute": 0 };
let timer: ReturnType<typeof setTimeout> | null = null;

const floor = (now: number, resolution: Resolution) => now - (now % PERIOD[resolution]);

function tick(): void {
  timer = null;
  const now = Date.now();
  for (const resolution of ["second", "half-minute"] as const) {
    const value = floor(now, resolution);
    if (value !== snapshots[resolution]) {
      snapshots[resolution] = value;
      listeners[resolution].forEach((listener) => listener());
    }
  }
  schedule();
}

function schedule(): void {
  const resolution: Resolution | null =
    listeners.second.size > 0 ? "second" : listeners["half-minute"].size > 0 ? "half-minute" : null;
  if (timer !== null) {
    clearTimeout(timer);
    timer = null;
  }
  if (resolution !== null) {
    const now = Date.now();
    timer = setTimeout(tick, floor(now, resolution) + PERIOD[resolution] - now);
  }
}

function subscribe(resolution: Resolution, listener: () => void): () => void {
  listeners[resolution].add(listener);
  schedule();
  return () => {
    listeners[resolution].delete(listener);
    schedule();
  };
}

/** Now, to the resolution: unchanged between ticks, so React can compare it, and never older than one period. */
function read(resolution: Resolution): number {
  const now = floor(Date.now(), resolution);
  if (timer === null || now - snapshots[resolution] >= PERIOD[resolution]) {
    snapshots[resolution] = now;
  }
  return snapshots[resolution];
}

const subscribers = {
  second: (listener: () => void) => subscribe("second", listener),
  "half-minute": (listener: () => void) => subscribe("half-minute", listener),
};
const readers = { second: () => read("second"), "half-minute": () => read("half-minute") };

/** The current time in ms, re-rendering the component every `resolution`; one timer serves every caller. */
export function useNow(resolution: Resolution): number {
  return useSyncExternalStore(subscribers[resolution], readers[resolution], readers[resolution]);
}

/** How many timers are running: at most one, whatever is on screen (for tests). */
export const runningTimers = () => (timer === null ? 0 : 1);
