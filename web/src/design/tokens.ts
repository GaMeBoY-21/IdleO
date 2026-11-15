/**
 * The design tokens (web/DESIGN.md), the one place colours, sizes, radii and durations are written down.
 * Tailwind's theme is generated from this file (scripts/tokens-css.ts), and a lint rule refuses raw colours
 * and arbitrary values anywhere else.
 */

/**
 * The IdleO system (ADR 0060, Linear's structure with IdleO's identity): a surface ladder (canvas, then surface-1 to
 * surface-4), hairline borders instead of shadows, quiet type, and one accent, volt, used only for the screen's one primary
 * button, the focus ring, the brand mark and live indicators. `dark` is the default; `light` follows the device. The
 * status colours (success, warning, danger, info) are for chips' dots, small marks and charts, never fills.
 *
 * Two values are a touch off the reference so the contrast table (web/DESIGN.md "Contrast") holds: the light theme's
 * `ink-subtle` and `ink-tertiary` are darker, and `control` (the border of a field or a switch, WCAG 1.4.11) is
 * stronger than a hairline.
 */
export const themes = {
  dark: {
    canvas: "#08090A",
    "surface-1": "#0F1011",
    "surface-2": "#141516",
    "surface-3": "#18191A",
    "surface-4": "#1C1D1F",
    hairline: "#23252A",
    "hairline-strong": "#34343A",
    "hairline-tertiary": "#3E3E44",
    /** The border of a field or a control, which must show against any surface (WCAG 1.4.11). */
    control: "#666B73",
    ink: "#F7F8F8",
    "ink-muted": "#D0D6E0",
    "ink-subtle": "#8A8F98",
    "ink-tertiary": "#80858E",
    volt: "#C8FF3D",
    "volt-hover": "#D6FF6E",
    "volt-pressed": "#B3E02F",
    /** Text on volt: the canvas. */
    "on-volt": "#08090A",
    /** Live and running indicators, the brand mark and progress: volt, darker by day. */
    live: "#C8FF3D",
    /** The focus ring: volt at half strength, as a colour (2 px). */
    focus: "#688423",
    success: "#27A644",
    warning: "#D9A400",
    danger: "#EB5A5F",
    info: "#4C8DF6",
    /** Text on a solid danger button (the confirm dialog's). */
    "danger-fg": "#08090A",
    /** What dims the page behind a dialog. */
    scrim: "#000000",
    /** The QR code's ground: always white, so a scanner reads it. */
    paper: "#FFFFFF",
  },
  light: {
    canvas: "#FFFFFF",
    "surface-1": "#F7F8F8",
    "surface-2": "#F1F2F3",
    "surface-3": "#EBECEE",
    "surface-4": "#E4E5E7",
    hairline: "#E4E5E7",
    "hairline-strong": "#D6D8DB",
    "hairline-tertiary": "#C9CBCF",
    control: "#80858E",
    ink: "#0B0C0E",
    "ink-muted": "#3C4149",
    "ink-subtle": "#5F646D",
    "ink-tertiary": "#666B74",
    volt: "#C8FF3D",
    "volt-hover": "#D6FF6E",
    "volt-pressed": "#B3E02F",
    "on-volt": "#08090A",
    live: "#4A7300",
    focus: "#4A7300",
    success: "#1F8A3C",
    warning: "#A07400",
    danger: "#C0262D",
    info: "#2F6FE0",
    "danger-fg": "#FFFFFF",
    scrim: "#000000",
    paper: "#FFFFFF",
  },
} as const;

export type ColourToken = keyof (typeof themes)["dark"];
export type ThemeName = keyof typeof themes;

/**
 * The pairs DESIGN.md's contrast table lists, checked in both themes: text needs 4.5:1 (3:1 for the 24 px and larger
 * headlines), controls, rings, marks and the progress bar 3:1 (WCAG 2.2 AA). Tertiary text is for placeholders and table
 * heads on the canvas and the first two surfaces only; it never sits on a hover fill.
 */
