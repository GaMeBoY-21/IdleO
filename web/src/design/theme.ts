/**
 * Light, dark, or the system's choice (web/DESIGN.md "Colour"). The choice is remembered on this device only,
 * as a per-viewer convenience; any failure to read or write it just means following the system.
 */
export type ThemeChoice = "system" | "light" | "dark";

export const THEME_KEY = "idleo.theme";

/**
 * Runs before the first paint (inlined in the page head, allowed by its hash in the CSP), so a chosen theme
 * never flashes the other one first.
 */
export const themeBootScript = `try{var t=localStorage.getItem("${THEME_KEY}");if(t==="light"||t==="dark")document.documentElement.dataset.theme=t}catch(e){}`;

export function readThemeChoice(): ThemeChoice {
  try {
    const stored = localStorage.getItem(THEME_KEY);
    return stored === "light" || stored === "dark" ? stored : "system";
  } catch {
    return "system";
  }
}

export function applyThemeChoice(choice: ThemeChoice): void {
  const root = document.documentElement;
  if (choice === "system") {
    delete root.dataset.theme;
  } else {
    root.dataset.theme = choice;
  }
  try {
    if (choice === "system") {
      localStorage.removeItem(THEME_KEY);
    } else {
      localStorage.setItem(THEME_KEY, choice);
    }
  } catch {
    // Storage is blocked: the choice lasts until the page is closed.
  }
}
