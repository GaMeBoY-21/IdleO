# The IdleO design language

The standard every screen is checked against, now and later. Numbers, not adjectives: if a screen breaks a rule here, the screen is wrong, or this file changes first, with Nikki's say.

The look was chosen on Oct 8, 2026: **a ride app's style**. Black and white, big bold type, one question per screen, the things you can run on Home, and requesting a GPU the way you request a ride. The reference screens are in `docs/design/redesign/`.

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
| Home (what to run, credit, Book again) | Phone | Laptop |
| Book | Laptop | Phone |
| Session: finding, found, live, done | Phone | Laptop |
| All sessions | Phone | Laptop |
| Credit history | Phone | Laptop |
| Host home and earnings (later) | Phone | Laptop |

## Principles

1. **One question per screen, one obvious next step.** A screen asks one thing in big type ("What do you want to run?") and has at most one primary button (black, white in the dark theme): its next step. A screen that only shows information may have none. Two primary buttons on one screen is a bug.
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
   - No modal where a page works. The one dialog confirms stopping a session. Settings open in a sheet: a side drawer on a laptop, a bottom sheet on a phone.
   - No jargon. Say "GPU memory", not "VRAM", unless it's next to a number ("24 GB GPU memory").
   - Error messages say what happened and what to do next, in that order: "Your sign-in link has expired. Send a new one."
6. **Speed is a feature.** Navigation feels instant. Every interaction shows a response within 100 ms, even if that's only a pressed state. Data a screen has already seen is shown at once from the cache, then refreshed.

## Typography

- **One family: Inter** (Rasmus Andersson, SIL Open Font License 1.1), version 4.1, self-hosted. No request ever goes to a font service.
  - **Why Inter:** it was drawn for user interfaces at small sizes on screens. It has true tabular figures (`tnum`), and it includes the rupee sign (₹, U+20B9), which many UI families lack.
  - **One file:** a subset of Inter's variable font, cut by `web/scripts/subset-font.sh`, holds Basic Latin, Latin-1, the punctuation we use, the arrows, the minus sign, `≈` (U+2248) and ₹. The weight axis is kept from 400 to 700 and the optical size is pinned at text size, which makes it 25.5 KB as WOFF2.
  - **It never swaps.** It's preloaded with `font-display: optional`. If it hasn't arrived by the first paint (a slow first visit), that page keeps the fallback, Arial scaled to Inter's metrics, because a swap would move text sideways. Every later page has it from the cache.
- **Logs are the one exception:** a session's log panel uses the system's monospace fonts (`font-mono`), so nothing is downloaded for it. Nothing else is set in monospace.
- **Numbers** (money, durations, counts, times) use Inter's tabular figures: the `tabular` class sets `font-variant-numeric: tabular-nums`.
- **Weights:** 400 for reading, 500 for secondary labels, 600 for titles of rows and options and for buttons, 700 for headings and big figures.
- **The scale.** Sizes of 28 and up are weight 700 with -0.03em letter-spacing, built into their tokens.

| Token | Size / line height | Used for |
| --- | --- | --- |
| `text-caption` | 13 / 18 px | Captions, timestamps, the line under a field |
| `text-small` | 14 / 20 px | Secondary text, chips, buttons, navigation |
| `text-body` | 16 / 24 px | Reading text, form inputs (16 px stops iOS zooming on focus) |
| `text-option` | 17 / 24 px | The title of an option or a row; large buttons |
| `text-section` | 20 / 26 px | Section titles ("Your data", "Your GPU") |
| `text-title` | 28 / 34 px | Page titles on a phone; big figures beside the timer |
| `text-question` | 40 / 44 px | The laptop's page question ("What do you want to run?") |
| `text-timer` | 56 / 56 px | The running timer on a phone |
| `text-timer-xl` | 72 / 72 px | The running timer on a laptop |

- **Line length:** reading text is at most 68 characters wide (`max-w-prose`, set to 68ch). Tables and lists may be wider.
- **Case:** sentence case everywhere: titles, buttons, labels, navigation. No all-caps text.

## Colour

Black and white, with one green for running, the warning and danger colours, and the brand's indigo only in the wordmark's ring and the focus ring.

