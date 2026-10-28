/**
 * The design tokens (web/DESIGN.md), the one place colours, sizes, radii and durations are written down.
 * Tailwind's theme is generated from this file (scripts/tokens-css.ts), and a lint rule refuses raw colours
 * and arbitrary values anywhere else.
 */

import { ring } from "../brand/brand.ts";

/**
 * Night Shift (ADR 0043): a deep indigo page with a few loud colours, in two themes. `dark` ("Night Shift") is the
 * default; `light` ("Day Shift") has the same layout. The tokens below are the whole vocabulary: the surfaces, the
 * three tones of text, the accents (volt, ember, ion, ion-deep), and the roles that differ between the themes
 * (`primary`, `link`, `running`, the chips). A `night*` token is the same in both themes: the dark preview card and the
 * "sealed" band stay dark in Day Shift, as drawn. Volt is never text on a light background; there it is only a fill
 * under dark text (`ink`).
 */
export const themes = {
  dark: {
    canvas: "#0C0A1F",
    sidebar: "#0F0D25",
    panel: "#17142F",
    "panel-2": "#221E40",
    raised: "#1B1838",
    /** A ghost button: the raised fill by night, white with an edge by day (the website draws it so). */
    ghost: "#1B1838",
    "ghost-hover": "#27234B",
    line: "#2A2650",
    /** A panel's own edge: none at night, where panels are told apart by colour. */
    edge: "transparent",
    /** The border of a field or a control, which must show against any surface (WCAG 1.4.11). */
    control: "#7A75B0",
    text: "#FFFFFF",
    "text-2": "#B9B5DA",
    "text-3": "#8F8AB8",
    volt: "#C8FF3D",
    "volt-hover": "#DAFF70",
    ember: "#FF7A2F",
    ion: "#8E80FF",
    "ion-deep": "#5B4BE8",
    "ion-deep-hover": "#7060F0",
    black: "#000000",
    ink: "#0C0A1F",
    "on-ion-deep": "#FFFFFF",
    primary: "#C8FF3D",
    "primary-hover": "#DAFF70",
    "primary-fg": "#0C0A1F",
    link: "#C8FF3D",
    "link-hover": "#E2FF8F",
    progress: "#C8FF3D",
    running: "#C8FF3D",
    "running-dot": "#C8FF3D",
    ok: "#22351A",
    "ok-fg": "#C8FF3D",
    warn: "#3A2214",
    "warn-fg": "#FFB27F",
    danger: "#FF7A7A",
    "danger-soft": "#3A1620",
    "danger-fg": "#0C0A1F",
    bone: "#221E40",
    "bone-shine": "#2A2650",
    night: "#17142F",
    "night-2": "#221E40",
    "night-line": "#2A2650",
    "on-night": "#FFFFFF",
    "on-night-2": "#B9B5DA",
    "on-night-3": "#8F8AB8",
    "danger-on-night": "#FF8A8A",
    ...ring.dark,
  },
  light: {
    canvas: "#F4F3FA",
    sidebar: "#FFFFFF",
    panel: "#FFFFFF",
    "panel-2": "#F4F3FA",
    raised: "#EEECF8",
    ghost: "#FFFFFF",
    "ghost-hover": "#EEECF8",
    line: "#ECEAF5",
    edge: "#E4E2F0",
    control: "#7A76A0",
    text: "#0C0A1F",
    "text-2": "#4A4668",
    "text-3": "#5E5A80",
    volt: "#C8FF3D",
    "volt-hover": "#DAFF70",
    ember: "#FF7A2F",
    ion: "#8E80FF",
    "ion-deep": "#5B4BE8",
    "ion-deep-hover": "#4A3BD0",
    black: "#000000",
    ink: "#0C0A1F",
    "on-ion-deep": "#FFFFFF",
    primary: "#0C0A1F",
    "primary-hover": "#25214A",
    "primary-fg": "#C8FF3D",
    link: "#4B3BD6",
    "link-hover": "#3427B0",
    progress: "#5B4BE8",
    running: "#3A7D0A",
    "running-dot": "#47A010",
    ok: "#E6F7D4",
    "ok-fg": "#2F6B06",
    warn: "#FFE9DB",
    "warn-fg": "#9A3D06",
    danger: "#C0262D",
    "danger-soft": "#FDECEC",
    "danger-fg": "#FFFFFF",
    bone: "#E4E2F0",
    "bone-shine": "#F4F3FA",
    night: "#17142F",
    "night-2": "#221E40",
    "night-line": "#2A2650",
    "on-night": "#FFFFFF",
    "on-night-2": "#B9B5DA",
    "on-night-3": "#8F8AB8",
    "danger-on-night": "#FF8A8A",
    ...ring.light,
  },
} as const;

