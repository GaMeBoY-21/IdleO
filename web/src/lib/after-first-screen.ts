import { startTransition, useEffect, useState } from "react";

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

/** The app's font has loaded, or a second has passed: until then, text may not be drawn yet (font-display). */
function fontLoaded(): Promise<unknown> {
  if (typeof document === "undefined" || !("fonts" in document)) {
    return Promise.resolve();
  }
  // `document.fonts.ready` can resolve before any text has asked for the preloaded font; loading it by name waits.
  return Promise.race([
    document.fonts.load("1em Plus Jakarta Sans").catch(() => undefined),
    new Promise((resolve) => setTimeout(resolve, 1_000)),
  ]);
}

/**
 * The page's first contentful paint is on screen, or a second has passed, or the browser can't say. A signed-in
 * screen's pre-rendered frame holds no text, so on a fresh page its first contentful paint is its content. The
 * browser reports it once it's presented, which on a slow machine comes after two animation frames: a request
 * started at the second frame was still counted against the paint (Book on CI: 2.14 s instead of 1.89 s).
 */
function firstContentPresented(): Promise<void> {
  if (
    typeof PerformanceObserver === "undefined" ||
    !PerformanceObserver.supportedEntryTypes.includes("paint") ||
    performance.getEntriesByName("first-contentful-paint").length > 0
  ) {
    return Promise.resolve();
  }
  return new Promise((resolve) => {
    const done = () => {
      observer.disconnect();
      clearTimeout(timer);
      resolve();
    };
    const observer = new PerformanceObserver((list) => {
      if (list.getEntriesByName("first-contentful-paint").length > 0) {
        done();
      }
    });
    const timer = setTimeout(done, 1_000);
    observer.observe({ type: "paint", buffered: true });
  });
}

/**
 * Runs `work` once what's on screen now has painted: its text drawn in the app's font, the page's first contentful
 * paint presented, and two frames after that. Returns a cancel. The font matters: until it's there, for a first
 * frame or two, text isn't drawn at all, so two frames after the content mounts can still be before its text has
 * painted.
 */
export function afterPaint(work: () => void): () => void {
  let cancelled = false;
  let frame = 0;
  let timer = 0;
  void Promise.all([fontLoaded(), firstContentPresented()]).then(() => {
    if (cancelled) {
      return;
    }
    frame = requestAnimationFrame(() => {
      frame = requestAnimationFrame(() => {
        timer = window.setTimeout(work, 0);
      });
    });
  });
  return () => {
    cancelled = true;
    cancelAnimationFrame(frame);
    clearTimeout(timer);
  };
}

/**
 * False until `ready` (the screen's main content is there) and that content has painted (afterPaint), then true.
 * For requests a screen doesn't need to paint its main content (a session's stream and logs, the price hints,
 * Book's form): started after the paint, they never hold it back (ADR 0018: what would compete with the first
 * screen waits).
 */
export function useAfterPaint(ready = true): boolean {
  const [painted, setPainted] = useState(false);
  // A transition: what this unlocks (Book's whole form) is drawn in slices of a few milliseconds, which the browser
  // can interleave with input, rather than in one long task that counts against total blocking time.
  useEffect(() => (ready ? afterPaint(() => startTransition(() => setPainted(true))) : undefined), [ready]);
  return painted;
}

/** False until the first screen has loaded (afterFirstScreen), then true. */
export function useAfterFirstScreen(): boolean {
  const [loaded, setLoaded] = useState(false);
  useEffect(() => afterFirstScreen(() => setLoaded(true)), []);
  return loaded;
}
