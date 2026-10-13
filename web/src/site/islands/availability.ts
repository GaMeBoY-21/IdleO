import type { PublicAvailability } from "../pricing";

/**
 * Asks GET /v1/public/availability for the pages that show live figures (ADR 0029), once the page has painted and
 * again each minute while it's visible. A failed refresh keeps the last figures and says so; "Try again" asks at once.
 * Plain script: nothing of the app is loaded here.
 */
export type Outcome =
  | { kind: "loaded"; data: PublicAvailability }
  | { kind: "stale"; data: PublicAvailability; failedAt: Date }
  | { kind: "failed" };

const REFRESH_MS = 60_000;

export function watchAvailability(api: string, onChange: (outcome: Outcome) => void): { retry: () => void } {
  let last: PublicAvailability | null = null;
  let timer: ReturnType<typeof setTimeout> | undefined;

  async function load(): Promise<void> {
    clearTimeout(timer);
    try {
      const response = await fetch(`${api}/v1/public/availability`, {
        headers: { Accept: "application/json" },
        signal: AbortSignal.timeout(10_000),
      });
      if (!response.ok) {
        throw new Error(`status ${response.status}`);
      }
      last = (await response.json()) as PublicAvailability;
      onChange({ kind: "loaded", data: last });
    } catch {
      onChange(last ? { kind: "stale", data: last, failedAt: new Date() } : { kind: "failed" });
    }
    timer = setTimeout(() => {
      if (document.visibilityState === "visible") {
        void load();
      } else {
        // Come back to it when the tab does.
        document.addEventListener("visibilitychange", () => void load(), { once: true });
      }
    }, REFRESH_MS);
  }

  // After the first paint, so the page never waits on it.
  requestAnimationFrame(() => setTimeout(() => void load(), 0));
  return { retry: () => void load() };
}

export const timeOfDay = (when: Date): string =>
  when.toLocaleTimeString("en-IN", { hour: "numeric", minute: "2-digit", hour12: true });