export type ColourToken = keyof (typeof themes)["dark"];
export type ThemeName = keyof typeof themes;

/**
 * The pairs DESIGN.md's contrast table lists, checked in both themes: text needs 4.5:1 (3:1 for the 24 px and larger
 * headlines), controls, rings and the progress bar 3:1 (WCAG 2.2 AA). Volt is text only on the `night` surfaces; the
 * roles that carry its place to Day Shift (`link`, `running`, `brand`) have their own colour there.
 */
export const contrastPairs: ReadonlyArray<{
  fg: ColourToken;
  bg: ColourToken;
  min: 4.5 | 3;
}> = [
  { fg: "text", bg: "canvas", min: 4.5 },
  { fg: "text", bg: "sidebar", min: 4.5 },
  { fg: "text", bg: "panel", min: 4.5 },
  { fg: "text", bg: "panel-2", min: 4.5 },
  { fg: "text", bg: "raised", min: 4.5 },
  { fg: "text", bg: "ghost", min: 4.5 },
  { fg: "text", bg: "ghost-hover", min: 4.5 },
  { fg: "text-2", bg: "canvas", min: 4.5 },
  { fg: "text-2", bg: "sidebar", min: 4.5 },
  { fg: "text-2", bg: "panel", min: 4.5 },
  { fg: "text-2", bg: "panel-2", min: 4.5 },
  { fg: "text-2", bg: "raised", min: 4.5 },
  { fg: "text-3", bg: "canvas", min: 4.5 },
  { fg: "text-3", bg: "sidebar", min: 4.5 },
  { fg: "text-3", bg: "panel", min: 4.5 },
  { fg: "text-3", bg: "panel-2", min: 4.5 },
  { fg: "text-3", bg: "raised", min: 4.5 },
  { fg: "primary-fg", bg: "primary", min: 4.5 },
  { fg: "primary-fg", bg: "primary-hover", min: 4.5 },
  { fg: "ink", bg: "volt", min: 4.5 },
  { fg: "ink", bg: "volt-hover", min: 4.5 },
  { fg: "ink", bg: "ember", min: 4.5 },
  { fg: "ink", bg: "ion", min: 4.5 },
  { fg: "on-ion-deep", bg: "ion-deep", min: 4.5 },
  { fg: "on-ion-deep", bg: "ion-deep-hover", min: 4.5 },
  { fg: "link", bg: "canvas", min: 4.5 },
  { fg: "link", bg: "panel", min: 4.5 },
  { fg: "link-hover", bg: "canvas", min: 4.5 },
  { fg: "link-hover", bg: "panel", min: 4.5 },
  { fg: "running", bg: "canvas", min: 4.5 },
  { fg: "running", bg: "panel", min: 4.5 },
  { fg: "running", bg: "panel-2", min: 4.5 },
  { fg: "ok-fg", bg: "ok", min: 4.5 },
  { fg: "running", bg: "ok", min: 4.5 },
  { fg: "warn-fg", bg: "warn", min: 4.5 },
  { fg: "warn-fg", bg: "panel", min: 4.5 },
  { fg: "danger", bg: "canvas", min: 4.5 },
  { fg: "danger", bg: "panel", min: 4.5 },
  { fg: "danger", bg: "panel-2", min: 4.5 },
  { fg: "danger", bg: "danger-soft", min: 4.5 },
  { fg: "danger-fg", bg: "danger", min: 4.5 },
  { fg: "on-night", bg: "night", min: 4.5 },
  { fg: "on-night", bg: "night-2", min: 4.5 },
  { fg: "on-night", bg: "black", min: 4.5 },
  { fg: "on-night-2", bg: "night", min: 4.5 },
  { fg: "on-night-2", bg: "night-2", min: 4.5 },
  { fg: "on-night-2", bg: "black", min: 4.5 },
  { fg: "on-night-3", bg: "night", min: 4.5 },
  { fg: "on-night-3", bg: "night-2", min: 4.5 },
  { fg: "on-night-3", bg: "black", min: 4.5 },
  { fg: "volt", bg: "night", min: 4.5 },
  { fg: "volt", bg: "night-2", min: 4.5 },
  { fg: "ion", bg: "night", min: 4.5 },
  { fg: "ion", bg: "black", min: 4.5 },
  { fg: "danger-on-night", bg: "night", min: 4.5 },
  { fg: "control", bg: "canvas", min: 3 },
  { fg: "control", bg: "panel", min: 3 },
  { fg: "control", bg: "panel-2", min: 3 },
  { fg: "focus", bg: "canvas", min: 3 },
  { fg: "focus", bg: "panel", min: 3 },
  { fg: "progress", bg: "line", min: 3 },
  { fg: "progress", bg: "panel", min: 3 },
  { fg: "running-dot", bg: "canvas", min: 3 },
  { fg: "running-dot", bg: "panel", min: 3 },
  { fg: "running-dot", bg: "night", min: 3 },
  { fg: "brand", bg: "canvas", min: 4.5 },
  { fg: "brand", bg: "panel", min: 4.5 },
];

