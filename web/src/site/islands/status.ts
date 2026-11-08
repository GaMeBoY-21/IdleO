/**
 * The footer's status line (ADR 0056): whether IdleO is in maintenance, or any part of it is paused, from GET
 * /v1/public/status once the page has loaded, in the words the control plane gives. Its line keeps its height from the
 * start, so filling it moves nothing. Without an answer it says nothing rather than guess. Plain script, bundled with
 * the footer's Appearance switch, and importing nothing, so it adds no file of its own.
 */
export interface PublicStatus {
  maintenance: boolean;
  paused: { flag: string; message: string; until?: string | null }[];
}

/** What the line says for `status`; `fine` is the page's own words for nothing paused, with the product's name. */
export function statusLine(status: PublicStatus, fine: string): string {
  if (status.paused.length === 0) {
    return fine;
  }
  return status.paused.map((part) => part.message).join(" ");
}

const line = document.querySelector<HTMLElement>("[data-status]");
const api = line?.dataset.api;

if (line && api) {
  window.addEventListener(
    "load",
    () =>
      setTimeout(
        () =>
          void fetch(`${api}/v1/public/status`, {
            headers: { Accept: "application/json" },
            signal: AbortSignal.timeout(10_000),
          })
            .then((response) => (response.ok ? (response.json() as Promise<PublicStatus>) : null))
            .then((status) => {
              if (status) {
                line.textContent = statusLine(status, line.dataset.fine ?? "");
              }
            })
            .catch(() => undefined),
        0,
      ),
    { once: true },
  );
}
