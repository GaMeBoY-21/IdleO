/**
 * The design tokens (web/DESIGN.md), the one place colours, sizes, radii and durations are written down.
 * Tailwind's theme is generated from this file (scripts/tokens-css.ts), and a lint rule refuses raw colours
 * and arbitrary values anywhere else.
 */

import { ring } from "../brand/brand.ts";

/**
 * Black and white (Oct 8): grey fills and white space, not bordered boxes. The brand's indigo is only in the
 * wordmark's ring and the focus ring.
 */
export const themes = {
  light: {
    ground: "#FFFFFF",
    fill: "#F3F3F3",
    "fill-selected": "#F6F6F6",
    divider: "#EBEBEB",
    control: "#767676",
    text: "#000000",
    "text-muted": "#5E5E5E",
    primary: "#000000",
    "primary-hover": "#2B2B2B",
    "primary-fg": "#FFFFFF",
    "inverse-muted": "#BDBDBD",
    "inverse-running": "#4CC38A",
    running: "#0B6B37",
    "running-soft": "#E3F4EA",
    warning: "#8A5300",
    "warning-soft": "#FFF3DD",
    danger: "#C0262D",
    "danger-soft": "#FDECEC",
    "danger-fg": "#FFFFFF",
    "log-ground": "#111111",
    "log-text": "#D6D6D6",
    "log-muted": "#A8A8A8",
    ...ring.light,
  },
  dark: {
    ground: "#0B0B0B",
    fill: "#1A1A1A",
    "fill-selected": "#222222",
    divider: "#2A2A2A",
    control: "#8A8A8A",
    text: "#FFFFFF",
    "text-muted": "#A6A6A6",
    primary: "#FFFFFF",
    "primary-hover": "#E0E0E0",
    "primary-fg": "#000000",
    "inverse-muted": "#4A4A4A",
    "inverse-running": "#0B6B37",
    running: "#4CC38A",
    "running-soft": "#10291D",
    warning: "#F0B44C",
    "warning-soft": "#2E220B",
    danger: "#FF6B6B",
    "danger-soft": "#331414",
    "danger-fg": "#000000",
    "log-ground": "#161616",
    "log-text": "#D6D6D6",
    "log-muted": "#A8A8A8",
    ...ring.dark,
  },
} as const;

export type ColourToken = keyof (typeof themes)["light"];

/** The pairs DESIGN.md's contrast table lists: text needs 4.5:1, controls, rings and borders 3:1 (WCAG 2.2 AA). */
export const contrastPairs: ReadonlyArray<{ fg: ColourToken; bg: ColourToken; min: 4.5 | 3 }> = [
  { fg: "text", bg: "ground", min: 4.5 },
  { fg: "text", bg: "fill", min: 4.5 },
  { fg: "text", bg: "fill-selected", min: 4.5 },
  { fg: "text-muted", bg: "ground", min: 4.5 },
  { fg: "text-muted", bg: "fill", min: 4.5 },
  { fg: "text-muted", bg: "fill-selected", min: 4.5 },
  { fg: "primary-fg", bg: "primary", min: 4.5 },
  { fg: "primary-fg", bg: "primary-hover", min: 4.5 },
  { fg: "inverse-muted", bg: "primary", min: 4.5 },
  { fg: "inverse-running", bg: "primary", min: 3 },
  { fg: "running", bg: "running-soft", min: 4.5 },
  { fg: "running", bg: "ground", min: 4.5 },
  { fg: "warning", bg: "warning-soft", min: 4.5 },
  { fg: "warning", bg: "ground", min: 4.5 },
  { fg: "danger", bg: "danger-soft", min: 4.5 },
  { fg: "danger", bg: "ground", min: 4.5 },
  { fg: "danger-fg", bg: "danger", min: 4.5 },
  { fg: "log-text", bg: "log-ground", min: 4.5 },
  { fg: "log-muted", bg: "log-ground", min: 4.5 },
  { fg: "control", bg: "ground", min: 3 },
  { fg: "control", bg: "fill", min: 3 },
  { fg: "focus", bg: "ground", min: 3 },
  { fg: "focus", bg: "fill", min: 3 },
  { fg: "brand", bg: "ground", min: 3 },
];