/**
 * The type scale: [size, line height] in px. Archivo's display sizes are in `displayScale` below; these are Inter's.
 * From `headingFrom` up, Inter is bold and tightly spaced (`headingStyle`): the panel titles and the figures.
 */
export const typeScale = {
  caption: [13, 18],
  small: [14, 20],
  body: [16, 24],
  option: [17, 24],
  /** The website's opening paragraph: large, but regular weight. */
  lede: [20, 30],
  section: [20, 26],
  figure: [24, 28],
  headline: [26, 28],
  title: [28, 34],
  question: [40, 44],
  timer: [54, 54],
  "timer-lg": [60, 60],
  "timer-xl": [72, 72],
} as const;

/** From this size up, type is bold with tight letter-spacing, except the sizes named here. */
export const headingFrom = 20;
export const plainSizes: ReadonlySet<string> = new Set(["lede"]);

export function headingStyle(size: number): { weight: 700; letterSpacing: string } {
  return { weight: 700, letterSpacing: size >= 54 ? "-0.03em" : size >= 28 ? "-0.02em" : "-0.01em" };
}

/**
 * Archivo's sizes: fluid between a smallest and a largest, as `[min px, vw, max px, line height, face]`, or fixed
 * (`min` equal to `max`). `display` is width 125% at weight 900, set uppercase; `title` is width 112% at weight 800.
 * The names are Tailwind's `text-*`.
 */
export const displayScale = {
  /** The website's headline. */
  "display-hero": [44, 6, 84, 0.98, "display"],
  /** A section of the website. */
  "display-band": [36, 4.4, 60, 1, "display"],
  "display-section": [32, 4, 52, 1, "display"],
  /** The app's page headline. */
  "display-page": [32, 3.2, 44, 1, "display"],
  /** A panel's title. */
  "display-card": [26, 0, 26, 1.1, "title"],
  "display-feature": [30, 0, 30, 1.1, "title"],
  "display-panel": [40, 0, 40, 1.05, "title"],
  /** The step numbers on the website, and the credit tile's figure. */
  "display-number": [72, 0, 72, 1, "display"],
  "display-figure": [52, 0, 52, 1, "display"],
  /** The wordmark. */
  "display-logo": [28, 0, 28, 1.2, "display"],
  /** The footer's wordmark. */
  "display-wordmark": [96, 19, 280, 0.8, "display"],
} as const satisfies Record<string, readonly [number, number, number, number, "display" | "title"]>;

export const fontWeights = { regular: 400, medium: 500, semibold: 600, bold: 700 } as const;

/** What the first screen's font files hold: Basic Latin, the few Latin-1 signs we use, the punctuation, arrows and rupee. */
const BASIC_LATIN =
  "U+0020-007E,U+00A0,U+00A7,U+00A9,U+00B0,U+00B7,U+00D7,U+2013-2014,U+2018-201D,U+2022,U+2026,U+20B9,U+2190-2193,U+2212,U+2248";
/** Archivo at 125% / 900 is set uppercase, so its file holds no lowercase; the wordmark's few lowercase letters are a second, tiny file. */
const DISPLAY_CAPS =
  "U+0020-0060,U+007B-007E,U+00A0,U+00A9,U+00B7,U+00D7,U+2013-2014,U+2018-201D,U+2022,U+2026,U+20B9,U+2190-2193,U+2212,U+2248";
const DISPLAY_LOWER = "U+0061-007A";
/** The rest of Latin-1: accented letters and the signs we do not use. */
const LATIN_ACCENTS = "U+00A1-00A6,U+00A8,U+00AA-00AF,U+00B1-00B6,U+00B8-00D6,U+00D8-00FF";

