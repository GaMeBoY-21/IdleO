/**
 * The brand, in one place (web/DESIGN.md "Brand"): the product name isn't decided, so the working name, the
 * accent colour, the wordmark (Wordmark.tsx) and the favicon (icon.svg) all live here, and the final brand is
 * one small change. The design tokens take the accent from here.
 */
export const brand = {
  name: "IdleO",
  description: "Rent a GPU by the minute, in rupees.",
} as const;

/** The accent, in both themes, with the shades the tokens derive from it. Contrast is tested in tokens.test.ts. */
export const accent = {
  light: {
    accent: "#4B3FD1",
    "accent-hover": "#3E33B8",
    "accent-fg": "#FFFFFF",
    "accent-text": "#4136C4",
    "accent-soft": "#EEEDFC",
    focus: "#4B3FD1",
  },
  dark: {
    accent: "#8F86FF",
    "accent-hover": "#A39CFF",
    "accent-fg": "#0F0F14",
    "accent-text": "#A39CFF",
    "accent-soft": "#221F45",
    focus: "#A39CFF",
  },
} as const;
