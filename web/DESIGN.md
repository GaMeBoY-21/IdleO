# The IdleO design language

The standard every screen is checked against, now and later. Numbers, not adjectives: if a screen breaks a rule here, the screen is wrong, or this file changes first, with the product owner's say.

The look is **the IdleO system** (ADR 0060, which supersedes ADR 0043's Night Shift): Linear's structure (`design-reference/linear.md`) with IdleO's identity. A surface ladder, hairline borders instead of shadows, compact controls, quiet type with negative tracking on headings, and a single accent, volt, used sparingly. It is not Linear's name, its fonts or its lavender. It comes in two themes with one layout: dark, the default, and light, which follows the device. Requesting a GPU like a ride (ADR 0021's behaviour) is unchanged.

The tokens below live in code in one place, `web/src/design/tokens.ts` (the brand's name and wordmark in `web/src/brand/brand.ts`, so the brand stays one change), and Tailwind's theme and the fonts are built from them (`web/scripts/tokens-css.ts`). Nothing outside that file names a colour, a size or a duration; a lint rule refuses raw colours and arbitrary values.

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
| Home (what's running, credit, what to run) | Phone | Laptop |
| Book | Laptop | Phone |
| Session: finding, found, live, done | Phone | Laptop |
| All sessions | Phone | Laptop |
| Credit history | Phone | Laptop |
| Hosting: the tab, one machine, price and limits, sessions it ran, earnings | Phone | Laptop |
| Add a machine | Laptop | Phone |

## Principles

1. **One question per screen, one obvious next step.** A screen asks one thing in big type ("What do you want to run?") and has at most one primary button (volt with canvas text; `primary`): its next step. A screen that only shows information may have none. Two primary buttons on one screen is a bug. The volt button inside the "Your request" card is that screen's one primary.
2. **Requesting is like a ride.** The renter says what to run and gives their data, then presses "Request a GPU". We match the best free GPU that fits, right now; they never choose a GPU or type a price. They see the price range and an estimate before they request, and pay the matched GPU's price by the minute.
3. **Money is always exact and always visible where it matters:** credit, the price before requesting, the cost so far while a session runs, and the final charge.
   - Rupees are formatted from integer paise with Indian digit grouping (₹1,23,456.78), never through floating point.
   - There is one formatting function in the whole app, `formatPaise` in `web/src/lib/money.ts`. It refuses anything that isn't a whole number of paise.
   - Money is always shown in tabular figures, so digits line up and don't jitter as they change.
4. **The app never lies about state.**
   - Live data looks live: a session that's running shows when it was last refreshed.
   - Stale data says so: if a refresh fails, the old figures stay with a line saying when they're from and that the app is retrying.
   - Nothing pretends to have succeeded before the server agrees. No optimistic money updates, ever: a charge, a hold or a stop appears only once the API has returned it.
5. **Calm, fast and quiet.**
   - No spinner where a skeleton fits. A spinner is allowed only inside a button that has been pressed, for at most the length of that request.
   - No modal where a page works. The dialogs are the one that confirms stopping a session and, since Oct 13 (ADR 0028), the sheet that takes a report of a problem: a choice of six reasons and what happened, which the product owner asked to be a short sheet, not a page. What can wait (a service's advanced settings) is one row that opens in place, with its defaults set; deleting from My files asks once more in its own row.
   - No jargon. Say "GPU memory", not "VRAM", unless it's next to a number ("24 GB GPU memory").
   - Error messages say what happened and what to do next, in that order: "Your sign-in link has expired. Send a new one."
6. **Speed is a feature.** Navigation feels instant. Every interaction shows a response within 100 ms, even if that's only a pressed state. Data a screen has already seen is shown at once from the cache, then refreshed.


## Typography

- **Two families, both self-hosted** (SIL Open Font License 1.1; each licence is in `web/public/fonts/` beside its file). No request ever goes to a font service. Each is a subset cut by `web/scripts/subset-font.sh`: Basic Latin, Latin-1, the punctuation we use, the arrows, the minus sign, `≈` (U+2248) and the rupee sign (₹, U+20B9); tabular figures kept.
  - **Inter** for everything, one variable file for weights 400 to 700.
  - **JetBrains Mono**, weight 400, for ids, logs and code (`font-mono`), and nothing else.
  - Archivo is gone: there is no uppercase headline face and no uppercase headline.
- **Files** (WOFF2): `inter-400-700.woff2` 16.8 KB, `inter-400-700-latin1.woff2` 9.2 KB (Inter's accented letters, fetched only by a page that has one) and `jetbrains-mono-400.woff2` 18.3 KB. Both the app and the website preload Inter alone (16.8 KB); the wordmark is drawn, so it needs no file (ADR 0049). The website's code is set in the reader's own monospace font, so JetBrains Mono is the app's alone. The first-screen files hold Basic Latin and the few signs we use (a test keeps every other accented letter out of our own text).
- **They swap.** Each face is `font-display: swap`, over a fallback of Arial (or Liberation Sans or Arimo, which have its metrics, on a machine without Arial: Linux, ChromeOS, the build's browser) scaled to the face's metrics (`size-adjust`, `ascent-override`, `descent-override`; generated from the same table), so the swap moves nothing: cumulative layout shift stays 0.
- **Numbers** (money, durations, counts, times) use tabular figures everywhere: the body sets `font-variant-numeric: tabular-nums`, and the `tabular` class sets it where a component resets it.
- **Weights:** 400 for reading, 500 for buttons, navigation, card and section titles, 600 for page titles, heroes and figures. Nothing is heavier than 600. Headings are tight: negative letter-spacing is built into the token of every size from `section` up (−0.2 px at 20, −0.4 px at 24, −0.6 px at 28, −1.0 px at 40, −1.8 px at 56 and the timers).
- **The eyebrow** is `text-eyebrow`: 12 px at 500, tracked +0.4 px, in sentence case.

| Token | Size / line height | Used for |
| --- | --- | --- |
| `text-caption` | 12 / 16 px | Captions, timestamps, chips, the line under a field |
| `text-small` | 13 / 20 px | Secondary text, table cells, help lines |
| `text-button` | 14 / 20 px | Buttons, navigation (weight 500) |
| `text-body` | 15 / 24 px | Reading text (14 px in the console, set once by `.dense`) |
| `text-card` | 16 / 24 px | A card's or an option's title (weight 500) |
| `text-lede` | 18 / 28 px | The website's opening paragraph (regular) |
| `text-section` | 20 / 28 px | A section's title (weight 500, −0.2 px) |
| `text-figure` | 24 / 28 px | A figure on a small card (weight 600) |
| `text-title` | 28 / 34 px | A page's title (weight 600, −0.6 px) |
| `text-hero` | 40 / 44 px | A page hero, a big figure (weight 600, −1.0 px) |
| `text-display` | 56 / 62 px | The website's headline (weight 600, −1.8 px) |
| `text-timer` | 54 / 54 px | The running timer on a phone, and the big credit |
| `text-timer-lg` | 60 / 60 px | The running timer in the live session's band |
| `text-timer-xl` | 72 / 72 px | The running timer on a laptop |

## Colour

Two themes with the same layout. The **dark** theme is the default: a near-black canvas (`#08090A`) and a ladder of four surfaces, each a step lighter. The **light** theme is a white canvas and the same ladder going the other way. Both have hairline borders and no shadows.

- **Choosing.** An Appearance choice (Match my device, Light, Dark) is a cookie on the parent domain, so the website and the app, on different addresses of one domain, share it on the same browser; it is never a server setting. In the app it is under Account, and on the website a small switch in the footer. With no choice, both follow the device's setting (`prefers-color-scheme`); with no setting, dark.
- **No flash.** A tiny inline script in each page's head (`themeBootScript`, allowed by its hash in the content security policy) reads the cookie and sets `data-theme` on `<html>` before the first paint. Every colour token has a dark and a light value in `tokens.ts`, switched by that one attribute (and by the device's setting when there is none).
- **The surface ladder.** The page is `canvas`; a card is `surface-1` with a 1 px `hairline`; what is on a card, or hovered, or selected, is the next step up (`surface-2`, `surface-3`). Depth is the ladder and the hairlines, never a shadow. The only shadows are a soft one on what floats over a scrim (a menu, a popover, a dialog: `shadow-menu`), and a faint top-edge highlight on a lifted panel (`shadow-lift`).
- **Volt is the one accent** (`#C8FF3D`, with canvas text on it). It is used only for: the screen's one primary button, the focus ring (2 px, at half strength in the dark theme), the brand mark, and live or running indicators. It is never a background, a card fill or decoration. A hover shade (`volt-hover`) and a pressed shade (`volt-pressed`) exist. In the light theme, where volt fails contrast as text and as a ring, `live` and `focus` are a darker volt (`#4A7300`); the primary button is still volt with canvas text.
- **Status colours** (`success`, `warning`, `danger`, `info`) are muted and for chips' dots, small marks and charts. They are never fills, with one exception: the danger button is solid, in the confirm dialog only. A status chip is a `surface-2` pill with 12 px text, a coloured dot and words.
- **Colour is never the only signal.** A state chip has a word in it; an error has an icon and a sentence; the selected option has a check.
- **Two values are a hair off the reference** so the contrast table holds in both themes: the light theme's `ink-subtle` and `ink-tertiary` are darker than the reference's, and `control` (the border of a field or a switch, WCAG 1.4.11) is stronger than a hairline.

| Token | Dark | Light | Used for |
| --- | --- | --- | --- |
| `canvas` | `#08090A` | `#FFFFFF` | The page |
| `surface-1` | `#0F1011` | `#F7F8F8` | Cards, the sidebar, a field, a secondary button |
| `surface-2` | `#141516` | `#F1F2F3` | A card on a card, a hover, a chip's fill, a menu |
| `surface-3` | `#18191A` | `#EBECEE` | The current nav item, a selected option, a skeleton |
| `surface-4` | `#1C1D1F` | `#E4E5E7` | A skeleton's shimmer, a pressed fill |
| `hairline` | `#23252A` | `#E4E5E7` | A card's border, dividers between rows |
| `hairline-strong` | `#34343A` | `#D6D8DB` | A secondary button's border, a dialog's edge |
| `hairline-tertiary` | `#3E3E44` | `#C9CBCF` | A selected option's border, a quiet bar |
| `control` | `#666B73` | `#80858E` | A field's, switch's and radio's outline (must meet 3:1) |
| `ink` | `#F7F8F8` | `#0B0C0E` | Primary text and headings |
| `ink-muted` | `#D0D6E0` | `#3C4149` | Emphasised body text |
| `ink-subtle` | `#8A8F98` | `#5F646D` | Secondary text, captions, table heads |
| `ink-tertiary` | `#80858E` | `#666B74` | Placeholders, quiet labels |
| `volt` | `#C8FF3D` | `#C8FF3D` | The screen's one primary button |
| `volt-hover` | `#D6FF6E` | `#D6FF6E` | The primary button when hovered |
| `volt-pressed` | `#B3E02F` | `#B3E02F` | The primary button when pressed |
| `on-volt` | `#08090A` | `#08090A` | Text on volt |
| `live` | `#C8FF3D` | `#4A7300` | Live and running marks, progress, the brand mark |
| `focus` | `#688423` | `#4A7300` | The 2 px focus ring, offset 2 px |
| `success` | `#27A644` | `#1F8A3C` | A done or healthy dot, a chart's good series |
| `warning` | `#D9A400` | `#A07400` | A warning's icon and dot |
| `danger` | `#E5484D` | `#C8282D` | Errors, a failed dot, the danger button's text |
| `info` | `#4C8DF6` | `#2F6FE0` | An information icon and dot |
| `danger-fg` | `#08090A` | `#FFFFFF` | Text on a solid danger button (the confirm dialog) |
| `scrim` | `#000000` | `#000000` | The dimmed page behind a dialog (at 60%) |
| `paper` | `#FFFFFF` | `#FFFFFF` | A QR code's ground: always white |

### Contrast, WCAG 2.2 AA

Every text pair meets 4.5:1 (1.4.3; the 24 px and larger headlines need only 3:1 and are held to 4.5:1 too), and every control outline, focus ring and graphic meets 3:1 (1.4.11). `web/src/design/tokens.test.ts` computes each ratio from the tokens, **in both themes**, and fails if any is below its minimum, or if it differs from this table by more than 0.01. Tertiary text is for placeholders and quiet labels on the canvas and the first two surfaces only; it never sits on a hover fill.

| Foreground on background | Dark | Light | Minimum |
| --- | --- | --- | --- |
| `ink` on `canvas` | 18.73 | 19.57 | 4.5 |
| `ink` on `surface-1` | 17.90 | 18.39 | 4.5 |
| `ink` on `surface-2` | 17.18 | 17.46 | 4.5 |
| `ink` on `surface-3` | 16.55 | 16.55 | 4.5 |
| `ink` on `surface-4` | 15.85 | 15.52 | 4.5 |
| `ink-muted` on `canvas` | 13.64 | 10.27 | 4.5 |
| `ink-muted` on `surface-1` | 13.04 | 9.65 | 4.5 |
| `ink-muted` on `surface-2` | 12.52 | 9.16 | 4.5 |
| `ink-muted` on `surface-3` | 12.05 | 8.69 | 4.5 |
| `ink-subtle` on `canvas` | 6.13 | 5.95 | 4.5 |
| `ink-subtle` on `surface-1` | 5.86 | 5.59 | 4.5 |
| `ink-subtle` on `surface-2` | 5.63 | 5.31 | 4.5 |
| `ink-subtle` on `surface-3` | 5.42 | 5.03 | 4.5 |
| `ink-tertiary` on `canvas` | 5.37 | 5.36 | 4.5 |
| `ink-tertiary` on `surface-1` | 5.14 | 5.03 | 4.5 |
| `ink-tertiary` on `surface-2` | 4.93 | 4.78 | 4.5 |
| `on-volt` on `volt` | 16.92 | 16.92 | 4.5 |
| `on-volt` on `volt-hover` | 17.45 | 17.45 | 4.5 |
| `on-volt` on `volt-pressed` | 12.92 | 12.92 | 4.5 |
| `live` on `canvas` | 16.92 | 5.61 | 4.5 |
| `live` on `surface-1` | 16.17 | 5.27 | 4.5 |
| `live` on `surface-2` | 15.52 | 5.00 | 4.5 |
| `danger` on `canvas` | 5.09 | 5.53 | 4.5 |
| `danger` on `surface-1` | 4.87 | 5.20 | 4.5 |
| `danger` on `surface-2` | 4.67 | 4.93 | 4.5 |
| `danger-fg` on `danger` | 5.09 | 5.53 | 4.5 |
| `control` on `canvas` | 3.72 | 3.71 | 3 |
| `control` on `surface-1` | 3.55 | 3.49 | 3 |
| `control` on `surface-2` | 3.41 | 3.31 | 3 |
| `focus` on `canvas` | 4.66 | 5.61 | 3 |
| `focus` on `surface-1` | 4.46 | 5.27 | 3 |
| `focus` on `surface-2` | 4.28 | 5.00 | 3 |
| `focus` on `surface-3` | 4.12 | 4.75 | 3 |
| `success` on `canvas` | 6.29 | 4.41 | 3 |
| `success` on `surface-1` | 6.01 | 4.15 | 3 |
| `success` on `surface-2` | 5.77 | 3.94 | 3 |
| `warning` on `canvas` | 8.79 | 4.21 | 3 |
| `warning` on `surface-1` | 8.40 | 3.95 | 3 |
| `warning` on `surface-2` | 8.06 | 3.75 | 3 |
| `info` on `canvas` | 6.12 | 4.70 | 3 |
| `info` on `surface-1` | 5.85 | 4.41 | 3 |
| `info` on `surface-2` | 5.61 | 4.19 | 3 |
| `live` on `hairline` | 13.02 | 4.45 | 3 |

## Shapes and space

- **Cards, depth from the ladder.** A card is `surface-1` with a 1 px `hairline` and 12 px corners (`rounded-card`), 16 px of padding on a phone, 24 to 32 on a laptop. A raised card (`surface-2`) is what matters now. A big panel (`rounded-panel`, 16 px: a dialog, a sheet, the website's framed pictures) also gets the faint top-edge highlight. There are no shadows beside the menu's and the lifted panel's.
- **Corners:** 6 px on tags (`rounded-tag`); 8 px on buttons and inputs (`rounded-control`); 12 px on cards (`rounded-card`); 16 px on big panels (`rounded-panel`); pills (`rounded-full`) on status chips, segmented tabs and the avatar.
- **The selected option** goes up the ladder (`surface-3`) with a stronger border and a check; the ones not selected are plain cards.
- **Spacing is a 4 px base:** 4, 8, 12, 16, 24, 32, 48, 96 px. Padding, margins and gaps stay on the 4/8 px grid and a lint rule (`idleo/design-tokens`) refuses the off-grid half steps (2, 6, 10, 12, 14, 18, 20 px and the rest) there; they stay only for heights, line heights and a button's 14 px side padding (`px-button`). Larger steps for widths, heights and skeletons are the scale's own: 128 to 384 px.
  - Between sections: 24 to 32 px in the app, 96 px on the website; between a card's title and its content: 16 px; between options: 8 px.
- **Heights:** buttons and inputs 32 px (`min-h-control`); 40 px for the large button at the foot of a flow; the top bar 48 px; table rows 44 px, and 36 px in the console. On a touch screen (`pointer-coarse`) every control is 44 px (`touch`), so a thumb always has its target, WCAG 2.5.8.
- **Named sizes:** the finding ring 88 px on a 168 px disc (`finding-ring`, `finding`); the log panel at least 384 px (`panel`); Book's price beside its GPU card 112 px on a laptop (`price`), so it never moves as it arrives; the website's framed pictures use the same components as the app.
- **The layout:**

| Width | Layout | Margin |
| --- | --- | --- |
| Up to 767 px (phone) | One column of cards; a 48 px header and a docked 56 px bottom tab bar of the five most used destinations and "More"; Book and the session are full-screen flows with a back button | 16 px |
| 768 px and up (tablet, small laptop) | The left sidebar and the 48 px top bar; the same cards, wider | 32 px |
| 1200 px and up (laptop) | The sidebar and a twelve-column grid of cards, content at most 1200 px on the renter and host sites (full width in the console); Book is its questions beside the "Your request" card | 40 px |

- **Navigation:** each site has its own (ADR 0058, `src/sites/<site>/site.ts`). The renter app: Home, Book a GPU, Sessions, My files and Credit. The host site: Home, Laptops, Earnings, Payouts and Help. Neither has the other's screens; the account menu has one quiet link to the other ("Earn with your laptop", "Rent a GPU").
  - **Laptop and tablet:** a **left sidebar**, 240 px wide (collapsible to 56 px of icons, remembered per browser), on `surface-1` with a hairline on its right: the brand mark at the top, then the site's destinations (32 px rows, each with its icon), and the collapse button at the foot. The current item goes up to `surface-3`. A **top bar** of 48 px runs over the content: where you are on the left, the bell and the account's menu on the right (the console's adds the search palette).
  - **Phone (under 768 px):** the sidebar becomes a **bottom tab bar** on `surface-1`, 56 px tall and docked to the edge with a hairline over it: the site's five destinations and "More", which opens the account, the other site and Sign out. The current one is in the ink colour, the others in `ink-subtle`. Book and the session screen are flows: on a phone they are full screen with their own back button.
  - A laptop and its price and limits, adding one and approving a link are flows on the host site's phone layout, with their own back button; its home and its earnings keep the bar.
- **Tables:** 36 px rows in the console and 44 px elsewhere, a hairline between rows, sticky headings, a quiet hover on `surface-2`, numbers aligned right in tabular figures.
- **Empty states:** a small icon, one line saying what would be here, and one button. Calm, not big.

## Buttons

8 px corners, 32 px tall (28 in the console's tables; 44 on a touch screen), 8 × 14 px of padding, Inter at 14 px and weight 500.

- **Primary** (`primary`): volt with canvas text, a hover and a pressed shade. At most one on a screen.
- **Secondary** (`secondary`): `surface-1` with a `hairline-strong` border, for the other choices.
- **Ghost** (`ghost`): no fill, a `surface-2` hover, for everyday and quiet actions.
- **Danger** (`danger`): a ghost button with danger text. It turns solid (`solid`) only in the confirm dialog.
- **Quiet:** a text action, like "Change".
- **Large** (`large`): 40 px, the one at the foot of a flow.
- **Chips and segmented tabs** are pills; the chosen one goes up the ladder. A pressed button that's waiting shows a small spinner inside it.
- **Inputs** are `surface-1` with a `control` border and, on focus, the volt ring.

## Motion

- **One duration** (`motion` in tokens.ts; `duration-press`, `duration-appear`, `duration-large`): 150 ms ease-out on a hover, a press, a menu, a sheet, a toast. Nothing longer, but for the continuous motions below. A number of milliseconds in a component fails lint.
- **Layers** (`layers` in tokens.ts), by name, never a z-index number: `z-raised` (sticky rows and bars, the sidebar), `z-sticky` (a table's head over its pinned cells), `z-popover` (menus), `z-overlay` and `z-sheet` (a dialog's scrim and the dialog), `z-toast`, and `z-above` (a step-up over a sheet).
- **One easing curve**, ease-out: `cubic-bezier(0, 0, 0.2, 1)`.
- **What moves:** opacity and small translations (at most 8 px) of things appearing or leaving; colour on hover and press; the skeleton's shimmer.
- **A card that is a link goes up one step of the ladder on hover** (`tile-motion`). Nothing lifts or scales.
- **The continuous motions** are the two below, both 2 seconds, and stop under reduced motion:
  - **the running pulse:** while a session is `RUNNING`, the dot beside its state pulses its opacity;
  - **the finding ring:** while we find a GPU, the large ring breathes (a little smaller and fainter, then back).
  - No other decorative motion.
- **What never moves:** money figures (they change in place, never count up), page layout, and anything the person is reading or about to tap.
- **`prefers-reduced-motion: reduce` removes all non-essential motion:** no translations, no shimmer, no pulse. Changes are instant; only focus and state colours remain.
- **No layout shift, ever** (cumulative layout shift 0 on every screen):
  - skeletons have the same size as the content that replaces them;
  - images and icons have fixed dimensions;
  - a font swapping in moves nothing, because its fallback is scaled to its metrics (see Typography);
  - **banners push the content below them down; they never cover it** (the product owner, Oct 8): they're rare and important, and covering would hide what you're reading.

## States

Every view that shows data designs four states before it's built:

| State | What it shows |
| --- | --- |
| **Loading** | A skeleton with the shape of the content: same cards, same heights, no text. No spinner. |
| **Empty** | Why it's empty and what to do: "No sessions yet. Book a GPU to run your first one." With the one next step as the primary action. |
| **Error** | What happened, in a sentence, plus a Retry button: "We couldn't load your sessions. Check your connection and try again." Never a raw status code. |
| **Partial or stale** | The data it has, plus one line at the top: "Couldn't refresh. Showing figures from 12:04. Retrying…" Stale money is never presented as current, and nothing says "Live" while it's stale. |

## Times

- **Relative where it's about how long ago:** "started 18 min ago", "2 h ago", "yesterday", then the date after a week. The exact time is in the `<time>` element's `datetime` and its title.
- **Elapsed time ticks** every second while a session runs, as a clock ("18:42", "1:02:05"), and stops when it ends. Durations of ended sessions keep seconds only under ten minutes ("6 min 10 s", "41 min").
- **One clock for the app** (`src/lib/clock.ts`): every relative time moves on together every 30 seconds, and every elapsed time every second, from a single timer.

## The phone action bar

On a phone, a screen whose next step is used while watching something (Stop session while it runs, Cancel request while finding, Download outputs or Try again once it ends) puts that action at the foot of the screen, in a bar on the page's colour above the tab bar's place. The bar is stuck to the bottom of the screen but takes its own place at the end of the page, so it covers nothing and nothing moves when it appears. It holds that one action, a second one at most ("Run again"), and a line under it ("Saves a checkpoint first. Nothing more is charged."). On a laptop the same actions sit in the grid.

## Home

The screens below keep their arrangement under ADR 0060; where one still names an old colour or a night, ember, ion or lime card, read a plain card (`surface-1`) or a raised one (`surface-2`): colour is never a card's fill now, and headings are Inter in the scale above, never uppercase.


Home follows the approved `AppHome` reference. On a phone it is a two-column grid of these cards:

| Tile | Size | Colour | What it holds |
| --- | --- | --- | --- |
| Running now | 2 × 2 | raised | "Running now", what it's doing and on which GPU, the elapsed time at 54 px and the cost in volt; opens the session. One for each live session. |
| The first service | 1 × 2 | raised | The service's icon, its name and what you bring; opens Book at it. |
| Credit | 1 × 1 | panel | "Credit" and the available amount; opens Credit. |
| Book again | 1 × 1 | panel | "Book again" and the last setup ("Fine-tune · 41 min"); opens Book filled in from it. Not there for a new renter. |
| Another service | 2 × 1 | panel | The service's name, what you bring, its icon; opens Book at it. |
| Earn with your GPU | 2 × 2 | volt | "For gamers", "Your GPU sits idle. Let it earn.", "Rent out your gaming laptop →"; opens Hosting. |

With nothing running, the first card is "What do you want to run?" with the two services. On a laptop the same cards sit on four columns; the services come from the catalog, never typed here, so a third service is a third card.

## Book

Book is the services first ("What do you want to run?"), then each service's own screen, made from its template in the catalog, never written by hand (ADR 0022). Its questions are stacked cards:
- **Top:** "← All services", the service's name in big type, and a line on what you bring and what you get.
- **One panel for each question:** each input ("1. Your model"), with Upload (a folder or a file, starting as soon as it's chosen and carrying on where it stopped if the same files are chosen again), From My files and, where the template allows, Link; then each plain question: a preset as **selectable cards**, the selected one volt with a check, with their typical time and "≈ ₹" at today's prices, labelled as typical; a basic setting; or the GPU memory the job needs.
- **Budget:** the template's chips ("Stop at ₹100"), as pills, and "Other amount".
- **Advanced:** one row, closed, naming what's inside, with every default set; it opens by itself when something in it needs fixing.
- **Your request:** the **raised card**: each answer, what you get, the GPU card with how many fit now and the price, the hold, and "Request a GPU" as a volt button inside it. On a laptop it sits beside the questions; on a phone it is the last card, and the button is held at the foot like the action bar.
- **The GPU card** names the memory the job needs, from the model's size when the template says so ("Your 14.2 GB model needs a 12 GB GPU"). A model too big for any GPU here says how much it needs and that the biggest have 24 GB, and the button stays off; it never says "No GPU free", since waiting won't help.

My files is a list of the renter's uploads (name, size, files, when last used), reached from the account menu and from each input's From My files; Delete asks once more in its row.

## The session screen

The session screen follows the state, as cards:

| State | What it shows | Its action |
| --- | --- | --- |
| Finding (`REQUESTED`, `MATCHED`) | One big raised card with the ring, breathing; "Finding your GPU", "Usually under a minute"; what's done ("train.jsonl uploaded"); "Nothing is charged until it runs"; and a small panel for what was asked for | Cancel request |
| Found (`PROVISIONING`) | "GPU found", the GPU and its memory, "Starting…" | Cancel request |
| Running (and moving host, saving) | The raised card holds the time and cost (54 px), the state and "of about 45 min"; then cards for the GPU (use and temperature), the checkpoints, the log (a panel with a monospace body), and Stop. On a laptop they sit in a grid beside the log | Stop session, asking first |
| Done (`COMPLETED`) | The charge (raised card), the outputs (volt, with Download), the rating, "Report a problem" and Run again | Download outputs, then Run again |
| Ended otherwise | Why, in plain words, and what to do; the charge, often "Nothing was charged" | Try again, filled from it |

All sessions is a list of panels, newest first, with a volt dot on the live one.

Raising the cap and sharing a live link are not built yet.

## Credit

The balance as a big raised card (Available, with On hold and Total beside it), and the history as panels grouped by day.

## Hosting

The owner's screens (ADR 0023), for someone who wants to know "is my machine earning, and is it safe?" from their phone. They follow the same pattern: one dark summary card, then panels.
- **The Hosting tab:** "Earned this week" as the raised card, big, with the total beside "payouts start with the paid pilot" and the last 7 days as bars in volt (today in full, a day with nothing a thin line); their machines, each an ember or panel with its state in words; "Add a machine". With none, what hosting is and that one step.
- **A machine's states,** one at a time, in this order: running a session (the raised card, its dot pulsing, "earning ₹2.73 so far"), paused (until when), update needed, status unknown ("Status unknown, checking…" while we can't read whether its agent is connected: never "offline", which would tell a host their laptop dropped), offline, checking (not yet activated), suspended, idle. A paused machine finishes a session it's running, and says so.
- **One machine:** a raised card with its state, name and GPU; then cards for its temperature and use against the limit its owner set; "Right now", the session running and what it has earned so far; the privacy line ("Renters never see who you are, and their work never touches your files."); rows to its price and limits, the sessions it ran, and renaming or removing it; and at the foot, "Pause hosting" (a choice of how long, opened in place) or "Resume".
- **Money is the server's:** "earned so far" is read again every 5 seconds while a session runs and changes in place; it's never counted up on the client (principle 4). The elapsed time ticks.
- **Price and limits:** the price an hour with − and + in ₹0.60 steps inside a panel, what the owner keeps of it and the 20% fee, worked out from whole paise; the range others charge (or the usual range for a GPU like theirs); the temperature to pause above (60 to 95 °C); the disk to keep free (10 to 500 GB); and a fixed line that it's one session at a time.
- **Removing** asks first in a sheet that names the machine, never a browser dialog; the one other dialog the app has.
- **Add a machine** is "Earn with this laptop" (ADR 0032): what a laptop needs, the download (a disabled "Windows app: coming soon" until the installer exists), and the one command that links it. No code is shown or typed.
- **The link page** (`/link/<id>/`, features/hosting/LinkApproval.tsx): a signed-in screen in a centred card. "Link this laptop to your account?" over the laptop as it described itself (name, GPU, system, agent version, minutes left), a name field, the price with "You'll earn ₹x an hour (renters pay ₹y)" and Change (the machine's own price form), "Approve and start earning" and "This isn't my laptop". It is drawn whole while loading. Expired, denied and linked are states of the same card; linked says new laptops are checked before they go live and opens the machine.
- **Words:** here, and only here, a host's owner calls their own host a **machine**. Renters never see "host" or "machine"; owners never see who rented one.

## Account, sign-in and the rest

Sign-in, the invite step, Account, API keys, the notifications list and the CLI approval are panels on the page colour, with one primary action each. The admin console (ADR 0027) stays dense: it takes the fonts, the colours and the radii (and both themes), with smaller type and tighter tables, and a 240 px sidebar with all its sections in eight groups (The admin sidebar, above), items 36 px tall from 768 px. Its headlines are Archivo uppercase at 26 px, its tables sit in a panel with tracked uppercase headings and 8 px of padding in a row, and its pills are `raised`, `ok` or `danger-soft`.

## Laptop layout

From 1200 px every signed-in screen is laid out for the screen, not as the phone's cards in a column. The approved references are `AppHome` and `Session` (each in both themes); screens that are not drawn follow the same rules. Judged at 1440 × 900.

**The system**
- **Breakpoints:** phone under 768 px; tablet 768 to 1199 px (the laptop's header and tabs, with 6 columns and stacked rails); laptop from 1200 px (`xl:`, `useLaptop()`); a wide step at 1680 px (`2xl:`).
- **Frame and grid** (`components/shell/Layout.tsx`): the content runs the full width of the window, no maximum and no centred column; side padding 14 px on a phone, 32 from 768 px and 40 from 1200 px; 12 columns with a 16 px gap (20 px from 1680 px), 6 columns on a tablet. A screen that fills the window (Hosting) is 100% of the height less 32 px above and 48 px below (`FIT_FRAME`, `fit-screen`).
- **The shell** is the left sidebar (see Navigation): the wordmark, the site's destinations, and the account at the bottom, with the credit in a panel on Home and Credit. Appearance (Match my device, Light, Dark) is under Account, in Profile. A phone has the bottom tab bar instead.
- **Rails** (`RailColumns`): a left column of 400 px (320 px for Account) of summary cards and the rest beside it, stacked on a tablet. The live session, Hosting's pages, Credit and Account use it; the left column's first card is dark.
- **Tiles hold their space:** no more than about 35% of a card is empty at 1440 × 900. A card is as tall as its content, or two cards share a row's height; one with nothing to say isn't drawn (no free GPUs, a log with under two loss values, a host with no sessions). Figures are tabular and come only from the API; money is `formatPaise`.
- **Rules that stay:** one dark primary button on a screen; touch targets of 44 px or more; no layout shift, so a skeleton is exactly its card's height; the first screen's requests are only what it needs to paint, and the cards that need more ask after the paint (`useAfterPaint`).

**Home** (approved `AppHome`), a column of rows under the page padding (32 px above, 48 px below), 24 px apart:

| Row | Holds |
| --- | --- |
| Headline | "Or drop a folder anywhere on this page" over "What do you want to run?", and "Book a GPU" (`primary`, large) at the end. Dropping a folder anywhere opens the matching service with it |
| Services | One accent card for each service of the catalog, in a row that wraps below 280 px each: the first raised, the others plain. Each has its typical time as a tracked label, its name in the title face and what you bring |
| Running and credit | A raised card for each live session ("Running now", the GPU, what it is doing, the time and the cost in volt; it opens the session), and the credit card (available at 52 px, what is on hold and the last charge, "Add credit"); each at least 380 px wide |
| Recent sessions | A panel with a table of the last four: what, GPU, ran, cost and "Run again" or "Open"; "All sessions" in the link colour |
| Last | "GPUs free right now" (each GPU memory class with whether one is free and the live price range, drawn after the first paint; not there when it has nothing), "Book again" (a card; not there for a new renter) and the volt "For gamers" panel |

**The live session** (approved `Session`): a night band across the top with 32 px corners (the state and the epoch in volt, the service's name, when it started and the live status; on the right the time, the cost so far in volt with what it stops at, and "Stop session" in `secondary`). Under it two panels side by side, 580 px at least so nothing under them moves as the log is read: the GPU's panel (its model and memory, and four small cards: busy, temperature, checkpoints, step) with the training loss under it as 20 bars in ion-deep and the latest in the running colour (drawn only when the log carries two or more loss values); and the log, a panel with a monospace body, Copy and Download. Under them the four steps of the run (uploaded, GPU found, running, outputs), each a 6 px bar, a title and a line. Finding is the raised card with the ring and the request beside it; Done is the charge, the outputs (volt, with Download), the rating and "Run again". Nothing is drawn that the log or the session doesn't carry.

**Hosting**, two equal rows: the raised card of the week (the amount, the total and hours rented, the seven days as bars) over 8 columns and the "Add a machine" card (what a machine needs, one-time code, about 5 minutes) over 4; then each machine as a 4-column card (state, temperature and use, name, GPU and price, "Earning now" or Pause or Resume) and "Latest earnings" with the last entries and All. With more than two machines they are a grid of three a row, and the earnings sit under them. Machine, Price, Sessions it ran, Earnings and Add have the 400 px rail with a dark summary card (the machine's state, name and rating; or the screen's title and what it is) and panels beside it.

**Account**: the 320 px rail holds a raised profile card (an avatar in volt, the name and email), the sections (Profile and notifications, API keys, My files, Reports and the rest) and Sign out. Beside it Profile (name with Edit, email, how they sign in) with Appearance (Match my device, Light or Dark) under it, and Notifications, a table of a kind and two switches (Email, In the app), the kinds about money and safety locked on with their reason. API keys, My files and Reports are cards of rows in the same frame.

**The other screens**
- **Book:** the full width with 7 columns of questions and, sticky at the top, the dark "Your request" in 5.
- **Sessions:** a full-width table (service, started, time, state, charge) in one panel, a row a link; Active and Ended pills; paging as before.
- **Credit:** the 400 px rail with the dark balance card (available, on hold, total) and the history beside it, grouped by day.
- **Sign-in:** the sign-in card in 5 columns and, beside it, a collage in 7 of what the product is ("Rent a GPU by the minute", "Fine-tune a model", "Earn with your GPU", "A locked box"), saying only what the website says. A small "← idleo" leads back to the website; "Get started" from the website arrives as "Create your account".
- **Notifications, the CLI approval, the invite step:** one panel, 7 columns, centred, never a phone column.
- **The admin console** takes the same tokens, denser: smaller type, tighter tables, and the sidebar with all its sections.

**The website** (ADR 0029) has its own frame, 1280 px wide and centred (see The public pages).

## Components

One component for each pattern, on every site (ADR 0059). A screen that builds its own fails lint (`idleo/one-component`); every one is in the component gallery (`/_gallery` on a development server), in every variant and state. The website is its own build and keeps its own.

| Pattern | Component | Where |
| --- | --- | --- |
| Page header: breadcrumbs, the page's one h1 (which names the tab), one line on what it's for, the primary action top right, then filters | `PageHeader` (`dense` in the console), `PageTitle` where a panel holds the title, `Breadcrumbs` | components/ui/Headers.tsx |
| Section header: an h2 (or h3), a line on it, its action | `SectionHeader` | components/ui/Headers.tsx |
| Table: caption, tracked headings, numbers right-aligned in tabular figures | `DataTable`, `DataRow`, `DataCell numeric`; the console's dense table (columns, pins, sorting, saved views, bulk, windowing) on top of it | components/ui/DataTable.tsx, features/admin/Table.tsx |
| Filters | `FilterBar` (a submitted search, or choices that apply at once) | components/ui/FilterBar.tsx |
| Loading, empty, error, stale | skeletons; `EmptyState` (what would be here, and the next step), `ErrorState` (what happened, Try again), `StaleNotice` | components/ui/Skeleton.tsx, States.tsx |
| Form field: label, help, the problem under it | `Field`, `TextArea`, `Select`, `Switch`, `RadioGroup` | components/ui/Field.tsx, Inputs.tsx |
| The action button and its pending state | `Button busy`: disabled at once, so it can't be pressed twice; its spinner only after 300 ms | components/ui/Button.tsx |
| Toast, with Undo | `toast({ message, undo })`, one `Toaster` in each frame | components/ui/Toast.tsx |
| Confirm and sheet | `ConfirmDialog` (what will happen, the button that does it, the way out), `Sheet` | components/ui/Sheet.tsx |
| State chip | `StatusChip`, from the one map in lib/states.ts; `Tag` for a label that isn't a state | components/ui/StatusChip.tsx |
| Key-value list, figures | `KeyValueList` (`columns` or `split`), `StatGrid` and `StatTile` | components/ui/KeyValues.tsx |
| Money, times, counts | `formatPaise`, lib/time.ts, `count()` and `percent()` | lib/money.ts, lib/time.ts, lib/format.ts |

## States and chips

Every state on every site comes from one map (`src/lib/states.ts`): each kind of thing (a session, a laptop, a payout, an appeal…), each code's words and its tone. `StatusChip` draws the tone as a colour and a mark: live is a pulsing dot, success a tick, warning a warning sign, danger a cross, neutral a ring, so a state never rests on colour alone. A screen may say more of the same state ("Paused until 6 pm"), never choose its own colour. The console's words are more exact where the renter's join two states (`exact`).

## Feedback

- Every action answers within 100 ms: the pressed button is disabled at once and shows it's working; its spinner appears only after 300 ms, so a quick answer never flashes it.
- Success is a short toast, with Undo where the action allows it. A problem is never a toast: it's said in plain words where it happened, with what to do next.
- A loading list or panel is a skeleton of the content's own size; nothing jumps when data arrives.

## Formats

- Money: `formatPaise`, Indian grouping (₹1,23,456.00). Numbers in tables are right-aligned in tabular figures.
- Times: India's time; relative ("18 min ago") with the exact time on hover (`RelativeTime`).
- Counts: `count(n, "day")` gives "1 day", "2 days", grouped the Indian way; percentages `percent(n)`, a whole number with no space.

## The admin sidebar

Eight groups, in the order an admin's day runs, each showing only the sections this admin may open; the palette and pinned items stay.

| Group | Sections |
| --- | --- |
| Today | Control room, Inbox, Approvals |
| People and hosts | Users, Hosts, Fleet, Invites |
| Sessions | Sessions, Review queue, Images |
| Trust and safety | Reports, Chargebacks, Abuse, Appeals, Tickets |
| Money | Money, Payments, Refunds, Invoices, Payouts, Verification, Clawbacks |
| Books | Ledger, Adjustments, Months, Settlements, Money reports, Tax |
| Platform | Flags, Notices, Audit log |
| Settings | Your access |

## Verbs

One verb for each action, on every site, in buttons, menus and confirms. A button says what it does ("Suspend user", never "Submit" or "OK").

| Say | For | Never |
| --- | --- | --- |
| **Delete** | Erasing something for good: a file, an account | Remove, Erase (but under a legal order) |
| **Remove** | Taking something off a list it can come back to: a laptop, an image, a tag | Delete, Unlink |
| **Revoke** | Ending a key, a code or a sign-in | Delete, Disable |
| **Suspend** / **Lift** | Stopping an account or host until it's lifted | Block, Disable, Unsuspend |
| **Ban** | Suspending for good | Block |
| **Stop** | Ending a session that runs | End, Kill, Terminate |
| **Cancel** | Withdrawing a request that hasn't started | Abort, Stop |
| **Pause** / **Resume** | Hosting, payouts, a kill switch | Disable, Turn off |
| **Approve** / **Turn down** | A request for approval | Accept, Reject, Deny |
| **Refund**, **Waive**, **Reverse** | Giving money back; not charging it; undoing a ledger entry | Return, Forgive, Undo |
| **Add** | Something new in a list of the person's own: a laptop, credit | Create, New |
| **Create** | A key, a code or a view, made and named | Make, Generate |
| **Save** / **Send** | Keeping a change to settings / a message to a person | Submit, Apply, Post |
| **Try again** | Retrying what failed | Retry, Reload |

## Voice

Short, plain sentences. Second person ("your credit"), active voice, no exclamation marks. Copy follows the reference screens: "What do you want to run?", "Request a GPU", "Finding your GPU", "GPU found", "Best free GPU for this job".

**The core words, and never a synonym for them:**

| Word | Means | Never say |
| --- | --- | --- |
| **service** | Something you can run ("Fine-tune a model"): a card on Home, a choice in Book | product, app, tool |
| **template** | One way to run a service: the image and its settings | image, recipe, preset, environment |
| **session** | One booked run of a template on a host's GPU | job, run, instance, rental, task |
| **credit** | Money in the renter's account, in rupees | balance, funds, wallet, money, coins |
| **host** | A person's machine that runs sessions | provider, node, server, worker, machine (in the UI) |
| **output** | A file a session produced, to download | result, artifact, download, file (as the name of the thing) |

A service is something you can run; a template is one way to run it.

The parts of credit have fixed labels too: **Available** (what you can spend now), **On hold** (set aside for sessions that are running or about to), and **Total** (the two together).

## Brand

The product name isn't decided. **IdleO** is the working name.

- The name, the wordmark and the favicon live in one place, `web/src/brand/`, so the final brand is one small change.
- **The wordmark is the name in lowercase** ("idleo"), drawn as outlines: one `Wordmark` component, in the app's sidebar, sign-in, the admin console and the public pages; it comes from `brand.name` and has no image to load. **The ring** (a ring with a small gap at the top, like a power button at rest) is the favicon and the large, breathing ring of "Finding your GPU".
- No stock logos or icons stand in for the brand.

## Icons

One set only: **Lucide**, imported one icon at a time. 20 to 22 px in controls and rows, 16 px inline with text, 30 px on a service's card, 1.6 to 1.75 px stroke (2 px for chevrons), coloured with the text they sit beside. A service names its icon by a key from a fixed list (`layers`, `code`, `table`, `cube`, `notebook`, `plug`, `gamepad`, `flask`, `pulse`); `src/lib/service-icons.ts` maps each to its Lucide icon. An icon-only button always has a visible tooltip or an accessible label.

## The public pages

The signed-out pages (ADR 0029) follow everything above: the same tokens, type and panels, volt as the one primary  and one primary action on a page, except the docs, which are a calm reading layout. They differ in three ways: they are their own build at their own address (the website, with the app at another), they are static HTML with no app code, and their budgets are stricter (on Lighthouse's mobile profile, the median of three runs: LCP 1.5 s, TBT 100 ms, CLS 0, accessibility 100, SEO 100; 80 KB for a first load, which does not count the fonts). A live figure has its place on the page at its final size, so loading, loaded, empty, error and stale never move anything. A number is live (from the API) or marked "typical"; nothing is invented.

**The home page** follows the approved reference section by section: the header (wordmark, the pages, "Log in" as a ghost button, "Get started" as the primary); the hero (a kicker chip, the headline in uppercase Archivo, the opening paragraph, the two buttons and the pilot line) beside a preview of a live session in a night card with the violet glow, labelled as an example; the marquee band in ion-deep; three step cards in ember, ion and volt; what you can run, from the catalog, as accent cards; "For builders" (a panel) beside "For gamers" (volt); the black "Sealed, on both sides." band; "Pick a GPU" (a table of the GPU memory classes, their live price after the first paint and "typical" before it); the questions; a closing ion-deep band; and the footer with its columns, the Appearance switch, and the large wordmark. The header's pages, "Log in" and "Get started" and the footer's Terms, Privacy and Contact are on every page. Pricing, GPUs, Earn, Help, Docs, Contact, Terms and Privacy use the same language: an uppercase Archivo headline, panels with 28 px corners, the same buttons.

