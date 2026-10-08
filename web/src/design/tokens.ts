/**
 * The design tokens (web/DESIGN.md), the one place colours, sizes, radii and durations are written down.
 * Tailwind's theme is generated from this file (scripts/tokens-css.ts), and a lint rule refuses raw colours
 * and arbitrary values anywhere else.
 */

import { accent } from "../brand/brand.ts";

export const themes = {
  light: {
    canvas: "#F7F7F9",
    surface: "#FFFFFF",
    sunken: "#EFEFF3",
    border: "#E2E2E8",
    "border-strong": "#8A8A99",
    text: "#16161D",
    "text-muted": "#55556A",
    ...accent.light,
    success: "#157347",
    "success-soft": "#E7F5EE",
    warning: "#8A5300",
    "warning-soft": "#FFF3DD",
    danger: "#C0262D",
    "danger-soft": "#FDECEC",
    "danger-fg": "#FFFFFF",
  },
  dark: {
    canvas: "#0F0F14",
    surface: "#17171F",
    sunken: "#0B0B0F",
    border: "#2A2A35",
    "border-strong": "#71718A",
    text: "#EDEDF3",
    "text-muted": "#A3A3B8",
    ...accent.dark,
    success: "#4CC38A",
    "success-soft": "#10291D",
    warning: "#F0B44C",
    "warning-soft": "#2E220B",
    danger: "#FF6B6B",
    "danger-soft": "#331414",
    "danger-fg": "#0F0F14",
  },
} as const;

export type ColourToken = keyof (typeof themes)["light"];

/** The pairs DESIGN.md's contrast table lists: text needs 4.5:1, controls and focus rings 3:1 (WCAG 2.2 AA). */
export const contrastPairs: ReadonlyArray<{ fg: ColourToken; bg: ColourToken; min: 4.5 | 3 }> = [
  { fg: "text", bg: "canvas", min: 4.5 },
  { fg: "text", bg: "surface", min: 4.5 },
  { fg: "text-muted", bg: "canvas", min: 4.5 },
  { fg: "text-muted", bg: "surface", min: 4.5 },
  { fg: "text-muted", bg: "sunken", min: 4.5 },
  { fg: "accent-fg", bg: "accent", min: 4.5 },
  { fg: "accent-fg", bg: "accent-hover", min: 4.5 },
  { fg: "accent-text", bg: "canvas", min: 4.5 },
  { fg: "accent-text", bg: "surface", min: 4.5 },
  { fg: "accent-text", bg: "accent-soft", min: 4.5 },
  { fg: "success", bg: "surface", min: 4.5 },
  { fg: "success", bg: "success-soft", min: 4.5 },
  { fg: "warning", bg: "surface", min: 4.5 },
  { fg: "warning", bg: "warning-soft", min: 4.5 },
  { fg: "danger", bg: "surface", min: 4.5 },
  { fg: "danger", bg: "danger-soft", min: 4.5 },
  { fg: "danger-fg", bg: "danger", min: 4.5 },
  { fg: "border-strong", bg: "surface", min: 3 },
  { fg: "border-strong", bg: "canvas", min: 3 },
  { fg: "focus", bg: "canvas", min: 3 },
  { fg: "focus", bg: "surface", min: 3 },
  { fg: "accent", bg: "surface", min: 3 },
];

/** Six sizes, no more: [size, line height] in px. */
export const typeScale = {
  caption: [12, 16],
  small: [14, 20],
  body: [16, 24],
  title: [20, 28],
  heading: [24, 32],
  display: [32, 40],
} as const;

export const fontWeights = { regular: 400, medium: 500, semibold: 600 } as const;

/** The 4 px scale, by Tailwind step; `touch` is the 44 px minimum target. */
export const spacing = {
  "0": 0,
  "1": 4,
  "2": 8,
  "3": 12,
  "4": 16,
  "5": 20,
  "6": 24,
  "8": 32,
  "10": 40,
  "12": 48,
  "16": 64,
  touch: 44,
  /** The log panel's height on the session screen: about 24 lines. */
  panel: 384,
} as const;

export const radii = { control: 8, card: 12, sheet: 16, full: 9999 } as const;

/** Widths: the breakpoints of the layout grid, the reading measure and the page's content. */
export const layout = {
  breakpoints: { md: 768, xl: 1280 },
  prose: "68ch",
  content: 1120,
  /** Menus and popovers. */
  menu: 256,
  /** Single-card screens: sign-in and welcome. */
  narrow: 384,
  /** An amount in the header, reserved so the label beside it never moves: up to ₹9,999.99. */
  amount: "9ch",
} as const;

export const motion = {
  durations: { press: 120, appear: 180, large: 240 },
  /** The running pulse on a state chip, the one continuous motion beside the skeleton's shimmer. */
  pulse: 2_000,
  easing: { enter: "cubic-bezier(0.2, 0, 0, 1)", leave: "cubic-bezier(0.4, 0, 1, 1)" },
} as const;

/** The one shadow, for things that float above the page. */
export const shadows = {
  float: {
    light: "0 8px 24px rgb(22 22 29 / 0.12), 0 2px 6px rgb(22 22 29 / 0.08)",
    dark: "0 8px 24px rgb(0 0 0 / 0.5), 0 2px 6px rgb(0 0 0 / 0.4)",
  },
} as const;