export const contrastPairs: ReadonlyArray<{
  fg: ColourToken;
  bg: ColourToken;
  min: 4.5 | 3;
}> = [
  { fg: "ink", bg: "canvas", min: 4.5 },
  { fg: "ink", bg: "surface-1", min: 4.5 },
  { fg: "ink", bg: "surface-2", min: 4.5 },
  { fg: "ink", bg: "surface-3", min: 4.5 },
  { fg: "ink", bg: "surface-4", min: 4.5 },
  { fg: "ink-muted", bg: "canvas", min: 4.5 },
  { fg: "ink-muted", bg: "surface-1", min: 4.5 },
  { fg: "ink-muted", bg: "surface-2", min: 4.5 },
  { fg: "ink-muted", bg: "surface-3", min: 4.5 },
  { fg: "ink-subtle", bg: "canvas", min: 4.5 },
  { fg: "ink-subtle", bg: "surface-1", min: 4.5 },
  { fg: "ink-subtle", bg: "surface-2", min: 4.5 },
  { fg: "ink-subtle", bg: "surface-3", min: 4.5 },
  { fg: "ink-tertiary", bg: "canvas", min: 4.5 },
  { fg: "ink-tertiary", bg: "surface-1", min: 4.5 },
  { fg: "ink-tertiary", bg: "surface-2", min: 4.5 },
  { fg: "on-volt", bg: "volt", min: 4.5 },
  { fg: "on-volt", bg: "volt-hover", min: 4.5 },
  { fg: "on-volt", bg: "volt-pressed", min: 4.5 },
  { fg: "live", bg: "canvas", min: 4.5 },
  { fg: "live", bg: "surface-1", min: 4.5 },
  { fg: "live", bg: "surface-2", min: 4.5 },
  { fg: "danger", bg: "canvas", min: 4.5 },
  { fg: "danger", bg: "surface-1", min: 4.5 },
  { fg: "danger", bg: "surface-2", min: 4.5 },
  { fg: "danger", bg: "surface-3", min: 4.5 },
  { fg: "danger", bg: "surface-4", min: 4.5 },
  { fg: "danger-fg", bg: "danger", min: 4.5 },
  { fg: "control", bg: "canvas", min: 3 },
  { fg: "control", bg: "surface-1", min: 3 },
  { fg: "control", bg: "surface-2", min: 3 },
  { fg: "focus", bg: "canvas", min: 3 },
  { fg: "focus", bg: "surface-1", min: 3 },
  { fg: "focus", bg: "surface-2", min: 3 },
  { fg: "focus", bg: "surface-3", min: 3 },
  { fg: "success", bg: "canvas", min: 3 },
  { fg: "success", bg: "surface-1", min: 3 },
  { fg: "success", bg: "surface-2", min: 3 },
  { fg: "warning", bg: "canvas", min: 3 },
  { fg: "warning", bg: "surface-1", min: 3 },
  { fg: "warning", bg: "surface-2", min: 3 },
  { fg: "info", bg: "canvas", min: 3 },
  { fg: "info", bg: "surface-1", min: 3 },
  { fg: "info", bg: "surface-2", min: 3 },
  { fg: "live", bg: "hairline", min: 3 },
];

/**
 * The type scale: [size, line height] in px. Inter for everything, JetBrains Mono for ids, logs and code. `weights` and
 * `tracking` say how each size is set: the quiet sizes are regular, the headlines 500 to 600 with negative tracking
 * (px), as the system draws them. The names are Tailwind's `text-*`.
 */
export const typeScale = {
  caption: [12, 18],
  small: [13, 20],
  /** A button's label. */
  button: [14, 20],
  /** 15 px; the console sets it to 14 (`dense`, styles.css). */
  body: [15, 24],
  /** A card's title, and an option's. */
  card: [16, 24],
  /** The website's opening paragraph: large, but regular weight. */
  lede: [18, 28],
  section: [20, 26],
  figure: [24, 28],
  title: [28, 34],
  hero: [40, 44],
  display: [56, 62],
  timer: [54, 54],
  "timer-lg": [60, 60],
  "timer-xl": [72, 72],
} as const;

/** Weight and tracking (px) for each size that isn't plain 15 px regular. */
export const typeStyle: Record<string, { weight: 400 | 500 | 600; tracking: number }> = {
  button: { weight: 500, tracking: 0 },
  card: { weight: 500, tracking: 0 },
  section: { weight: 500, tracking: -0.2 },
  figure: { weight: 600, tracking: -0.4 },
  title: { weight: 600, tracking: -0.6 },
  hero: { weight: 600, tracking: -1 },
  display: { weight: 600, tracking: -1.8 },
  timer: { weight: 600, tracking: -1.8 },
  "timer-lg": { weight: 600, tracking: -1.8 },
  "timer-xl": { weight: 600, tracking: -1.8 },
};

/** The eyebrow: 12 px at 500, tracked out 0.4 px, in sentence case. */
export const eyebrow = { size: 12, lineHeight: 16, weight: 500, tracking: 0.4 } as const;

export const fontWeights = { regular: 400, medium: 500, semibold: 600 } as const;

/** What the first screen's font files hold: Basic Latin, the few Latin-1 signs we use, the punctuation, arrows and rupee. */
const BASIC_LATIN =
  "U+0020-007E,U+00A0,U+00A7,U+00A9,U+00B0,U+00B7,U+00D7,U+2013-2014,U+2018-201D,U+2022,U+2026,U+20B9,U+2190-2193,U+2212,U+2248";
