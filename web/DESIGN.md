# The IdleO design language

The standard every screen is checked against, now and later. Numbers, not adjectives: if a screen breaks a rule here, the screen is wrong, or this file changes first, with Nikki's say.

The tokens below live in code in one place, `web/src/design/tokens.ts` (the accent in `web/src/brand/brand.ts`, so the brand stays one change), and Tailwind's theme is built from them. Nothing outside that file names a colour, a size or a duration; a lint rule refuses raw colours and arbitrary values.

## Who it's for

- **Renters** are engineering students and small teams who want a GPU for an hour without learning a cloud console.
  - They book sessions and use their outputs on a **laptop**, because that's where their code, datasets and notebooks are.
  - They check a running session, stop it, or see what it cost from wherever they are, often a **phone**.
- **Hosts** are gaming-laptop owners who want to know one thing: "is my machine earning, and is it safe?" Their laptop is busy hosting, so they check in from their **phone**.

**So both sizes are first-class, each for its job:**
- the laptop is the main place for booking and for working with outputs;
- the phone must be excellent for checking status, stopping a session, seeing credit and cost, and a host's online status and earnings.

Each screen is designed first for the size it's mostly used on, then checked fully at the other. No screen may be broken or cramped at 360 px or at 1280 px.

| Screen | Designed first for | Must also be complete on |
| --- | --- | --- |
| Sign-in, welcome | Phone | Laptop |
| Home (credit and sessions) | Phone | Laptop |
| Session (live) | Phone | Laptop |
| All sessions | Phone | Laptop |
| Credit history | Phone | Laptop |
| Booking | Laptop | Phone |
| Host home and earnings (later) | Phone | Laptop |

## Principles

1. **One obvious next step per screen.** A screen has at most one primary button (filled accent): its next step. A screen that only shows information, like the credit history, may have none. Everything else is a secondary (outlined) or quiet (text) action. Two filled accent buttons on one screen is a bug.
2. **Money is always exact and always visible where it matters:** credit, the price before booking, the cost so far while a session runs, and the final charge.
   - Rupees are formatted from integer paise with Indian digit grouping (₹1,23,456.78), never through floating point.
   - There is one formatting function in the whole app, `formatPaise` in `web/src/lib/money.ts`. It refuses anything that isn't a whole number of paise.
   - Money is always shown in tabular figures, so digits line up and don't jitter as they change.
3. **The app never lies about state.**
   - Live data looks live: a session that's running shows when it was last refreshed.
   - Stale data says so: if a refresh fails, the old figures stay with a line saying when they're from and that the app is retrying.
   - Nothing pretends to have succeeded before the server agrees. No optimistic money updates, ever: a charge, a hold or a stop appears only once the API has returned it.
4. **Calm, fast and quiet.**
   - No spinner where a skeleton fits. A spinner is allowed only inside a button that has been pressed, for at most the length of that request.
   - No modal where a page works. Dialogs are only for confirming something destructive (stopping a session).
   - No jargon. Say "GPU memory", not "VRAM", unless it's next to a number ("24 GB GPU memory").
   - Error messages say what happened and what to do next, in that order: "Your sign-in link has expired. Send a new one."
5. **Speed is a feature.** Navigation feels instant. Every interaction shows a response within 100 ms, even if that's only a pressed state. Data a screen has already seen is shown at once from the cache, then refreshed.

## Typography

- **One family: Inter** (Rasmus Andersson, SIL Open Font License 1.1), version 4.1, self-hosted. No request ever goes to a font service.
  - **Why Inter:** it was drawn for user interfaces at small sizes on screens. It has true tabular figures (`tnum`), and it includes the rupee sign (₹, U+20B9), which many UI families lack: of the candidates checked, IBM Plex Sans has no tabular figures, and Geist's rupee sits in a subset without them.
  - **One file:** a subset of Inter's variable font, cut by `web/scripts/subset-font.sh`, holds Basic Latin, Latin-1, the punctuation we use, the arrows, the minus sign and ₹. The weight axis is kept from 400 to 650 and the optical size is pinned at text size, which makes it 25.5 KB as WOFF2.
  - **It never swaps.** It's preloaded with `font-display: optional`. If it hasn't arrived by the first paint (a slow first visit), that page keeps the fallback, Arial scaled to Inter's metrics, because even matched metrics leave glyph widths that differ, and a swap would move text sideways. Every later page has it from the cache.