- **The theme** follows the system preference (`prefers-color-scheme`) until the person chooses Light or Dark from the user menu. Their choice is remembered on this device only.
- **Surfaces are grey fills, not bordered boxes.** The ground is white (near-black in the dark theme); a fill is a step darker (a step lighter in dark). The primary button is black with white text (white with black text in dark).
- **Colour is never the only signal.** A state pill has a word in it; an error has an icon and a sentence.

| Token | Light | Dark | Used for |
| --- | --- | --- | --- |
| `ground` | `#FFFFFF` | `#0B0B0B` | The page behind everything; panels inside a grey area |
| `fill` | `#F3F3F3` | `#1A1A1A` | Fields, tiles, grey rows, chips, the laptop's right panel |
| `fill-selected` | `#F6F6F6` | `#222222` | The selected option's background |
| `divider` | `#EBEBEB` | `#2A2A2A` | 1 px lines between list rows and under the top bar; bar tracks |
| `control` | `#767676` | `#8A8A8A` | Outlines of switches and radio buttons (must meet 3:1) |
| `text` | `#000000` | `#FFFFFF` | Primary text; the 2 px border of the selected option |
| `text-muted` | `#5E5E5E` | `#A6A6A6` | Secondary text; still meets 4.5:1 |
| `primary` | `#000000` | `#FFFFFF` | Primary buttons, the current navigation pill, the running bar on Home |
| `primary-hover` | `#2B2B2B` | `#E0E0E0` | Primary buttons when hovered or pressed |
| `primary-fg` | `#FFFFFF` | `#000000` | Text on `primary` |
| `inverse-muted` | `#BDBDBD` | `#4A4A4A` | Secondary text on `primary` |
| `inverse-running` | `#4CC38A` | `#0B6B37` | The running dot on `primary`: Home's running bar |
| `running` | `#0B6B37` | `#4CC38A` | Running, done steps, a saved checkpoint |
| `running-soft` | `#E3F4EA` | `#10291D` | The running pill's background |
| `warning` | `#8A5300` | `#F0B44C` | Low credit, stale data |
| `warning-soft` | `#FFF3DD` | `#2E220B` | Warning banner background |
| `danger` | `#C0262D` | `#FF6B6B` | Failed, errors, destructive actions |
| `danger-soft` | `#FDECEC` | `#331414` | Error banner background |
| `danger-fg` | `#FFFFFF` | `#000000` | Text on `danger` |
| `log-ground` | `#111111` | `#161616` | The log panel, dark in both themes |
| `log-text` | `#D6D6D6` | `#D6D6D6` | Log lines |
| `log-muted` | `#A8A8A8` | `#A8A8A8` | Secondary text on the log panel |
| `brand` | `#4B3FD1` | `#8F86FF` | The wordmark's ring, and nothing else |
| `focus` | `#4B3FD1` | `#A39CFF` | The 2 px focus ring, offset 2 px |

### Contrast, WCAG 2.2 AA

Every text pair meets 4.5:1 (1.4.3), and every control outline, focus ring and graphic meets 3:1 (1.4.11). `web/src/design/tokens.test.ts` computes each ratio from the tokens and fails if any is below its minimum, or if it differs from this table by more than 0.01.

| Foreground on background | Light | Dark | Minimum |
| --- | --- | --- | --- |
| `text` on `ground` | 21.00 | 19.68 | 4.5 |
| `text` on `fill` | 18.93 | 17.40 | 4.5 |
| `text` on `fill-selected` | 19.43 | 15.91 | 4.5 |
| `text-muted` on `ground` | 6.48 | 8.09 | 4.5 |
| `text-muted` on `fill` | 5.84 | 7.15 | 4.5 |
| `text-muted` on `fill-selected` | 6.00 | 6.54 | 4.5 |
| `primary-fg` on `primary` | 21.00 | 21.00 | 4.5 |
| `primary-fg` on `primary-hover` | 14.16 | 15.91 | 4.5 |
| `inverse-muted` on `primary` | 11.18 | 8.86 | 4.5 |
| `inverse-running` on `primary` | 9.48 | 6.62 | 3 |
| `running` on `running-soft` | 5.80 | 6.99 | 4.5 |
| `running` on `ground` | 6.62 | 8.89 | 4.5 |
| `warning` on `warning-soft` | 5.76 | 8.40 | 4.5 |
| `warning` on `ground` | 6.33 | 10.63 | 4.5 |
| `danger` on `danger-soft` | 5.17 | 6.05 | 4.5 |
| `danger` on `ground` | 5.90 | 7.09 | 4.5 |
| `danger-fg` on `danger` | 5.90 | 7.57 | 4.5 |
| `log-text` on `log-ground` | 12.99 | 12.45 | 4.5 |
| `log-muted` on `log-ground` | 7.94 | 7.61 | 4.5 |
| `control` on `ground` | 4.54 | 5.70 | 3 |
| `control` on `fill` | 4.09 | 5.04 | 3 |
| `focus` on `ground` | 7.17 | 8.21 | 3 |
| `focus` on `fill` | 6.46 | 7.26 | 3 |
| `brand` on `ground` | 7.17 | 6.58 | 3 |

