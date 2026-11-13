import AxeBuilder from "@axe-core/playwright";
import type { Page } from "@playwright/test";
import { typeScale } from "../src/design/tokens";

/**
 * The UI review's checks of one screen as drawn (web/DESIGN.md, the UI standard): what a machine can see that breaks a
 * rule, read from the page itself, so a person looking through the pictures starts from a list. Each problem names its
 * rule and a severity: P0 broken or unusable, P1 inconsistent or confusing, P2 polish. It never fails a test: the review
 * reports (web/e2e/ui-review.ts), the specs gate.
 */
export type Severity = "P0" | "P1" | "P2";
export type Problem = { rule: string; severity: Severity; detail: string; count: number };

/** Inter's sizes from the type scale. */
const INTER_SIZES: number[] = Object.values(typeScale).map(([size]) => size);

/** Words that are names, so a title with them in capitals is still sentence case. */
const PROPER = new Set(
  "IdleO idleo GPU GPUs CPU UPI PAN GST GSTIN IFSC CLI API SDK India Indian Windows Linux RTX NVIDIA CUDA Razorpay RazorpayX IST UTC Terms Privacy Docker Python LoRA QLoRA ID IDs URL TDS KYC SLA OK Wi-Fi PDF CSV JSON".split(
    " ",
  ),
);

