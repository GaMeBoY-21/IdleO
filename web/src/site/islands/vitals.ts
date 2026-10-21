import { holdEarlyErrors } from "@/telemetry/early-errors";
import { privacyAsked } from "@/telemetry/privacy";

/**
 * Every public page's one small script (ADR 0036): what real visits meet, and JavaScript errors, sent to the control
 * plane once the page has loaded and the browser is idle, so the reporting never slows the page it measures. A visitor
 * whose browser asks not to be tracked (Do Not Track or Global Privacy Control) sends nothing at all.
 */
const api = document.body.dataset.api;
if (api && !privacyAsked(navigator)) {
  holdEarlyErrors();
  const start = () =>
    void import("@/telemetry/report").then(({ startReporting }) =>
      startReporting({ api, app: "site", release: import.meta.env.IDLEO_RELEASE || "development" }),
    );
  const whenIdle = () =>
    "requestIdleCallback" in window ? requestIdleCallback(start) : setTimeout(start, 0);
  if (document.readyState === "complete") {
    whenIdle();
  } else {
    window.addEventListener("load", whenIdle, { once: true });
  }
}
