import { useEffect, useState } from "react";

/**
 * Runs `work` once the first screen has had its chance to load: two seconds in, when the browser is next idle.
 * For fetching code that later screens and menus need, so it never competes with what's on screen now.
 */
export function afterFirstScreen(work: () => void): () => void {
  let idle: number | null = null;
  const timer = setTimeout(() => {
    if ("requestIdleCallback" in window) {
      idle = window.requestIdleCallback(work, { timeout: 2000 });
    } else {
      work();
    }
  }, 2000);
  return () => {
    clearTimeout(timer);
    if (idle !== null) {
      window.cancelIdleCallback(idle);
    }
  };
}

/**
 * False until `ready` (the screen's main content is there), its text drawn in the app's font, and two frames after
 * that, then true. For requests a screen doesn't need to paint its main content (a session's stream and logs, the
 * price hints, Book's form): started after the paint, they never hold it back (ADR 0018: what would compete with
 * the first screen waits). The font matters: until it's there, for a first frame or two, text isn't drawn at all,
 * so two frames after the content mounts can still be before its text has painted.
 */
export function useAfterPaint(ready = true): boolean {
  const [painted, setPainted] = useState(false);
  useEffect(() => {
    if (!ready) {
      return;
    }
    let cancelled = false;
    let frame = 0;
    let timer = 0;
    const fonts =
      typeof document !== "undefined" && "fonts" in document ? document.fonts.ready : Promise.resolve();
    void fonts.then(() => {
      if (cancelled) {
        return;
      }
      frame = requestAnimationFrame(() => {
        frame = requestAnimationFrame(() => {
          timer = window.setTimeout(() => setPainted(true), 0);
        });
      });
    });
    return () => {
      cancelled = true;
      cancelAnimationFrame(frame);
      clearTimeout(timer);
    };
  }, [ready]);
  return painted;
}

/** False until the first screen has loaded (afterFirstScreen), then true. */
export function useAfterFirstScreen(): boolean {
  const [loaded, setLoaded] = useState(false);
  useEffect(() => afterFirstScreen(() => setLoaded(true)), []);
  return loaded;
}
