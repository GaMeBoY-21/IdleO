import { applyThemeChoice, readThemeChoice, type ThemeChoice } from "@/design/theme";

/**
 * The footer's Appearance switch (web/DESIGN.md "Colour"): Match my device, Light or Dark, kept in the cookie the app reads
 * too. The buttons are on every page, hidden but holding their place until this has run.
 */
const group = document.querySelector<HTMLElement>("[data-appearance]");
const app = document.body.dataset.app;

if (group && app) {
  const mark = () => {
    const chosen = readThemeChoice();
    for (const button of group.querySelectorAll<HTMLButtonElement>("button[data-choice]")) {
      button.setAttribute("aria-pressed", String(button.dataset.choice === chosen));
    }
  };
  group.addEventListener("click", (event) => {
    const button = (event.target as Element).closest<HTMLButtonElement>("button[data-choice]");
    if (button) {
      applyThemeChoice(button.dataset.choice as ThemeChoice, app);
      mark();
    }
  });
  mark();
  group.classList.remove("invisible");
}
