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
