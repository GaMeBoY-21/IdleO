import { onCLS, onINP, onLCP, onTTFB, type Metric } from "web-vitals";
import { takeEarlyErrors, type EarlyError } from "./early-errors";

/**
 * What real visits meet (ADR 0036): Core Web Vitals (LCP, INP, CLS and TTFB) and JavaScript errors, sent to the
 * control plane's `POST /v1/vitals`, which turns them into meters and logs. Loaded after the first paint, so it never
 * slows the page it measures; the vitals are buffered by the browser from the start, and errors before then are kept
 * by `early-errors.ts`.
 *
 * Only the route (the path, ids taken out, never the query), the metric and its value, phone or laptop (by the
 * viewport) and the release go; never who, never the address. Batched and sent with `navigator.sendBeacon` as
 * plain text (no CORS preflight) when the page is hidden, and an error a few seconds after it happens.
 */
export interface ReportOptions {
  /** The control plane's address; `/v1/vitals` is added. */
  api: string;
  app: "app" | "site";
  release: string;
}

type Item =
  | { type: "vital"; name: Metric["name"]; value: number; route: string; device: Device }
  | { type: "error"; message: string; source: string; line: number; column: number; route: string };

type Device = "phone" | "laptop";

/** At most this many errors from one page, so a loop that throws can't flood anything. */
const MAX_ERRORS = 10;
/** The server's own bound on a batch's items. */
const MAX_ITEMS = 20;
const ERROR_DELAY_MS = 3000;
const ID = /^(?:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|[0-9a-f]{16,}|\d+)$/i;

/** The route a path is: query and hash dropped, every id-like part replaced, so a route never names a thing. */
export function routeOf(pathname: string): string {
  const parts = pathname.split("/").map((part) => (ID.test(part) ? ":id" : part));
  const route = parts.join("/").toLowerCase();
  return /^\/[a-z0-9/:._-]{0,80}$/.test(route) ? route : "/other";
}

/** Phone or laptop, by the viewport, at the app's own breakpoint for its wide layout (768 px). */
export function deviceOf(width: number): Device {
  return width < 768 ? "phone" : "laptop";
}

/** The page's own path for a script's address on this site, else the whole address with its query dropped. */
function sourceOf(url: string): string {
  try {
    const parsed = new URL(url, window.location.href);
    return parsed.origin === window.location.origin ? parsed.pathname : `${parsed.origin}${parsed.pathname}`;
  } catch {
    return "";
  }
}

export function startReporting(options: ReportOptions): void {
  const endpoint = `${options.api.replace(/\/$/, "")}/v1/vitals`;
  const queue: Item[] = [];
  let errors = 0;
  let errorTimer: ReturnType<typeof setTimeout> | null = null;

  const flush = () => {
    if (errorTimer !== null) {
      clearTimeout(errorTimer);
      errorTimer = null;
    }
    while (queue.length > 0) {
      const items = queue.splice(0, MAX_ITEMS);
      const body = JSON.stringify({ app: options.app, release: options.release, items });
      const blob = new Blob([body], { type: "text/plain;charset=UTF-8" });
      if (!navigator.sendBeacon(endpoint, blob)) {
        void fetch(endpoint, { method: "POST", body: blob, keepalive: true, mode: "cors" }).catch(
          () => undefined,
        );
      }
    }
  };

  const vital = (metric: Metric) => {
    queue.push({
      type: "vital",
      name: metric.name,
      value: metric.value,
      route: routeOf(window.location.pathname),
      device: deviceOf(window.innerWidth),
    });
  };

  const error = (early: EarlyError) => {
    if (errors >= MAX_ERRORS) {
      return;
    }
    errors += 1;
    queue.push({
      type: "error",
      message: early.message.slice(0, 300),
      source: sourceOf(early.source),
      line: early.line,
      column: early.column,
      route: routeOf(early.pathname),
    });
    errorTimer ??= setTimeout(flush, ERROR_DELAY_MS);
  };

  takeEarlyErrors(error);
  onLCP(vital);
  onINP(vital);
  onCLS(vital);
  onTTFB(vital);
  document.addEventListener("visibilitychange", () => {
    if (document.visibilityState === "hidden") {
      flush();
    }
  });
  window.addEventListener("pagehide", flush);
}