/** Runs every check in the page and returns what broke, merged by rule. */
export async function audit(
  page: Page,
  { phone, axe, shifts }: { phone: boolean; axe: boolean; shifts?: number },
): Promise<Problem[]> {
  const found = await page.evaluate(
    ({ interSizes, proper, phone }) => {
      const problems: { rule: string; severity: "P0" | "P1" | "P2"; detail: string }[] = [];
      const add = (rule: string, severity: "P0" | "P1" | "P2", detail: string) =>
        problems.push({ rule, severity, detail });
      const visible = (element: Element) => {
        const rect = element.getBoundingClientRect();
        const style = getComputedStyle(element);
        return rect.width > 0 && rect.height > 0 && style.visibility !== "hidden" && style.display !== "none";
      };
      /** Text for screen readers only (Tailwind's sr-only): a 1 px box that clips by design. */
      const hidden = (element: Element) => {
        for (let at: Element | null = element; at && at !== document.body; at = at.parentElement) {
          const rect = at.getBoundingClientRect();
          if (rect.width <= 1 || rect.height <= 1 || at.closest("[aria-hidden='true']")) {
            return true;
          }
        }
        return false;
      };
      const describe = (element: Element) => {
        const text = element.textContent.trim().replace(/\s+/g, " ").slice(0, 60);
        return `<${element.tagName.toLowerCase()}>${text ? ` "${text}"` : ""}`;
      };
      const main: HTMLElement = document.querySelector("main") ?? document.body;
      // What's read as words: code samples and reference tables (the docs) name codes and numbers on purpose.
      const prose = main.cloneNode(true) as HTMLElement;
      prose.querySelectorAll("code, pre, kbd, samp, .docs table").forEach((element) => element.remove());
      document.body.append(prose);
      prose.style.position = "absolute";
      prose.style.visibility = "hidden";
      const text = prose.innerText;
      prose.remove();

      // Layout: nothing scrolls sideways; nothing is cut off; a truncated name shows its whole value on hover or focus.
      const overflow = document.documentElement.scrollWidth - window.innerWidth;
      if (overflow > 0) {
        add("no-horizontal-scroll", "P0", `the page scrolls sideways by ${overflow} px`);
      }
      for (const element of main.querySelectorAll("*")) {
        if (
          !visible(element) ||
          element.children.length > 0 ||
          !element.textContent.trim() ||
          hidden(element)
        ) {
          continue;
        }
        const style = getComputedStyle(element);
        const clipsX = /hidden|clip/.test(style.overflowX) && element.scrollWidth > element.clientWidth + 1;
        const clipsY =
          /hidden|clip/.test(style.overflowY) &&
          element.scrollHeight > element.clientHeight + 2 &&
          style.webkitLineClamp === "none";
        if (clipsX || clipsY) {
          const holder = element.closest("[title],[aria-label],[data-full]");
          if (style.textOverflow === "ellipsis" || style.webkitLineClamp !== "none") {
            if (!holder) {
              add(
                "truncation-shows-full-value",
                "P1",
                `${describe(element)} is truncated with no full value on hover`,
              );
            }
          } else {
            add("no-text-cut-off", "P0", `${describe(element)} is cut off`);
          }
        }
        const rect = element.getBoundingClientRect();
        if (rect.right > window.innerWidth + 1 && !element.closest("[data-scrolls],pre,code")) {
          // Inside something that clips or scrolls it within the window (a marquee, a table that scrolls), it's meant.
          let scroller = element.parentElement;
          while (
            scroller &&
            !(
              /auto|scroll|hidden|clip/.test(getComputedStyle(scroller).overflowX) &&
              scroller.getBoundingClientRect().right <= window.innerWidth + 1
            )
          ) {
            scroller = scroller.parentElement;
          }
          if (!scroller) {
            add("no-text-cut-off", "P0", `${describe(element)} runs past the window's edge`);
          }
        }
      }

      // Type: only the scale's sizes; body lines no longer than about 75 characters.
      const zero = document.createElement("span");
      zero.textContent = "0";
      zero.style.position = "absolute";
      zero.style.visibility = "hidden";
      document.body.append(zero);
      const sizes = new Map<string, string>();
      for (const element of document.querySelectorAll("body *")) {
        if (!visible(element) || element.closest("svg") || hidden(element)) {
          continue;
        }
        const own = [...element.childNodes].some(
          (node) => node.nodeType === Node.TEXT_NODE && (node.textContent ?? "").trim(),
        );
        if (!own) {
          continue;
        }
        const style = getComputedStyle(element);
        const size = Number.parseFloat(style.fontSize);
        const family = style.fontFamily;
        if (!/mono/i.test(family) && !interSizes.includes(Math.round(size * 10) / 10)) {
          sizes.set(`${size}px`, describe(element));
        }
        if (/^(P|LI|DD)$/.test(element.tagName) && element.textContent.length > 90) {
          zero.style.font = style.font;
          const ch = zero.getBoundingClientRect().width || size / 2;
          const measure = element.getBoundingClientRect().width / ch;
          if (measure > 80) {
            add("line-length", "P2", `${describe(element)} runs ${Math.round(measure)} characters a line`);
          }
        }
      }
      zero.remove();
      for (const [size, where] of sizes) {
        add("type-scale-only", "P1", `${size} isn't on the type scale (${where})`);
      }

      // Spacing: on the 8 px grid, with 4 px for small gaps (2 px for hairlines and a chip's own inset).
      const offGrid = new Map<number, string>();
      for (const element of main.querySelectorAll("*")) {
        if (!visible(element) || element.closest("svg")) {
          continue;
        }
        const style = getComputedStyle(element);
        for (const value of [
          style.paddingTop,
          style.paddingRight,
          style.paddingBottom,
          style.paddingLeft,
          style.marginTop,
          style.marginBottom,
          style.rowGap,
          style.columnGap,
        ]) {
          const px = Number.parseFloat(value);
          if (Number.isFinite(px) && px > 2 && Math.abs(px - Math.round(px / 4) * 4) > 0.5) {
            offGrid.set(Math.round(px * 10) / 10, describe(element));
          }
        }
      }
      for (const [px, where] of offGrid) {
        add("spacing-on-grid", "P2", `${px} px isn't on the 4/8 px grid (${where})`);
      }

      // Tables: numbers right-aligned in tabular figures.
      for (const cell of main.querySelectorAll("td, [role='cell']")) {
        const value = cell.textContent.trim();
        if (
          !visible(cell) ||
          cell.closest(".docs") ||
          cell.querySelector("code") ||
          !/^[₹+−-]?\s?\d[\d,.]*\s?(%|h|min|s|GB|MB|°C|paise)?$/.test(value)
        ) {
          continue;
        }
        const style = getComputedStyle(cell);
        const inner = cell.firstElementChild ? getComputedStyle(cell.firstElementChild) : style;
        if (!/right|end/.test(style.textAlign) && style.justifyContent !== "flex-end") {
          add("numbers-right-aligned", "P1", `${describe(cell)} is a number not aligned right`);
        }
        if (
          !/tabular-nums/.test(style.fontVariantNumeric) &&
          !/tabular-nums/.test(inner.fontVariantNumeric)
        ) {
          add("tabular-figures", "P1", `${describe(cell)} isn't in tabular figures`);
        }
      }

      // Formats: money with Indian grouping, times in words, no raw codes or ids in the text.
      for (const match of text.matchAll(/₹\s?[\d,]+(?:\.\d+)?/g)) {
        const digits = match[0].replace(/[₹\s]/g, "").split(".")[0]!;
        const indian = /^\d{1,3}$|^\d{1,2}(,\d{2})*,\d{3}$/.test(digits);
        if (!indian) {
          add("money-indian-grouping", "P1", `"${match[0]}" isn't grouped the Indian way`);
        }
      }
      if (/\bRs\.?\s?\d|\bINR\s?\d/.test(text)) {
        add("money-indian-grouping", "P1", "money written as Rs or INR, not ₹");
      }
      for (const match of text.matchAll(
        /\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}|\b\d{2}:\d{2}(:\d{2})?\s?(UTC|GMT|Z)\b/g,
      )) {
        add("one-time-format", "P1", `a raw time "${match[0]}"`);
      }
      const codes = new Set(
        (text.match(/\b[A-Z][A-Z0-9]*_[A-Z0-9_]+\b|\b[a-z]+(?:_[a-z]+)+\b/g) ?? []).filter(
          (code) => code.length > 3,
        ),
      );
      for (const code of codes) {
        add("no-internal-codes", "P1", `the code "${code}" is shown as text`);
      }
      for (const match of text.matchAll(
        /\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b/g,
      )) {
        add("no-ids-in-text", "P2", `an id ${match[0].slice(0, 13)}… in the text`);
      }

      // Words: sentence case; a button says what it does; a disabled control says why.
      const cased = (value: string) => {
        const words = value.split(/\s+/).filter((word) => /^[A-Za-z]/.test(word));
        if (words.length < 3) {
          return true;
        }
        const capitals = words.slice(1).filter((word) => /^[A-Z][a-z]/.test(word) && !proper.includes(word));
        return capitals.length < 2;
      };
      for (const element of main.querySelectorAll("h1,h2,h3,h4,button,label,th,[role='tab']")) {
        const value = element.textContent.trim().replace(/\s+/g, " ");
        if (visible(element) && value && value.length < 80 && !cased(value)) {
          add("sentence-case", "P2", `${describe(element)} is in title case`);
        }
      }
      for (const button of document.querySelectorAll("button, [role='button'], input[type='submit']")) {
        const name = ((button as HTMLElement).innerText || button.getAttribute("aria-label") || "").trim();
        if (/^(submit|ok|okay|yes|no|go|confirm|continue)$/i.test(name)) {
          add("buttons-say-what-they-do", "P1", `a button says "${name}"`);
        }
        const disabled = button.hasAttribute("disabled") || button.getAttribute("aria-disabled") === "true";
        // A busy button is disabled while it works, and its spinner says so.
        if (disabled && visible(button) && button.getAttribute("aria-busy") !== "true") {
          const why =
            button.getAttribute("title") ||
            button.getAttribute("aria-describedby") ||
            button.closest("[title],[data-why]");
          if (!why) {
            add("disabled-says-why", "P1", `${describe(button)} is disabled without saying why`);
          }
        }
      }

      // State: nothing is ever just blank; no spinner where a skeleton fits.
      const busy = main.querySelector("[aria-busy='true'], [data-skeleton], .skeleton, [class*='bone']");
      if (text.trim().length < 3 && !busy) {
        add("never-blank", "P0", "the screen is blank");
      }
      for (const spinner of main.querySelectorAll(".animate-spin, [data-spinner]")) {
        if (visible(spinner) && !spinner.closest("button")) {
          add("skeletons-not-spinners", "P1", "a spinner stands in for content");
        }
      }

      // Navigation: one h1 and a tab title that names the page.
      const headings = [...document.querySelectorAll("h1")].filter(visible);
      if (headings.length !== 1) {
        add("one-page-title", "P1", `${headings.length} visible h1 headings`);
      }
      if (!document.title.trim() || !/[—–|·-]/.test(document.title)) {
        add("tab-title-names-the-page", "P1", `the tab title "${document.title}" doesn't name the page`);
      }

      // Touch targets on a phone: 44 px, but for a link inside a sentence.
      if (phone) {
        for (const control of main.querySelectorAll("button, a[href], [role='button'], input, select")) {
          if (!visible(control) || control.closest("p, li > span, td")) {
            continue;
          }
          // A tick box or a radio is reached through its label as well: the label is what a thumb hits.
          const target = control.matches("input[type='checkbox'], input[type='radio']")
            ? (control.closest("label") ?? control)
            : control;
          const rect = target.getBoundingClientRect();
          if (rect.height < 43.5 || rect.width < 43.5) {
            add(
              "touch-targets",
              "P1",
              `${describe(control)} is ${Math.round(rect.width)}×${Math.round(rect.height)} px`,
            );
          }
        }
      }
      return problems;
    },
    { interSizes: INTER_SIZES, proper: [...PROPER], phone },
  );

  if (shifts !== undefined && shifts > 0) {
    found.push({
      rule: "no-layout-shift",
      severity: shifts > 0.1 ? "P0" : "P1",
      detail: `cumulative layout shift ${shifts.toFixed(4)}`,
    });
  }
  if (axe) {
    const result = await new AxeBuilder({ page })
      .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
      .analyze();
    for (const violation of result.violations) {
      found.push({
        rule: `axe-${violation.id}`,
        severity: violation.impact === "critical" || violation.impact === "serious" ? "P0" : "P1",
        detail: `${violation.help} (${violation.nodes.length} element${violation.nodes.length === 1 ? "" : "s"})`,
      });
    }
  }

  // One line per rule and detail, counted.
  const merged = new Map<string, Problem>();
  for (const problem of found) {
    const key = `${problem.rule}|${problem.detail}`;
    const seen = merged.get(key);
    if (seen) {
      seen.count += 1;
    } else {
      merged.set(key, { ...problem, count: 1 });
    }
  }
  return [...merged.values()];
}