- **Logs are the one exception:** a session's log panel uses the system's monospace fonts (`font-mono`: ui-monospace, SF Mono, Menlo, Consolas and so on), so nothing is downloaded for it. Nothing else is set in monospace.
- **Numbers** (money, durations, counts, times) use Inter's tabular figures: the `tabular` class sets `font-variant-numeric: tabular-nums`.
- **Weights:** 400 for reading, 500 for labels and buttons, 600 for headings and key figures. No other weights.
- **The scale: six sizes, no more.**

| Token | Size / line height | Used for |
| --- | --- | --- |
| `text-caption` | 12 / 16 px | Timestamps, helper text under a field, table footnotes |
| `text-small` | 14 / 20 px | Secondary text, labels, buttons, navigation |
| `text-body` | 16 / 24 px | Reading text, form inputs (16 px stops iOS zooming on focus) |
| `text-title` | 20 / 28 px | Card and section titles |
| `text-heading` | 24 / 32 px | The page title; the hero figure on a phone |
| `text-display` | 32 / 40 px | The hero figure (available credit) from 768 px up |

- **Line length:** reading text is at most 68 characters wide (`max-w-prose`, set to 68ch). Tables and lists may be wider.
- **Case:** sentence case everywhere: titles, buttons, labels, navigation. No all-caps text.

## Colour

A neutral scale, one accent, and success, warning and danger, in a light and a dark theme.

- **The theme** follows the system preference (`prefers-color-scheme`) until the person chooses Light or Dark from the user menu. Their choice is remembered on this device only.
- **The accent is indigo-violet.** Green is kept for success, so "earned" and "succeeded" never blur with the brand colour, and red only ever means danger.
- **Colour is never the only signal.** A state chip has a word in it; an error has an icon and a sentence.

| Token | Light | Dark | Used for |
| --- | --- | --- | --- |
| `canvas` | `#F7F7F9` | `#0F0F14` | The page behind everything |
| `surface` | `#FFFFFF` | `#17171F` | Cards, the header, the navigation bar |
| `sunken` | `#EFEFF3` | `#0B0B0F` | Skeletons, wells inside a card |
| `border` | `#E2E2E8` | `#2A2A35` | Separating surfaces (decorative) |
| `border-strong` | `#8A8A99` | `#71718A` | Input and control outlines (must meet 3:1) |
| `text` | `#16161D` | `#EDEDF3` | Primary text |
| `text-muted` | `#55556A` | `#A3A3B8` | Secondary text; still meets 4.5:1 |
| `accent` | `#4B3FD1` | `#8F86FF` | Primary buttons, the selected tab, focus rings |
| `accent-hover` | `#3E33B8` | `#A39CFF` | Primary buttons when hovered or pressed |
| `accent-fg` | `#FFFFFF` | `#0F0F14` | Text on `accent` |
| `accent-text` | `#4136C4` | `#A39CFF` | Links and accent text on `canvas` or `surface` |
| `accent-soft` | `#EEEDFC` | `#221F45` | The selected navigation item's background, info chips |
| `success` | `#157347` | `#4CC38A` | Completed, online, money received |
| `success-soft` | `#E7F5EE` | `#10291D` | Success chip background |
| `warning` | `#8A5300` | `#F0B44C` | Low credit, stale data |
| `warning-soft` | `#FFF3DD` | `#2E220B` | Warning chip and banner background |
| `danger` | `#C0262D` | `#FF6B6B` | Failed, errors, destructive actions |
| `danger-soft` | `#FDECEC` | `#331414` | Error banner background |
| `danger-fg` | `#FFFFFF` | `#0F0F14` | Text on `danger` |
| `focus` | `#4B3FD1` | `#A39CFF` | The 2 px focus ring, offset 2 px |

