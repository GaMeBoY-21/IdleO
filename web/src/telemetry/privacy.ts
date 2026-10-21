/** Whether the browser asks not to be tracked (Do Not Track or Global Privacy Control): the website then sends nothing (ADR 0036). */
export function privacyAsked(browser: {
  doNotTrack?: string | null;
  globalPrivacyControl?: boolean;
}): boolean {
  return browser.doNotTrack === "1" || browser.globalPrivacyControl === true;
}
