/**
 * The design tokens (web/DESIGN.md), the one place colours, sizes, radii and durations are written down.
 * Tailwind's theme is generated from this file (scripts/tokens-css.ts), and a lint rule refuses raw colours
 * and arbitrary values anywhere else.
 */

import { ring } from "../brand/brand.ts";

/**
 * The Bento look (Oct 16, ADR 0031): a light page, white tiles on it, and a few soft colour tiles. `ground` is the
 * page, and also what a field inside a tile is filled with, so it looks pressed in; `fill` is a tile. The dark tile
 * and the colour tiles keep their own text colours in both themes. The brand's indigo is only in the wordmark's ring
 * and the focus ring.
 */
export const themes = {
  light: {
    ground: "#EFEFF2",
    fill: "#FFFFFF",
    "fill-selected": "#F6F6F9",
    divider: "#E4E4EA",
    control: "#767683",
    text: "#16161A",
    "text-muted": "#5C5C66",
    primary: "#16161A",
    "primary-hover": "#2E2E36",
    "primary-fg": "#FFFFFF",
    dark: "#16161A",
    "dark-fg": "#FFFFFF",
    "dark-muted": "#B8B8C4",
    live: "#C9F27B",
    lime: "#C9F27B",
    "lime-fg": "#2E3A12",
    lavender: "#D8D3FF",
    "lavender-fg": "#3B347A",
    apricot: "#FFB86B",
    "apricot-fg": "#5A3410",
    "on-colour": "#16161A",
    running: "#0B6B37",
    "running-soft": "#E3F4EA",
    warning: "#8A5300",
    "warning-soft": "#FFF3DD",
    danger: "#C0262D",
    "danger-soft": "#FDECEC",
    "danger-fg": "#FFFFFF",
    "danger-on-dark": "#FF8A8A",
    bone: "#E3E3E9",
    "bone-shine": "#F4F4F7",
    ...ring.light,
  },
  dark: {
    ground: "#0E0E11",
    fill: "#1A1A1F",
    "fill-selected": "#222228",
    divider: "#2A2A31",
    control: "#8A8A96",
    text: "#F2F2F5",
    "text-muted": "#A3A3AE",
    primary: "#F2F2F5",
    "primary-hover": "#DADAE0",
    "primary-fg": "#16161A",
    dark: "#26262D",
    "dark-fg": "#FFFFFF",
    "dark-muted": "#B8B8C4",
    live: "#C9F27B",
    lime: "#C9F27B",
    "lime-fg": "#2E3A12",
    lavender: "#D8D3FF",
    "lavender-fg": "#3B347A",
    apricot: "#FFB86B",
    "apricot-fg": "#5A3410",
    "on-colour": "#16161A",
    running: "#4CC38A",
    "running-soft": "#10291D",
    warning: "#F0B44C",
    "warning-soft": "#2E220B",
    danger: "#FF6B6B",
    "danger-soft": "#331414",
    "danger-fg": "#16161A",
    "danger-on-dark": "#FF8A8A",
    bone: "#26262D",
    "bone-shine": "#30303A",
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
  { fg: "dark-fg", bg: "dark", min: 4.5 },
  { fg: "dark-muted", bg: "dark", min: 4.5 },
  { fg: "live", bg: "dark", min: 4.5 },
  { fg: "on-colour", bg: "lime", min: 4.5 },
  { fg: "lime-fg", bg: "lime", min: 4.5 },
  { fg: "on-colour", bg: "lavender", min: 4.5 },
  { fg: "lavender-fg", bg: "lavender", min: 4.5 },
  { fg: "on-colour", bg: "apricot", min: 4.5 },
  { fg: "apricot-fg", bg: "apricot", min: 4.5 },
  { fg: "running", bg: "running-soft", min: 4.5 },
  { fg: "running", bg: "fill", min: 4.5 },
  { fg: "running", bg: "ground", min: 4.5 },
  { fg: "warning", bg: "warning-soft", min: 4.5 },
  { fg: "warning", bg: "fill", min: 4.5 },
  { fg: "warning", bg: "ground", min: 4.5 },
  { fg: "danger", bg: "danger-soft", min: 4.5 },
  { fg: "danger", bg: "fill", min: 4.5 },
  { fg: "danger", bg: "ground", min: 4.5 },
  { fg: "danger-fg", bg: "danger", min: 4.5 },
  { fg: "danger-on-dark", bg: "dark", min: 4.5 },
  { fg: "control", bg: "ground", min: 3 },
  { fg: "control", bg: "fill", min: 3 },
  { fg: "focus", bg: "ground", min: 3 },
  { fg: "focus", bg: "fill", min: 3 },
  { fg: "brand", bg: "ground", min: 3 },
];

/**
 * The type scale: [size, line height] in px. From `headingFrom` up, type is weight 800 and tightly spaced
 * (`headingStyle`): the tile titles, the figures and the page's question.
 */
export const typeScale = {
  caption: [13, 18],
  small: [14, 20],
  body: [16, 24],
  option: [17, 24],
  section: [20, 26],
  figure: [24, 28],
  headline: [26, 28],
  title: [28, 34],
  question: [40, 44],
  timer: [54, 54],
  "timer-lg": [60, 60],
  "timer-xl": [72, 72],
} as const;

/** From this size up, type is weight 800 with tight letter-spacing. */
export const headingFrom = 20;

export function headingStyle(size: number): { weight: 800; letterSpacing: string } {
  return { weight: 800, letterSpacing: size >= 54 ? "-0.04em" : size >= 28 ? "-0.03em" : "-0.02em" };
}

export const fontWeights = { regular: 400, medium: 500, semibold: 600, bold: 700, extrabold: 800 } as const;

/**
 * The 4 px scale, by Tailwind step, with 2 px between a title and its line and 14 px for option padding; the
 * half steps 18, 26 and 34 are the type scale's line heights, for skeleton lines. Named sizes for touch targets and
 * the log panel.
 */
export const spacing = {
  "0": 0,
  "0.5": 2,
  "1": 4,
  "1.25": 5,
  "1.5": 6,
  "2": 8,
  "2.5": 10,
  "3": 12,
  "3.5": 14,
  "4": 16,
  "4.5": 18,
  "5": 20,
  "5.5": 22,
  "6": 24,
  "6.5": 26,
  "7": 28,
  "7.5": 30,
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
  /** The laptop's header, from 768 px. */
  header: 76,
  /** A row of the phone's tile grid (a small tile is one row, a tall one two). */
  "tile-row": 88,
  /** The session screen's dark tile on a phone, as its placeholder draws it. */
  "session-tile": 336,
  /** The Hosting tab's chart of the week on a laptop. */
  chart: 180,
  /** A row of the laptop's tile grid. */
  "tile-row-lg": 112,
  /** The log panel's least height on the session screen: about 21 lines. */
  panel: 384,
  /** The outputs tile on a laptop: the same height loading and loaded, its files scrolling inside it. */
  outputs: 264,
  /** "Finding your GPU": the ring, and the grey disc around it. */
  "finding-ring": 88,
  finding: 168,
  /** Delete my account's "We must keep": its four lines, wrapped, on a phone and on a laptop (the periods arrive). */
  kept: 192,
  "kept-wide": 136,
} as const;

/**
 * Tiles are 26 px, inputs and rows inside a tile 16, the floating tab bar 22; buttons, chips and the avatar are
 * round. A bottom sheet's top is a tile's corner.
 */
export const radii = { control: 16, icon: 14, sheet: 26, tile: 26, bar: 22, full: 9999 } as const;

/** Widths: the breakpoints of the layout grid, the reading measure, and the laptop's panels. */
export const layout = {
  /** Tablet from 768 px; laptop from 1200 px (full width, 32 px side padding); 40 px padding from 1680 px. */
  breakpoints: { md: 768, xl: 1200, "2xl": 1680 },
  /** The laptop's left column on the live session, Hosting's pages and Credit: the summary tile. */
  railLg: 400,
  /** The laptop's left column on Account: the profile tile and the section navigation. */
  railSm: 320,
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

/** Shadows only for what floats: menus, a bottom sheet over the page, and the phone's tab bar. */
export const shadows = {
  float: {
    light: "0 8px 24px rgb(22 22 26 / 0.12), 0 2px 6px rgb(22 22 26 / 0.08)",
    dark: "0 8px 24px rgb(0 0 0 / 0.6), 0 2px 6px rgb(0 0 0 / 0.5)",
  },
  sheet: {
    light: "0 -8px 24px rgb(22 22 26 / 0.08)",
    dark: "0 -8px 24px rgb(0 0 0 / 0.6)",
  },
  bar: {
    light: "0 4px 20px rgb(22 22 26 / 0.08)",
    dark: "0 4px 20px rgb(0 0 0 / 0.5)",
  },
} as const;