/**
 * The fonts (web/DESIGN.md "Typography"): self-hosted, Latin plus the rupee sign, cut by scripts/subset-font.sh. Each
 * has a fallback, Arial scaled to the font's metrics, so the page does not move when the font arrives
 * (`font-display: swap`). `preload` marks the files the first screen uses.
 */
export const fonts = {
  sans: {
    family: "Inter",
    file: "inter-400-700.woff2",
    weight: "400 700",
    range: BASIC_LATIN,
    // The accented letters, fetched only by a page that has one (a person's name), so the first screen does not carry them.
    more: { file: "inter-400-700-latin1.woff2", range: LATIN_ACCENTS },
    fallback: [107.17, 90.39, 22.51],
  },
  display: {
    family: "Archivo Display",
    file: "archivo-125-900.woff2",
    weight: "900",
    range: DISPLAY_CAPS,
    more: { file: "archivo-125-900-lower.woff2", range: DISPLAY_LOWER },
    fallback: [133.81, 65.62, 15.69],
  },
  title: {
    family: "Archivo Title",
    file: "archivo-112-800.woff2",
    weight: "800",
    range: BASIC_LATIN,
    more: null,
    fallback: [123.4, 75.94, 18.16],
  },
  mono: {
    family: "JetBrains Mono",
    file: "jetbrains-mono-400.woff2",
    weight: "400",
    range: `${BASIC_LATIN},${LATIN_ACCENTS}`,
    more: null,
    fallback: null,
  },
} as const;

/**
 * The files a first screen preloads, by file name. The app's is Inter and the wordmark's lowercase; the website adds
 * the uppercase of its headlines. Everything else loads when the page asks for it.
 */
export const preloadedFonts = {
  app: [fonts.sans.file, fonts.display.more.file],
  site: [fonts.sans.file, fonts.display.more.file, fonts.display.file],
} as const;

/**
 * The 4 px scale, by Tailwind step; screens lay out on an 8 px grid and use the half steps only for a title's gap and
 * a line height. Named sizes for touch targets and the log panel.
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
  "20": 80,
  "24": 96,
  "28": 112,
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
  /** Credit's invoices and receipts: three rows of 72 px and their lines, the same height loading and loaded, the rest
   * scrolling inside it (ADR 0039). */
  documents: 224,
  /** "Finding your GPU": the ring, and the grey disc around it. */
  "finding-ring": 88,
  finding: 168,
  /** Delete my account's "We must keep": its four lines, wrapped, on a phone and on a laptop (the periods arrive). */
  kept: 200,
  "kept-wide": 136,
  /** The hero card's chart on the website. */
  "hero-chart": 168,
  /** A step card on the website, at its least. */
  step: 256,
} as const;

/**
 * Controls are 14 px (16 for the large button), chips 12, inner panels 20, panels 28, feature panels 32 and the big ones 40; the phone's tab bar
 * is 22; buttons' pills, chips and the avatar are round. A bottom sheet's top is a panel's corner.
 */
export const radii = {
  control: 14,
  "control-lg": 16,
  icon: 12,
  inner: 20,
  panel: 28,
  feature: 32,
  hero: 40,
  sheet: 28,
  bar: 22,
  full: 9999,
} as const;

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
  /** The website's frame, as drawn: 1280 px with 32 px at the sides. */
  site: 1280,
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
  /** The hero's text, as drawn. */
  lede: 560,
  /** Book's price beside the GPU card ("≈ ₹5–8", "₹7.20–10.80/h"), reserved so it never moves as it arrives. */
  price: 112,
  /** An amount in the header, reserved so the label beside it never moves: up to ₹9,999.99. */
  amount: "9ch",
} as const;

export const motion = {
  durations: { press: 120, appear: 180, large: 240 },
  /** The running pulse, and the finding ring's breath: the only continuous motions beside the shimmer and the marquee. */
  pulse: 2_000,
  /** The website's marquee crosses its text in this long; it stops under reduced motion. */
  marquee: 60_000,
  easing: { enter: "cubic-bezier(0.2, 0, 0, 1)", leave: "cubic-bezier(0.4, 0, 1, 1)" },
} as const;

/** Depth comes from colour. The one shadow is the website's hero card's soft violet glow. */
export const shadows = {
  glow: "0 24px 80px rgb(91 75 232 / 0.18)",
} as const;
