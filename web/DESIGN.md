# The IdleO design language

The standard every screen is checked against, now and later. Numbers, not adjectives: if a screen breaks a rule here, the screen is wrong, or this file changes first, with Nikki's say.

The look was chosen on Oct 16, 2026: **Bento** (ADR 0031). A home made of tiles of different sizes, each one a thing you can do or check. It's calm and light, with soft colour tiles, very rounded corners and heavy, friendly type. It replaced the ride-app look of Oct 8 (ADR 0021's look; its behaviour, requesting a GPU like a ride, stays). The reference is `docs/design/bento/home-phone.html`, the approved Home on a phone.

The tokens below live in code in one place, `web/src/design/tokens.ts` (the brand's one colour in `web/src/brand/brand.ts`, so the brand stays one change), and Tailwind's theme is built from them. Nothing outside that file names a colour, a size or a duration; a lint rule refuses raw colours and arbitrary values.

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

1. **One question per screen, one obvious next step.** A screen asks one thing in big type ("What do you want to run?") and has at most one primary button (the dark tile's colour, `primary`): its next step. A screen that only shows information may have none. Two primary buttons on one screen is a bug. A lime button inside the dark "Your request" tile is that screen's one primary.
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
   - No modal where a page works. The dialogs are the one that confirms stopping a session and, since Oct 13 (ADR 0028), the sheet that takes a report of a problem: a choice of six reasons and what happened, which Nikki asked to be a short sheet, not a page. What can wait (a service's advanced settings) is one row that opens in place, with its defaults set; deleting from My files asks once more in its own row.
   - No jargon. Say "GPU memory", not "VRAM", unless it's next to a number ("24 GB GPU memory").
   - Error messages say what happened and what to do next, in that order: "Your sign-in link has expired. Send a new one."
6. **Speed is a feature.** Navigation feels instant. Every interaction shows a response within 100 ms, even if that's only a pressed state. Data a screen has already seen is shown at once from the cache, then refreshed.

## Typography

- **One family: Plus Jakarta Sans** (Tokotype, SIL Open Font License 1.1), self-hosted. No request ever goes to a font service.
  - **Why Plus Jakarta Sans:** the reference's own face. It is friendly and heavy at 800, has true tabular figures (`tnum`), and includes the rupee sign (₹, U+20B9).
  - **One file:** a subset of the variable font, cut by `web/scripts/subset-font.sh` from a pinned commit of the upstream repository, holds Basic Latin, Latin-1, the punctuation we use, the arrows, the minus sign, `≈` (U+2248) and ₹. The weight axis is kept from 400 to 800, which makes it 17.8 KB as WOFF2 (the Inter file it replaced was 25.5 KB).
  - **It never swaps.** It's preloaded with `font-display: optional`. If it hasn't arrived by the first paint (a slow first visit), that page keeps the fallback, Arial scaled to Plus Jakarta Sans's metrics, because a swap would move text sideways. Every later page has it from the cache.
- **Logs are the one exception:** a session's log panel uses the system's monospace fonts (`font-mono`), so nothing is downloaded for it. Nothing else is set in monospace.
- **Numbers** (money, durations, counts, times) use tabular figures: the `tabular` class sets `font-variant-numeric: tabular-nums`.
- **Weights:** 400 for reading, 500 for secondary labels, 600 for titles of rows and options and for buttons, 700 for small emphasis, 800 for headings and big figures.
- **The scale.** Sizes of 20 and up are weight 800 with tight letter-spacing built into their tokens: −0.02em up to 26 px, −0.03em at 28 and 40, −0.04em at 54 and 72.

| Token | Size / line height | Used for |
| --- | --- | --- |
| `text-caption` | 13 / 18 px | A tile's label, captions, timestamps, the line under a field |
| `text-small` | 14 / 20 px | Secondary text, chips, buttons, navigation |
| `text-body` | 16 / 24 px | Reading text, form inputs (16 px stops iOS zooming on focus) |
| `text-option` | 17 / 24 px | The title of an option or a row; large buttons |
| `text-section` | 20 / 26 px | A tile's title ("Fine-tune", "Your data") |
| `text-figure` | 24 / 28 px | A figure on a small tile (the credit) |
| `text-headline` | 26 / 28 px | A tile's headline ("Your GPU sits idle. Let it earn.") |
| `text-title` | 28 / 34 px | Page titles on a phone; big figures beside the timer |
| `text-question` | 40 / 44 px | The laptop's page question ("What do you want to run?") |
| `text-timer` | 54 / 54 px | The running timer on a phone, and the big credit |
| `text-timer-lg` | 60 / 60 px | The running timer in the live session's tile on a laptop |
| `text-timer-xl` | 72 / 72 px | The running timer on a laptop |

- **Line length:** reading text is at most 68 characters wide (`max-w-prose`, set to 68ch). Tables and lists may be wider.
- **Case:** sentence case everywhere: titles, buttons, labels, navigation. No all-caps text.

## Colour

A light grey page with white tiles on it, a dark tile for what matters most right now, and three soft colour tiles. Danger and warning keep their meaning.

- **The theme** follows the system preference (`prefers-color-scheme`) until the person chooses Light or Dark from the user menu. Their choice is remembered on this device only.
- **Surfaces are tiles, not bordered boxes.** The page is `ground`; a tile is `fill`, white (a step lighter than the page in dark, too). A field inside a tile is filled with `ground`, so it looks pressed in.
- **The dark tile** (`dark`, with `dark-fg` and `dark-muted`) is the one thing to look at: what's running, the charge, the request. Lime (`live`) is its accent: the running dot, the cost, the button inside it.
- **The colour tiles** keep their colours and their dark text in both themes, so they stay readable: **lime** is the main action (Fine-tune, outputs, the selected option); **lavender** is history (Book again); **apricot** is hosting and earning. Each has its own text colour (`lime-fg`, `lavender-fg`, `apricot-fg`) for its small lines, and `on-colour` for titles and figures.
- **Colour is never the only signal.** A state pill has a word in it; an error has an icon and a sentence; the selected option has a check.

| Token | Light | Dark | Used for |
| --- | --- | --- | --- |
| `ground` | `#EFEFF2` | `#0E0E11` | The page behind the tiles; a field inside a tile |
| `fill` | `#FFFFFF` | `#1A1A1F` | A tile, a chip, the tab bar |
| `fill-selected` | `#F6F6F9` | `#222228` | A tile or row when hovered or pressed |
| `divider` | `#E4E4EA` | `#2A2A31` | 1 px lines between rows inside a tile; bar tracks |
| `control` | `#767683` | `#8A8A96` | Outlines of switches and radio buttons (must meet 3:1) |
| `text` | `#16161A` | `#F2F2F5` | Primary text |
| `text-muted` | `#5C5C66` | `#A3A3AE` | Secondary text; still meets 4.5:1 on the page and on a tile |
| `primary` | `#16161A` | `#F2F2F5` | Primary buttons |
| `primary-hover` | `#2E2E36` | `#DADAE0` | Primary buttons when hovered or pressed |
| `primary-fg` | `#FFFFFF` | `#16161A` | Text on `primary` |
| `dark` | `#16161A` | `#26262D` | The dark tile |
| `dark-fg` | `#FFFFFF` | `#FFFFFF` | Text on the dark tile |
| `dark-muted` | `#B8B8C4` | `#B8B8C4` | Secondary text on the dark tile |
| `live` | `#C9F27B` | `#C9F27B` | The accent on the dark tile: the running dot, the cost, the button |
| `lime` | `#C9F27B` | `#C9F27B` | The main action's tile; the selected option |
| `lime-fg` | `#2E3A12` | `#2E3A12` | Small text on a lime tile |
| `lavender` | `#D8D3FF` | `#D8D3FF` | History: Book again |
| `lavender-fg` | `#3B347A` | `#3B347A` | Small text on a lavender tile |
| `apricot` | `#FFB86B` | `#FFB86B` | Hosting and earning |
| `apricot-fg` | `#5A3410` | `#5A3410` | Small text on an apricot tile |
| `on-colour` | `#16161A` | `#16161A` | Titles and figures on a colour tile or on `live` |
| `running` | `#0B6B37` | `#4CC38A` | Running, done steps, a saved checkpoint |
| `running-soft` | `#E3F4EA` | `#10291D` | The running pill's background |
| `warning` | `#8A5300` | `#F0B44C` | Low credit, stale data |
| `warning-soft` | `#FFF3DD` | `#2E220B` | Warning banner background |
| `danger` | `#C0262D` | `#FF6B6B` | Failed, errors, destructive actions |
| `danger-soft` | `#FDECEC` | `#331414` | Error banner background |
| `danger-fg` | `#FFFFFF` | `#16161A` | Text on `danger` |
| `danger-on-dark` | `#FF8A8A` | `#FF8A8A` | An error line on the dark tile |
| `bone` | `#E3E3E9` | `#26262D` | A placeholder (skeleton), on the page or on a tile |
| `bone-shine` | `#F4F4F7` | `#30303A` | The skeleton's shimmer |
| `brand` | `#4B3FD1` | `#8F86FF` | The wordmark's ring, and nothing else |
| `focus` | `#4B3FD1` | `#A39CFF` | The 2 px focus ring, offset 2 px |

### Contrast, WCAG 2.2 AA

Every text pair meets 4.5:1 (1.4.3), and every control outline, focus ring and graphic meets 3:1 (1.4.11). `web/src/design/tokens.test.ts` computes each ratio from the tokens and fails if any is below its minimum, or if it differs from this table by more than 0.01.

| Foreground on background | Light | Dark | Minimum |
| --- | --- | --- | --- |
| `text` on `ground` | 15.72 | 17.25 | 4.5 |
| `text` on `fill` | 18.04 | 15.51 | 4.5 |
| `text` on `fill-selected` | 16.73 | 14.16 | 4.5 |
| `text-muted` on `ground` | 5.76 | 7.72 | 4.5 |
| `text-muted` on `fill` | 6.61 | 6.94 | 4.5 |
| `text-muted` on `fill-selected` | 6.13 | 6.33 | 4.5 |
| `primary-fg` on `primary` | 18.04 | 16.15 | 4.5 |
| `primary-fg` on `primary-hover` | 13.46 | 12.96 | 4.5 |
| `dark-fg` on `dark` | 18.04 | 15.03 | 4.5 |
| `dark-muted` on `dark` | 9.19 | 7.65 | 4.5 |
| `live` on `dark` | 14.15 | 11.79 | 4.5 |
| `on-colour` on `lime` | 14.15 | 14.15 | 4.5 |
| `lime-fg` on `lime` | 9.52 | 9.52 | 4.5 |
| `on-colour` on `lavender` | 12.62 | 12.62 | 4.5 |
| `lavender-fg` on `lavender` | 7.50 | 7.50 | 4.5 |
| `on-colour` on `apricot` | 10.59 | 10.59 | 4.5 |
| `apricot-fg` on `apricot` | 6.37 | 6.37 | 4.5 |
| `running` on `running-soft` | 5.80 | 6.99 | 4.5 |
| `running` on `fill` | 6.62 | 7.83 | 4.5 |
| `running` on `ground` | 5.77 | 8.70 | 4.5 |
| `warning` on `warning-soft` | 5.76 | 8.40 | 4.5 |
| `warning` on `fill` | 6.33 | 9.36 | 4.5 |
| `warning` on `ground` | 5.52 | 10.41 | 4.5 |
| `danger` on `danger-soft` | 5.17 | 6.05 | 4.5 |
| `danger` on `fill` | 5.90 | 6.25 | 4.5 |
| `danger` on `ground` | 5.15 | 6.95 | 4.5 |
| `danger-fg` on `danger` | 5.90 | 6.50 | 4.5 |
| `danger-on-dark` on `dark` | 7.95 | 6.62 | 4.5 |
| `control` on `ground` | 3.90 | 5.65 | 3 |
| `control` on `fill` | 4.48 | 5.08 | 3 |
| `focus` on `ground` | 6.24 | 8.04 | 3 |
| `focus` on `fill` | 7.17 | 7.23 | 3 |
| `brand` on `ground` | 6.24 | 6.44 | 3 |

## Shapes and space

- **Tiles, no borders.** A tile is `fill` (or a colour, or `dark`) with 26 px corners and 18 px of padding. Separation comes from the page's colour between tiles. A 1 px `divider` appears only between rows inside a tile. A very soft shadow appears only where a tile floats: the phone's tab bar, menus and a bottom sheet.
- **Corners:** 26 px for tiles and the top of a bottom sheet (`rounded-tile`); 22 px for the floating tab bar (`rounded-bar`); 16 px for inputs and for rows or options inside a tile (`rounded-control`); 14 px for icon squares; fully round (999 px) for buttons, chips, pills, avatars and navigation.
- **A tile inside a tile** (a field, a secondary button, an option, a row that opens) is pressed in with `ground`, the page's colour; the code marks a tile's inside with `data-tile` and does this once (the `tiled` variant and one nested-tile rule in `styles.css`), so no component needs to know where it sits.
- **The selected option** is a lime tile with a check (`on-colour` text); the ones not selected are white tiles.
- **The tile grid:** two columns on a phone with rows of 88 px (`auto-rows-tile`), so a small tile is one row and a tall one two; four columns on a laptop, with the same tiles. The gap is 10 px on a phone and 14 px on a laptop.
- **Page padding:** 14 px on a phone, 24 px or more on a laptop.
- **Spacing is a 4 px scale:** 0, 4, 8, 10, 12, 16, 20, 24, 28, 32, 36, 40, 44, 48, 56, 60, 64, 72, 88, 96 px, plus 2 px between a title and its line under it, 6 px for thin bars, and 14 px for the page's gutter and a tile's inner gaps. The half steps 18, 26 and 34 px exist as tile padding and as the type scale's line heights, for skeleton lines. 88 px is a row of the tile grid.
  - Between sections: 24 to 32 px; between a tile's title and its content: 12 px; between options: 8 to 10 px.
- **Heights:** controls at least 44 px (`touch`); fields 56 px; buttons 56 px (`large`: the one at the foot of a flow, 60 px on a laptop).
- **Named sizes:** the finding ring 88 px on a 168 px grey disc (`finding-ring`, `finding`); the log panel at least 384 px (`panel`); Book's price beside its GPU card 112 px on a laptop (`price`), so it never moves as it arrives.
- **Shadows** only for things that float: menus and popovers (`shadow-float`), a bottom sheet over the page (`shadow-sheet`) and the tab bar (`shadow-bar`).
- **Touch targets are at least 44 × 44 px**, even where the visible control is smaller (the hit area is padded).
- **The layout:**

| Width | Layout | Margin |
| --- | --- | --- |
| Up to 767 px (phone) | One column of tiles on a two-column grid; Book and the session are full-screen flows with a back button | 14 px |
| 768 px and up (tablet, small laptop) | The same, wider | 24 px |
| 1280 px and up (laptop) | A four-column tile grid at most 1120 px wide; Book is its questions beside the "Your request" tile; a live session is its tiles in a grid beside the log | 24 px |

- **Navigation:**
  - **Phone:** a header (the wordmark at left, the bell and the avatar at right, 64 px tall) and the **floating tab bar**: a white bar 64 px tall, 14 px from the edges and from the bottom, 22 px corners, with a very soft shadow: Home, Book, Hosting, Credit; the current one in the ink colour at weight 600, the others muted. Book and the session screen are flows: on a phone they replace both bars with a back button.
  - **Laptop:** a 64 px top bar with the wordmark (a link to Home), the same four destinations as white pill tabs on the page colour (the current one `primary`), and at the right the bell and the avatar. The credit is a tile on Home and the Credit tab, not a pill in the bar.
  - On a phone, All sessions is a row under Book again on Home, since it has no tab.
  - A machine, its price and limits, its sessions and adding one are flows on a phone, with their own back button; the Hosting tab and its earnings keep the bars.

## Buttons

- **Primary:** `primary` (the dark tile's colour, and light in the dark theme), `primary-fg` text, 56 px tall, fully round. At most one on a screen.
- **Secondary:** a white tile (`fill`) with ink text, the same height and shape.
- **Inside the dark tile:** the lime (`live`) button with `on-colour` text.
- Smaller ones (44 px, `touch`) are for rows and sheets. Every button is at least 44 px tall.
- **Chips** are 44 px pills; the chosen one is `primary`.
- A pressed button that's waiting shows a small spinner inside it.

## Motion

- **Durations:** 120 ms for presses, hovers and toggles; 180 ms for small things appearing (menus, popovers, toasts); 240 ms for large things (sheets, dialogs, page content fading in). Nothing longer, but for the two continuous motions below.
- **One easing family**, a decelerating curve:
  - entering: `cubic-bezier(0.2, 0, 0, 1)`;
  - leaving: `cubic-bezier(0.4, 0, 1, 1)`, at two-thirds of the entering duration.
- **What moves:** opacity and small translations (at most 8 px) of things appearing or leaving; colour on hover and press; the skeleton's shimmer.
- **Tiles that are links lift 2 px on hover (a laptop) and press in to 98% on tap (a phone), at 120 ms** (`tile-motion`). Nothing else moves.
- **The two continuous motions,** both 2 seconds and both off under reduced motion:
  - **the running pulse:** while a session is `RUNNING`, the dot beside its state pulses its opacity;
  - **the finding ring:** while we find a GPU, the large ring breathes (a little smaller and fainter, then back).
- **What never moves:** money figures (they change in place, never count up), page layout, and anything the person is reading or about to tap.
- **`prefers-reduced-motion: reduce` removes all non-essential motion:** no translations, no lift or press, no shimmer, no pulse. Changes are instant; only focus and state colours remain.
- **No layout shift, ever** (cumulative layout shift 0 on every screen):
  - skeletons have the same size as the content that replaces them;
  - images and icons have fixed dimensions;
  - the font never swaps (see Typography);
  - **banners push the content below them down; they never cover it** (Nikki, Oct 8): they're rare and important, and covering would hide what you're reading.

## States

Every view that shows data designs four states before it's built:

| State | What it shows |
| --- | --- |
| **Loading** | A skeleton with the shape of the content: same tiles, same heights, no text. No spinner. |
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

Exactly the reference (`docs/design/bento/home-phone.html`), as tiles on the two-column grid:

| Tile | Size | Colour | What it holds |
| --- | --- | --- | --- |
| Running now | 2 × 2 | dark | "Running now", what it's doing and on which GPU, the elapsed time at 54 px and the cost in lime; opens the session. One for each live session. |
| Fine-tune | 1 × 2 | lime | The service's icon, its name and "Your model, your data"; opens Book at it. |
| Credit | 1 × 1 | white | "Credit" and the available amount; opens Credit. |
| Book again | 1 × 1 | lavender | "Book again" and the last setup ("Fine-tune · 41 min"); opens Book filled in from it. Not there for a new renter. |
| Run your code | 2 × 1 | white | The service's name, "Your folder, our GPU", its icon; opens Book at it. |
| Earn with your GPU | 2 × 2 | apricot | "For gamers", "Your GPU sits idle. Let it earn.", "Rent out your gaming laptop →"; opens Hosting. |

With nothing running, the first tile is "What do you want to run?" with the two services. On a laptop the same tiles sit on four columns; the services come from the catalog, never typed here, so a third service is a third tile.

## Book

Book is the services first ("What do you want to run?"), then each service's own screen, made from its template in the catalog, never written by hand (ADR 0022). Its questions are stacked tiles:
- **Top:** "← All services", the service's name in big type, and a line on what you bring and what you get.
- **One white tile for each question:** each input ("1. Your model"), with Upload (a folder or a file, starting as soon as it's chosen and carrying on where it stopped if the same files are chosen again), From My files and, where the template allows, Link; then each plain question: a preset as **selectable tiles**, the selected one lime with a check, with their typical time and "≈ ₹" at today's prices, labelled as typical; a basic setting; or the GPU memory the job needs.
- **Budget:** the template's chips ("Stop at ₹100"), as pills, and "Other amount".
- **Advanced:** one row, closed, naming what's inside, with every default set; it opens by itself when something in it needs fixing.
- **Your request:** the **dark tile**: each answer, what you get, the GPU card with how many fit now and the price, the hold, and "Request a GPU" as a lime button inside it. On a laptop it sits beside the questions; on a phone it is the last tile, and the button is held at the foot like the action bar.
- **The GPU card** names the memory the job needs, from the model's size when the template says so ("Your 14.2 GB model needs a 12 GB GPU"). A model too big for any GPU here says how much it needs and that the biggest have 24 GB, and the button stays off; it never says "No GPU free", since waiting won't help.

My files is a list of the renter's uploads (name, size, files, when last used), reached from the account menu and from each input's From My files; Delete asks once more in its row.

## The session screen

The session screen follows the state, as tiles:

| State | What it shows | Its action |
| --- | --- | --- |
| Finding (`REQUESTED`, `MATCHED`) | One big dark tile with the ring, breathing; "Finding your GPU", "Usually under a minute"; what's done ("train.jsonl uploaded"); "Nothing is charged until it runs"; and a small white tile for what was asked for | Cancel request |
| Found (`PROVISIONING`) | "GPU found", the GPU and its memory, "Starting…" | Cancel request |
| Running (and moving host, saving) | The dark tile holds the time and cost (54 px), the state and "of about 45 min"; then tiles for the GPU (use and temperature), the checkpoints, the log (a white tile with a monospace body), and Stop. On a laptop they sit in a grid beside the log | Stop session, asking first |
| Done (`COMPLETED`) | The charge (dark tile), the outputs (lime, with Download), the rating, "Report a problem" and Run again | Download outputs, then Run again |
| Ended otherwise | Why, in plain words, and what to do; the charge, often "Nothing was charged" | Try again, filled from it |

All sessions is a list of white tiles, newest first, with a lime dot on the live one.

Raising the cap and sharing a live link are not built yet.

## Credit

The balance as a big dark tile (Available, with On hold and Total beside it), and the history as white tiles grouped by day.

## Hosting

The owner's screens (ADR 0023), for someone who wants to know "is my machine earning, and is it safe?" from their phone. They follow the same pattern: one dark summary tile, then white tiles.
- **The Hosting tab:** "Earned this week" as the dark tile, big, with the total beside "payouts start with the paid pilot" and the last 7 days as bars in lime (today in full, a day with nothing a thin line); their machines, each an apricot or white tile with its state in words; "Add a machine". With none, what hosting is and that one step.
- **A machine's states,** one at a time, in this order: running a session (the dark tile, its dot pulsing, "earning ₹2.73 so far"), paused (until when), update needed, offline, checking (not yet activated), suspended, idle. A paused machine finishes a session it's running, and says so.
- **One machine:** a dark tile with its state, name and GPU; then tiles for its temperature and use against the limit its owner set; "Right now", the session running and what it has earned so far; the privacy line ("Renters never see who you are, and their work never touches your files."); rows to its price and limits, the sessions it ran, and renaming or removing it; and at the foot, "Pause hosting" (a choice of how long, opened in place) or "Resume".
- **Money is the server's:** "earned so far" is read again every 5 seconds while a session runs and changes in place; it's never counted up on the client (principle 4). The elapsed time ticks.
- **Price and limits:** the price an hour with − and + in ₹0.60 steps inside a white tile, what the owner keeps of it and the 20% fee, worked out from whole paise; the range others charge (or the usual range for a GPU like theirs); the temperature to pause above (60 to 95 °C); the disk to keep free (10 to 500 GB); and a fixed line that it's one session at a time.
- **Removing** asks first in a sheet that names the machine, never a browser dialog; the one other dialog the app has.
- **Add a machine** is "Earn with this laptop" (ADR 0032): what a laptop needs, the download (a disabled "Windows app: coming soon" until the installer exists), and the one command that links it. No code is shown or typed.
- **The link page** (`/link/<id>/`, features/hosting/LinkApproval.tsx): a signed-in screen in a centred tile. "Link this laptop to your account?" over the laptop as it described itself (name, GPU, system, agent version, minutes left), a name field, the price with "You'll earn ₹x an hour (renters pay ₹y)" and Change (the machine's own price form), "Approve and start earning" and "This isn't my laptop". It is drawn whole while loading. Expired, denied and linked are states of the same tile; linked says new laptops are checked before they go live and opens the machine.
- **Words:** here, and only here, a host's owner calls their own host a **machine**. Renters never see "host" or "machine"; owners never see who rented one.

## Account, sign-in and the rest

Sign-in, the invite step, Account, API keys, the notifications list and the CLI approval are white tiles on the page colour, with one primary action each. The admin console (ADR 0027) stays dense: it takes the font, the colours and the radii, and is not reorganised into tiles.

## Laptop layout

From 1200 px every signed-in screen is laid out for the screen, not as the phone's tiles in a column (ADR 0031's addendum). The references are `docs/design/bento/laptop/` (`home-laptop.html`, `live-session-laptop.html`, `hosting-laptop.html`, `account-laptop.html`), judged at 1440 × 900.

**The system**
- **Breakpoints:** phone under 768 px; tablet 768 to 1199 px (the laptop's header and tabs, with 6 columns and stacked rails); laptop from 1200 px (`xl:`, `useLaptop()`); a wide step at 1680 px (`2xl:`).
- **Frame and grid** (`components/shell/Layout.tsx`): the content runs the full width of the window, no maximum and no centred column; side padding 14 px on a phone, 24 from 768 px, 32 from 1200 px and 40 from 1680 px; 12 columns with a 16 px gap (20 px from 1680 px), 6 columns on a tablet. A screen that fills the window (Home, the live session, Hosting) is 100% of the height under the header less 16 px above and 24 px below (`FIT_FRAME`, `fit-screen`).
- **The header** is 76 px high in three zones: the wordmark on the left; in the middle one white pill bar of five tabs (Home, Book, Sessions, Hosting, Credit), the current one dark; on the right the credit chip (`CreditChip`, the wallet's available amount), the bell and the avatar. The avatar is a link to Account, with a 3 px lime ring while Account or a page under it is current; the account menu's contents (My files, theme, Sign out) are in Account's rail. A phone keeps the header and the floating tab bar.
- **Rails** (`RailColumns`): a left column of 400 px (320 px for Account) of summary tiles and the rest beside it, stacked on a tablet. The live session, Hosting's pages, Credit and Account use it; the left column's first tile is dark.
- **Tiles hold their space:** no more than about 35% of a tile is empty at 1440 × 900. A tile is as tall as its content, or two tiles share a row's height; one with nothing to say isn't drawn (no free GPUs, a log with under two loss values, a host with no sessions). Figures are tabular and come only from the API; money is `formatPaise`.
- **Rules that stay:** one dark primary button on a screen; touch targets of 44 px or more; no layout shift, so a skeleton is exactly its tile's height; the first screen's requests are only what it needs to paint, and the tiles that need more ask after the paint (`useAfterPaint`).

**Home** (`home-laptop.html`), 12 columns and three rows that fill the screen:

| Tile | Columns | Colour | What it holds |
| --- | --- | --- | --- |
| Running now, or "What do you want to run?" | 8 × 2 rows | dark | The live session's service, GPU, elapsed time at 60 px and cost (lime), opening it; or, with none, the question and the offered services as lime and white buttons. Dropping a folder anywhere on the page opens the matching service with it |
| Credit | 4 | white | Available, on hold, and the way to Credit |
| Free GPUs now | 4 | white | Each GPU memory class with whether one is free and the live price range (`GET /v1/public/availability`), drawn after the first paint; not there when it has nothing |
| Recent sessions | 8 | white | The latest sessions as rows, from the sessions already loaded or the first page of the list, drawn after the first paint |
| Book again | 4 | lavender | The last setup of an offered service ("Fine-tune a model · 41 min"); not there for a new renter or for a test job |
| Earn with your GPU | 4 | apricot | The host pitch, opening Hosting |

**The live session** (`live-session-laptop.html`): the left rail holds the dark tile (state, the timer at 60 px, the cost and the cap bar), then the GPU and checkpoint tiles and Stop. The right side is the log, a white tile with a monospace body that scrolls inside the screen; above it the steps the log shows (loading the model, training, saving), and the loss chart when the log has two or more loss values. Finding is the dark tile with the ring and the request beside it; Done is the charge, the outputs (lime, with Download), the rating and "Run again". Nothing is drawn that the log or the session doesn't carry.

**Hosting** (`hosting-laptop.html`), two equal rows: the dark tile of the week (the amount, the total and hours rented, the seven days as bars) over 8 columns and the apricot "Add a machine" (what a machine needs, one-time code, about 5 minutes) over 4; then each machine as a 4-column tile (state, temperature and use, name, GPU and price, "Earning now" or Pause or Resume) and "Latest earnings" with the last entries and All. With more than two machines they are a grid of three a row, and the earnings sit under them. Machine, Price, Sessions it ran, Earnings and Add have the 400 px rail with a dark summary tile (the machine's state, name and rating; or the screen's title and what it is) and white tiles beside it.

**Account** (`account-laptop.html`): the 320 px rail holds a dark profile tile (an avatar in lime, the name and email), the four sections (Profile and notifications, API keys, My files, Reports), Appearance (Match my device, Light or Dark) and Sign out. Beside it Profile (name with Edit, email, how they sign in) and Notifications, a table of a kind and two switches (Email, In the app), the kinds about money and safety locked on with their reason. API keys, My files and Reports are tiles of rows in the same frame.

**The other screens**
- **Book:** the full width with 7 columns of questions and, sticky at the top, the dark "Your request" in 5.
- **Sessions:** a full-width table (service, started, time, state, charge) in one white tile, a row a link; Active and Ended pills; paging as before.
- **Credit:** the 400 px rail with the dark balance tile (available, on hold, total) and the history beside it, grouped by day.
- **Sign-in:** the sign-in tile in 5 columns and, beside it, a collage in 7 of what the product is (dark "Rent a GPU by the minute", lime "Fine-tune a model", apricot "Earn with your GPU", lavender "A locked box"), saying only what the website says. A small "← idleo" leads back to the website; "Get started" from the website arrives as "Create your account".
- **Notifications, the CLI approval, the invite step:** one white tile, 7 columns, centred, never a phone column.
- **The admin console** is unchanged.

**The website** (ADR 0029) uses the same frame and grid at the same widths: its landing page is 12 columns (the dark tile 8, the services 4, three steps, three promises, one apricot band), and the docs keep a readable column of text inside it. Its header is the wordmark, the pages as white pills, "Log in" as a link and "Get started" as the dark button.

## Voice

Short, plain sentences. Second person ("your credit"), active voice, no exclamation marks. Copy follows the reference screens: "What do you want to run?", "Request a GPU", "Finding your GPU", "GPU found", "Best free GPU for this job".

**The core words, and never a synonym for them:**

| Word | Means | Never say |
| --- | --- | --- |
| **service** | Something you can run ("Fine-tune a model"): a tile on Home, a choice in Book | product, app, tool |
| **template** | One way to run a service: the image and its settings | image, recipe, preset, environment |
| **session** | One booked run of a template on a host's GPU | job, run, instance, rental, task |
| **credit** | Money in the renter's account, in rupees | balance, funds, wallet, money, coins |
| **host** | A person's machine that runs sessions | provider, node, server, worker, machine (in the UI) |
| **output** | A file a session produced, to download | result, artifact, download, file (as the name of the thing) |

A service is something you can run; a template is one way to run it.

The parts of credit have fixed labels too: **Available** (what you can spend now), **On hold** (set aside for sessions that are running or about to), and **Total** (the two together).

## Brand

The product name isn't decided. **IdleO** is the working name.

- The name, the wordmark, the ring's colour and the favicon live in one place, `web/src/brand/`, so the final brand is one small change.
- **The wordmark is the name in lowercase** ("idleo"), set at the `figure` step (24 px), weight 800, as in the reference: one `Wordmark` component, in the app's header, sign-in, the admin console and the public pages; it comes from `brand.name` and has no image to load. **The ring** (a ring in the brand's indigo with a small gap at the top, like a power button at rest) is the favicon and the large, breathing ring of "Finding your GPU".
- No stock logos or icons stand in for the brand.

## Icons

One set only: **Lucide**, imported one icon at a time. 20 to 22 px in controls and rows, 16 px inline with text, 30 px on a service's tile, 1.6 to 1.75 px stroke (2 px for chevrons), coloured with the text they sit beside. A service names its icon by a key from a fixed list (`layers`, `code`, `table`, `cube`, `notebook`, `plug`, `gamepad`, `flask`, `pulse`); `src/lib/service-icons.ts` maps each to its Lucide icon. An icon-only button always has a visible tooltip or an accessible label.

## The public pages

The signed-out pages (ADR 0029) follow everything above: the same tokens, type, tiles and primary button, one primary action on a page, and sentence case, except the docs, which are a calm reading layout without tiles. They differ in three ways: they are their own build at their own address (the website, with the app at another), they are static HTML with no app code, and their budgets are stricter (on Lighthouse's mobile profile, the median of three runs: LCP 1.5 s, TBT 100 ms, CLS 0, accessibility 100, SEO 100; 80 KB for a first load). A live figure has its place on the page at its final size, so loading, loaded, empty, error and stale never move anything. A number is live (from the API) or marked "typical"; nothing is invented.