## Shapes and space

- **No bordered cards.** Surfaces are grey fills and white space. 1 px dividers appear only between list rows and under the laptop's top bar.
- **The selected option** has a 2 px border in the text colour (black, white in dark) on `fill-selected`, and its icon tile turns black.
- **Corners:** 14 px for controls, fields, options and rows (`rounded-control`); 12 px for icon tiles; fully round for pills, chips, avatars and navigation; 24 px for panels; 20 px for the top of a bottom sheet.
- **Spacing is a 4 px scale:** 0, 4, 8, 12, 16, 20, 24, 28, 32, 36, 40, 48, 56, 60, 64, 72, 96 px, plus 2 px between a title and its line under it, 6 px for thin bars, and 14 px inside options and rows. The half steps 18, 26 and 34 px exist only as the type scale's line heights, for skeleton lines.
  - Between sections: 28 to 32 px; between a section's title and its content: 12 px; between options: 8 px.
- **Heights:** controls at least 44 px (`touch`); fields 56 px; the large button at the foot of a flow 56 px on a phone and 60 px on a laptop.
- **Named sizes:** the finding ring 88 px on a 168 px grey disc (`finding-ring`, `finding`), with two soft halos of the fill; the log panel at least 384 px (`panel`); Book's price beside its GPU card 112 px on a laptop (`price`), so it never moves as it arrives.
- **Shadows** only for things that float: menus and popovers (`shadow-float`) and a bottom sheet over the page (`shadow-sheet`).
- **Touch targets are at least 44 × 44 px**, even where the visible control is smaller (the hit area is padded).
- **The layout:**

| Width | Layout | Margin |
| --- | --- | --- |
| Up to 767 px (phone) | One column; Book, the session and Finding are full-screen flows with a back button | 16 px |
| 768 px and up (tablet, small laptop) | One column, wider | 24 px |
| 1280 px and up (laptop) | Two panels: the question and its choices on the left, at most 480 px, with a 1 px divider; a grey panel on the right with a white card of at most 600 px | 32 px |

- **Navigation:**
  - **Laptop:** a 64 px top bar with the wordmark (a link to Home), pill navigation (Book · Sessions · Credit, the current one a black pill), and on the right a grey credit pill and a black round account button.
  - **Phone:** a top bar with the wordmark, the credit pill and the account button, and bottom tabs: Home · Book · Credit. Book and the session screen are flows: on a phone they replace both bars with a back button, the way a ride app's booking does.
  - On a phone, All sessions is a row on Home, under Book again, since it has no tab.
  - Hosting joins both when it exists.

## Motion

- **Durations:** 120 ms for presses, hovers and toggles; 180 ms for small things appearing (menus, popovers, toasts); 240 ms for large things (sheets, dialogs, page content fading in). Nothing longer, but for the two continuous motions below.
- **One easing family**, a decelerating curve:
  - entering: `cubic-bezier(0.2, 0, 0, 1)`;
  - leaving: `cubic-bezier(0.4, 0, 1, 1)`, at two-thirds of the entering duration.
- **What moves:** opacity and small translations (at most 8 px) of things appearing or leaving; colour on hover and press; the skeleton's shimmer.
- **The two continuous motions,** both 2 seconds and both off under reduced motion:
  - **the running pulse:** while a session is `RUNNING`, the dot in its state pill pulses its opacity;
  - **the finding ring:** while we find a GPU, the large ring breathes (a little smaller and fainter, then back).