/** The rest of Latin-1: accented letters and the signs we do not use. */
const LATIN_ACCENTS = "U+00A1-00A6,U+00A8,U+00AA-00AF,U+00B1-00B6,U+00B8-00D6,U+00D8-00FF";

/**
 * The fonts (web/DESIGN.md "Typography"): self-hosted, Latin plus the rupee sign, cut by scripts/subset-font.sh. Each
 * has a fallback, Arial scaled to the font's metrics, so the page does not move when the font arrives
 * (`: swap`). `preload` marks the files the first screen uses.
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
  mono: {
    family: "JetBrains Mono",
    file: "jetbrains-mono-400.woff2",
    weight: "400",
    range: `${BASIC_LATIN},${LATIN_ACCENTS}`,
    more: null,
    fallback: null,
  },
} as const;

/** The files a first screen preloads, by file name: Inter, for the app and the website alike. */
export const preloadedFonts = {
  app: [fonts.sans.file],
  site: [fonts.sans.file],
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
  /** Larger steps, for widths, heights and skeletons (Tailwind's own numbers, 4 px each): 128 to 384 px. */
  "32": 128,
  "36": 144,
  "40": 160,
  "48": 192,
  "56": 224,
  "64": 256,
  "72": 288,
  "80": 320,
  "96": 384,
  touch: 44,
  /** A button's side padding, 14 px. */
  button: 14,
  /** The top bar, 48 px. */
  header: 48,
  /** A button's height (44 on a touch screen: `touch`), and a table's rows: 36 in the console, 44 elsewhere. */
  control: 32,
  "row-admin": 36,
  /** A row of the phone's tile grid (a small tile is one row, a tall one two). */
  "tile-row": 88,
  /** The session screen's raised card on a phone, as its placeholder draws it. */
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
  /** Home on a laptop: a service card at its least, the row of running and credit panels, and the recent sessions' table. */
  "service-card": 176,
  /** The left sidebar, on every site that has one. */
  sidebar: 240,
  /** The sidebar collapsed to its icons. */
  "sidebar-icons": 56,
  /** The running session's row of the GPU's panel (with the loss chart over it) and the log: the same before the log is
   * read and after, so the steps under it never move. */
  "session-row": 580,
  /** The live session's training-loss chart. */
  loss: 200,
  "credit-row": 216,
  /** The renter's Home from 1200 px (ADR 0058): its row of the credit, the GPUs online and this month, one height
   * before their figures arrive and after, so nothing under them moves. */
  "home-panel": 256,
  /** The host's home from 1200 px: its row of the demand, the score and the last payouts, the same way. */
  "host-panel": 320,
  recent: 320,
  /** The hero card's chart on the website. */
  "hero-chart": 168,
  /** A step card on the website, at its least. */
  step: 256,
} as const;

/**
 * Tags are 6 px, buttons and inputs 8, cards 12, big panels 16 (dialogs, sheets and the website's framed pictures);
 * status chips, segmented tabs and the avatar are round.
 */
export const radii = {
  tag: 6,
  control: 8,
  card: 12,
  panel: 16,
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
  content: 1200,
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

/**
 * One easing curve and one duration (web/DESIGN.md "Motion"): 150 ms ease-out on a hover, a menu, a sheet and a toast.
 * Nothing moves under reduced motion.
 */
export const motion = {
  durations: { press: 150, appear: 150, large: 150 },
  /** The running pulse: the only continuous motion beside the shimmer. */
  pulse: 2_000,
  easing: { enter: "cubic-bezier(0, 0, 0.2, 1)", leave: "cubic-bezier(0, 0, 0.2, 1)" },
} as const;

/**
 * Depth is the surface ladder and hairlines, so there are no shadows but a soft one on what floats over a scrim (a menu, a
 * popover, a dialog), and a faint top-edge highlight on a lifted panel.
 */
export const shadows = {
  menu: "0 8px 24px rgb(0 0 0 / 0.4)",
  lift: "inset 0 1px 0 rgb(255 255 255 / 0.04)",
} as const;

/**
 * What sits over what, by name (`z-raised`, `z-sheet`…), never a number: sticky rows, bars and the sidebar; a table's sticky
 * head over its pinned cells; menus and popovers; a dialog's backdrop and the dialog; a toast over everything but a step-up
 * opened over a sheet (`above`).
 */
export const layers = {
  raised: 10,
  sticky: 20,
  popover: 30,
  overlay: 40,
  sheet: 50,
  toast: 60,
  above: 70,
} as const;