### Contrast, WCAG 2.2 AA

Every text pair meets 4.5:1 (1.4.3), and every control outline, focus ring and button edge meets 3:1 (1.4.11). `web/src/design/tokens.test.ts` computes each ratio from the tokens and fails if any is below its minimum, or if it differs from this table by more than 0.01.

| Foreground on background | Light | Dark | Minimum |
| --- | --- | --- | --- |
| `text` on `canvas` | 16.82 | 16.39 | 4.5 |
| `text` on `surface` | 18.00 | 15.28 | 4.5 |
| `text-muted` on `canvas` | 6.78 | 7.73 | 4.5 |
| `text-muted` on `surface` | 7.26 | 7.20 | 4.5 |
| `text-muted` on `sunken` | 6.33 | 7.94 | 4.5 |
| `accent-fg` on `accent` | 7.17 | 6.39 | 4.5 |
| `accent-fg` on `accent-hover` | 8.86 | 7.97 | 4.5 |
| `accent-text` on `canvas` | 7.70 | 7.97 | 4.5 |
| `accent-text` on `surface` | 8.24 | 7.43 | 4.5 |
| `accent-text` on `accent-soft` | 7.12 | 6.49 | 4.5 |
| `success` on `surface` | 5.87 | 8.04 | 4.5 |
| `success` on `success-soft` | 5.23 | 6.99 | 4.5 |
| `warning` on `surface` | 6.33 | 9.62 | 4.5 |
| `warning` on `warning-soft` | 5.76 | 8.40 | 4.5 |
| `danger` on `surface` | 5.90 | 6.42 | 4.5 |
| `danger` on `danger-soft` | 5.17 | 6.05 | 4.5 |
| `danger-fg` on `danger` | 5.90 | 6.89 | 4.5 |
| `border-strong` on `surface` | 3.40 | 3.76 | 3 |
| `border-strong` on `canvas` | 3.18 | 4.03 | 3 |
| `focus` on `canvas` | 6.70 | 7.97 | 3 |
| `focus` on `surface` | 7.17 | 7.43 | 3 |
| `accent` on `surface` | 7.17 | 5.96 | 3 |

## Space and shape