- **What never moves:** money figures (they change in place, never count up), page layout, and anything the person is reading or about to tap.
- **`prefers-reduced-motion: reduce` removes all non-essential motion:** no translations, no shimmer, no pulse. Changes are instant; only focus and state colours remain.
- **No layout shift, ever** (cumulative layout shift 0 on every screen):
  - skeletons have the same size as the content that replaces them;
  - images and icons have fixed dimensions;
  - the font never swaps (see Typography);
  - **banners push the content below them down; they never cover it** (Nikki, Oct 8): they're rare and important, and covering would hide what you're reading.

## States

Every view that shows data designs four states before it's built:

| State | What it shows |
| --- | --- |
| **Loading** | A skeleton with the shape of the content: same rows, same heights, no text. No spinner. |
| **Empty** | Why it's empty and what to do: "No sessions yet. Book a GPU to run your first one." With the one next step as the primary action. |
| **Error** | What happened, in a sentence, plus a Retry button: "We couldn't load your sessions. Check your connection and try again." Never a raw status code. |
| **Partial or stale** | The data it has, plus one line at the top: "Couldn't refresh. Showing figures from 12:04. Retrying…" Stale money is never presented as current, and nothing says "Live" while it's stale. |

## Times

- **Relative where it's about how long ago:** "started 18 min ago", "2 h ago", "yesterday", then the date after a week. The exact time is in the `<time>` element's `datetime` and its title.
- **Elapsed time ticks** every second while a session runs, as a clock ("18:42", "1:02:05"), and stops when it ends. Durations of ended sessions keep seconds only under ten minutes ("6 min 10 s", "41 min").
- **One clock for the app** (`src/lib/clock.ts`): every relative time moves on together every 30 seconds, and every elapsed time every second, from a single timer.

## The phone action bar

On a phone, a screen whose next step is used while watching something (Stop session while it runs, Cancel request while finding, Download outputs or Try again once it ends) puts that action at the foot of the screen, in a bar with a 1 px divider above it. The bar is stuck to the bottom of the screen but takes its own place at the end of the page, so it covers nothing and nothing moves when it appears. It holds that one action, a second outlined one at most ("Run again"), and a line under it ("Saves a checkpoint first. Nothing more is charged."). On a laptop the same actions end the left panel.

## The session screen

The session screen follows the state, like a ride (docs/design/redesign/matching-phone.html, live-phone.html, live-laptop.html):

| State | What it shows | Its action |
| --- | --- | --- |
| Finding (`REQUESTED`, `MATCHED`) | The ring, breathing; "Finding your GPU", "Usually under a minute"; what's done ("train.jsonl uploaded"); "Nothing is charged until it runs" | Cancel request (outlined) |
| Found (`PROVISIONING`) | "GPU found", the GPU and its memory, "Starting…" | Cancel request |
| Running (and moving host, saving) | The green Running pill; "Fine-tuning on an RTX 4080 Laptop"; the big timer and "of about 45 min"; the cost so far against the cap, with a bar; the GPU's use and temperature; the last checkpoint; on a phone a Logs row, on a laptop the route across and the dark log panel | Stop session (outlined), asking first |
| Done (`COMPLETED`) | "Done", how long it ran, the final charge (or "so far" until it's settled), the outputs as rows | Download outputs, then Run again |
| Ended otherwise | Why, in plain words, and what to do; the charge, often "Nothing was charged" | Try again, filled from it |

Raising the cap and sharing a live link are in the references but not built yet.

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
- **The wordmark** is original: "Idle" drawn as outlines, followed by a ring for the "O" in the brand's indigo, with a small gap at the top, like a power button at rest. The favicon is the ring alone. The large, breathing ring of "Finding your GPU" is the same ring.
- No stock logos or icons stand in for the brand.

## Icons

One set only: **Lucide**, imported one icon at a time. 20 to 22 px in controls and rows, 16 px inline with text, 1.75 px stroke (2 px for chevrons), coloured with the text they sit beside. A service names its icon by a key from a fixed list (`layers`, `code`, `image`, `mic`, `table`, `cube`, `notebook`, `plug`, `gamepad`, `flask`, `pulse`); `src/lib/service-icons.ts` maps each to its Lucide icon. An icon-only button always has a visible tooltip or an accessible label.
