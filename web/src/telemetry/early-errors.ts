/**
 * JavaScript errors from the moment the page starts (ADR 0036), held until the reporter (`report.ts`) loads after the
 * first paint, then handed to it, and from then on passed straight through. A few lines, so the first load carries
 * almost nothing for it.
 */
export interface EarlyError {
  message: string;
  source: string;
  line: number;
  column: number;
  pathname: string;
}

const held: EarlyError[] = [];
let handler: ((error: EarlyError) => void) | null = null;

function record(error: EarlyError): void {
  if (handler) {
    handler(error);
  } else if (held.length < 10) {
    held.push(error);
  }
}

/** Starts listening; call once, as early as the page's own script runs. */
export function holdEarlyErrors(): void {
  window.addEventListener("error", (event) => {
    record({
      message: event.message || String(event.error ?? "Error"),
      source: event.filename || "",
      line: event.lineno || 0,
      column: event.colno || 0,
      pathname: window.location.pathname,
    });
  });
  window.addEventListener("unhandledrejection", (event) => {
    const reason: unknown = event.reason;
    record({
      message: `Unhandled rejection: ${reason instanceof Error ? reason.message : String(reason)}`,
      source: "",
      line: 0,
      column: 0,
      pathname: window.location.pathname,
    });
  });
}

/** Hands over what was held, and every later error. */
export function takeEarlyErrors(next: (error: EarlyError) => void): void {
  handler = next;
  for (const error of held.splice(0)) {
    next(error);
  }
}