/** The type scale: [size, line height] in px. Sizes of 28 and up are bold and tightly spaced (tightFrom). */
export const typeScale = {
  caption: [13, 18],
  small: [14, 20],
  body: [16, 24],
  option: [17, 24],
  section: [20, 26],
  title: [28, 34],
  question: [40, 44],
  timer: [56, 56],
  "timer-xl": [72, 72],
} as const;

/** From this size up, type is weight 700 at -0.03em. */
export const tightFrom = 28;

export const fontWeights = { regular: 400, medium: 500, semibold: 600, bold: 700 } as const;

/**
 * The 4 px scale, by Tailwind step, with 2 px between a title and its line and 14 px for option padding; the
 * half steps 18, 26 and 34 are the type scale's line heights, for skeleton lines. Named sizes for touch targets and
 * the log panel.
 */
export const spacing = {
  "0": 0,
  "0.5": 2,
  "1": 4,
  "1.5": 6,
  "2": 8,
  "3": 12,
  "3.5": 14,
  "4": 16,
  "4.5": 18,
  "5": 20,
  "5.5": 22,
  "6": 24,
  "6.5": 26,
  "7": 28,
  "8": 32,
  "8.5": 34,
  "9": 36,
  "10": 40,
  "11": 44,
  "12": 48,
  "14": 56,
  "15": 60,
  "16": 64,
  "18": 72,
  "24": 96,
  touch: 44,
  /** The log panel's least height on the session screen: about 21 lines. */
  panel: 384,
  /** "Finding your GPU": the ring, and the grey disc around it. */
  "finding-ring": 88,
  finding: 168,
} as const;

/** 14 px for controls and options, 24 px for panels, 20 px for the top of a bottom sheet, round for pills. */
export const radii = { control: 14, icon: 12, sheet: 20, panel: 24, full: 9999 } as const;

/** Widths: the breakpoints of the layout grid, the reading measure, and the laptop's panels. */
export const layout = {
  breakpoints: { md: 768, xl: 1280 },
  prose: "68ch",
  content: 1120,
  /** The laptop's left panel: the question, the choices and the one button. */
  rail: 480,
  /** The card inside the laptop's right panel. */
  card: 600,
  /** A service's booking screen on a laptop: the questions, wider than the rail for three option cards a row. */
  form: 720,
  /** The "Your request" card beside them. */
  request: 460,
  /** Menus and popovers. */
  menu: 256,
  /** Single-card screens: sign-in and welcome. */
  narrow: 384,
  /** Book's price beside the GPU card ("≈ ₹5–8", "₹7.20–10.80/h"), reserved so it never moves as it arrives. */
  price: 112,
  /** An amount in the header, reserved so the label beside it never moves: up to ₹9,999.99. */
  amount: "9ch",
} as const;

export const motion = {
  durations: { press: 120, appear: 180, large: 240 },
  /** The running pulse, and the finding ring's breath: the only continuous motions beside the shimmer. */
  pulse: 2_000,
  easing: { enter: "cubic-bezier(0.2, 0, 0, 1)", leave: "cubic-bezier(0.4, 0, 1, 1)" },
} as const;

/** Shadows only for what floats: menus, and a bottom sheet over the page. */
export const shadows = {
  float: {
    light: "0 8px 24px rgb(0 0 0 / 0.12), 0 2px 6px rgb(0 0 0 / 0.08)",
    dark: "0 8px 24px rgb(0 0 0 / 0.6), 0 2px 6px rgb(0 0 0 / 0.5)",
  },
  sheet: {
    light: "0 -8px 24px rgb(0 0 0 / 0.08)",
    dark: "0 -8px 24px rgb(0 0 0 / 0.6)",
  },
} as const;
