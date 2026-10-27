/**
 * Night Shift (dark), Day Shift (light), or the device's choice (web/DESIGN.md "Colour"). The choice is a cookie on the
 * parent domain, so the website and the app, on different addresses of one domain, share it on the same browser; it is a
 * per-browser convenience, never a server setting. With none, the page follows the device, and with no device setting
 * it is dark. Any failure to read or write it just means following the device.
 */
export type ThemeChoice = "system" | "light" | "dark";

export const THEME_COOKIE = "idleo-theme";
const YEAR = 365 * 24 * 60 * 60;

/**
 * Runs before the first paint (inlined in the page head, allowed by its hash in the CSP), so a chosen theme never
 * flashes the other one first. It only reads; writing is `applyThemeChoice`.
 */
export const themeBootScript = `try{var m=document.cookie.match(/(?:^|; )${THEME_COOKIE}=(light|dark)/);if(m)document.documentElement.dataset.theme=m[1]}catch(e){}`;

/**
 * The domain a cookie must name for two addresses to share it: the trailing labels their hosts have in common
 * (`app.idleo.in` and `idleo.in` give `idleo.in`). Empty when they share none, as on localhost, where a cookie is shared
 * across ports anyway.
 */
export function sharedDomain(hostA: string, hostB: string): string {
  const a = hostA.toLowerCase().split(".");
  const b = hostB.toLowerCase().split(".");
  const common: string[] = [];
  while (a.length > 0 && b.length > 0 && a[a.length - 1] === b[b.length - 1]) {
    common.unshift(a.pop()!);
    b.pop();
  }
  const numeric = common.every((label) => /^\d+$/.test(label));
  return common.length >= 2 && !numeric ? common.join(".") : "";
}

export function readThemeChoice(): ThemeChoice {
  try {
    const match = new RegExp(`(?:^|; )${THEME_COOKIE}=(light|dark)`).exec(document.cookie);
    return match ? (match[1] as ThemeChoice) : "system";
  } catch {
    return "system";
  }
}

/**
 * Applies a choice to this page and remembers it for the other address: `otherUrl` is the website's address from the app,
 * and the app's from the website.
 */
export function applyThemeChoice(choice: ThemeChoice, otherUrl: string): void {
  const root = document.documentElement;
  if (choice === "system") {
    delete root.dataset.theme;
  } else {
    root.dataset.theme = choice;
  }
  try {
    const domain = sharedDomain(location.hostname, new URL(otherUrl).hostname);
    const parts = [
      `${THEME_COOKIE}=${choice === "system" ? "" : choice}`,
      "Path=/",
      `Max-Age=${choice === "system" ? 0 : YEAR}`,
      "SameSite=Lax",
    ];
    if (domain) {
      parts.push(`Domain=${domain}`);
    }
    if (location.protocol === "https:") {
      parts.push("Secure");
    }
    document.cookie = parts.join("; ");
  } catch {
    // Cookies are blocked: the choice lasts until the page is closed.
  }
}