- **Spacing is a 4 px scale:** 0, 4, 8, 12, 16, 20, 24, 32, 40, 48, 64 px (`1` to `16` in Tailwind's units). Nothing in between.
  - Inside a card: 16 px on a phone, 24 px from 768 px up.
  - Between cards: 16 px; between page sections: 32 px.
  - Between a label and its field: 8 px; between fields: 20 px.
- **Corners:** 8 px for controls (buttons, inputs, chips are fully round at 9999 px), 12 px for cards, 16 px for sheets and dialogs.
- **Surfaces are separated by 1 px borders, not shadows.** A card is `surface` with a `border` edge on `canvas`. Shadows are only for things that float above the page: menus, popovers and dialogs (one shadow token, `shadow-float`).
- **Touch targets are at least 44 × 44 px**, even where the visible control is smaller (the hit area is padded).
- **The layout grid:**

| Width | Columns | Margin | Gutter | Content |
| --- | --- | --- | --- | --- |
| 360 px and up (phone) | 4 | 16 px | 16 px | Full width, one column |
| 768 px and up (tablet, small laptop) | 8 | 24 px | 24 px | Up to two columns |
| 1280 px and up (laptop) | 12 | 32 px | 24 px | Centred, at most 1120 px wide |

- **Navigation:** below 768 px, a bottom tab bar (Home, Book, Credit) and a compact top bar (wordmark, credit, user menu). From 768 px, one top bar with the same destinations as links. Home shows credit in its own card, so the bar's credit is hidden there: credit appears once per screen.
  - **Why a bottom bar on phones:** the renter's phone tasks are checking and stopping, done one-handed. The bottom of the screen is where the thumb rests, and three destinations fit with room for labels. A hamburger menu would hide them behind an extra tap.

## Motion

- **Durations:** 120 ms for presses, hovers and toggles; 180 ms for small things appearing (menus, popovers, toasts); 240 ms for large things (sheets, dialogs, page content fading in). Nothing longer.
- **One easing family**, a decelerating curve:
  - entering: `cubic-bezier(0.2, 0, 0, 1)`;
  - leaving: `cubic-bezier(0.4, 0, 1, 1)`, at two-thirds of the entering duration.
- **What moves:** opacity and small translations (at most 8 px) of things appearing or leaving; colour on hover and press; the skeleton's shimmer.
- **What never moves:** money figures (they change in place, never count up), page layout, and anything the person is reading or about to tap.
- **The running pulse:** while a session is `RUNNING`, the dot in its state chip pulses its opacity over 2 seconds (`motion.pulse`). It's on the state chip only, it's the one continuous motion beside the skeleton's shimmer, and it's off under reduced motion.
- **`prefers-reduced-motion: reduce` removes all non-essential motion:** no translations and no shimmer. Changes are instant; only focus and state colours remain.
- **No layout shift, ever** (cumulative layout shift 0 on every screen):
  - skeletons have the same size as the content that replaces them;
  - images and icons have fixed dimensions;
  - the font never swaps (see Typography);
  - banners appear in space already reserved, or push nothing (they overlay the page edge).

## States

Every view that shows data designs four states before it's built, and each one is shown in the review gallery:

| State | What it shows |
| --- | --- |
| **Loading** | A skeleton with the shape of the content: same rows, same heights, no text. No spinner. |
| **Empty** | Why it's empty and what to do: "No sessions yet. Book a GPU to run your first one." With the one next step as the primary action. |
| **Error** | What happened, in a sentence, plus a Retry button: "We couldn't load your sessions. Check your connection and try again." Never a raw status code. |
| **Partial or stale** | The data it has, plus one line: "Couldn't refresh. Showing figures from 12:04. Retrying…" Stale money is never presented as current. |

## Times

- **Relative where it's about how long ago:** "started 18 min ago", "2 h ago", "yesterday", then the date after a week. The exact time is in the `<time>` element's `datetime` and its title, so it's a hover away.
- **Elapsed time ticks** every second while a session runs, as a clock ("18:04", "1:02:05"), and stops when it ends. Durations of ended sessions keep seconds only under ten minutes ("6 min 10 s", "41 min").
- **One clock for the app** (`src/lib/clock.ts`): every relative time moves on together every 30 seconds, and every elapsed time every second, from a single timer.

## The phone action bar

On a phone, a screen whose one next step is used while watching something (Stop, on a running session) puts that primary action in a bar fixed just above the tab bar. Its height is reserved at the end of the page, so nothing it covers is out of reach and nothing moves when it appears. It holds that one action and nothing else; from 768 px the action sits beside the screen's heading instead.

## Voice

Short, plain sentences. Second person ("your credit"), active voice, no exclamation marks.

**The five words for the core things, and never a synonym for them:**

| Word | Means | Never say |
| --- | --- | --- |
| **session** | One booked run of a template on a host's GPU | job, run, instance, rental, task |
| **credit** | Money in the renter's account, in rupees | balance, funds, wallet, money, coins |
| **host** | A person's machine that runs sessions | provider, node, server, worker, machine (in the UI) |
| **template** | What a session runs: the image and its settings | image, recipe, preset, environment |
| **output** | A file a session produced, to download | result, artifact, download, file (as the name of the thing) |

The parts of credit have fixed labels too: **Available** (what you can spend now), **On hold** (set aside for sessions that are running or about to), and **Total** (the two together).

## Brand

The product name isn't decided. **IdleO** is the working name.

- The name, the wordmark, the accent and the favicon live in one place, `web/src/brand/`, so the final brand is one small change.
- **The wordmark** is original: "Idle" set in Inter SemiBold, drawn as outlines, followed by a ring for the "O" in the accent colour, with a small gap at the top, like a power button at rest. The favicon is the ring alone.
- No stock logos or icons stand in for the brand.

## Icons

One set only: **Lucide**, imported one icon at a time. 20 px in controls and 16 px inline with text, 1.75 px stroke, coloured with the text they sit beside. An icon-only button always has a visible tooltip and an accessible label.
