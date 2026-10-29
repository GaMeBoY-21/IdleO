# The IdleO design language

The standard every screen is checked against, now and later. Numbers, not adjectives: if a screen breaks a rule here, the screen is wrong, or this file changes first, with the product owner's say.

The look is **Night Shift** (ADR 0043, which supersedes ADR 0031's Bento): a deep indigo page, panels a step lighter, and a few loud colours (volt, ember, ion), set in a heavy, wide, often uppercase headline face over a plain text face. It comes in two themes with one layout: **Night Shift**, dark and the default, and **Day Shift**, light. Requesting a GPU like a ride (ADR 0021's behaviour) is unchanged. The approved references are `Main`, `AppHome` and `Session`, each in a dark and a light form.

The tokens below live in code in one place, `web/src/design/tokens.ts` (the brand's accent in `web/src/brand/brand.ts`, so the brand stays one change), and Tailwind's theme and the fonts are built from them (`web/scripts/tokens-css.ts`). Nothing outside that file names a colour, a size or a duration; a lint rule refuses raw colours and arbitrary values.

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

1. **One question per screen, one obvious next step.** A screen asks one thing in big type ("What do you want to run?") and has at most one primary button (volt at night, the page's ink with volt text by day; `primary`): its next step. A screen that only shows information may have none. Two primary buttons on one screen is a bug. The volt button inside the night "Your request" panel is that screen's one primary.
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

- **Three families, all self-hosted** (SIL Open Font License 1.1; each licence is in `web/public/fonts/` beside its file). No request ever goes to a font service. Each is a subset cut by `web/scripts/subset-font.sh` from a pinned commit of the google/fonts repository: Basic Latin, Latin-1, the punctuation we use, the arrows, the minus sign, `≈` (U+2248) and the rupee sign (₹, U+20B9); tabular figures kept.
  - **Archivo** for headlines, often UPPERCASE, as **two static instances** of the variable font, never the whole of it: width 125% at weight 900 (`font-display`: page and section headlines, the wordmark, big figures and step numbers) and width 112% at weight 800 (`font-title`: the title inside a panel).
  - **Inter** for everything else, one variable file for weights 400 to 700 at the 14 px optical size.
  - **JetBrains Mono**, weight 400, for logs (`font-mono`), and nothing else.
- **Files** (WOFF2): `archivo-125-900.woff2` 5.4 KB (the uppercase of the headlines) and `archivo-125-900-lower.woff2` 2.4 KB (the wordmark's lowercase), `archivo-112-800.woff2` 7.6 KB, `inter-400-700.woff2` 16.8 KB, `inter-400-700-latin1.woff2` 9.2 KB (Inter's accented letters, fetched only by a page that has one), `jetbrains-mono-400.woff2` 18.3 KB. The app preloads the three its first screen uses, Inter, the titles' face and the wordmark's lowercase (27 KB), and the website adds the headlines' uppercase (24 KB in all, without the titles'); the others load when the page asks for them. The website's code is set in the reader's own monospace font, so JetBrains Mono is the app's alone. The first-screen files hold Basic Latin and the few signs we use (the degree and section signs among them: a test keeps every other accented letter out of our own text); the speed budget is why they are not larger.
- **They swap.** Each face is `font-display: swap`, over a fallback of Arial (or Liberation Sans or Arimo, which have its metrics, on a machine without Arial: Linux, ChromeOS, the build's browser) scaled to the face's metrics (`size-adjust`, `ascent-override`, `descent-override`; generated from the same table), so the swap moves nothing: cumulative layout shift stays 0. The faces no page preloads (the headlines' uppercase and the titles) are fetched as soon as the first screen has painted (`loadHeadlineFonts`), so a screen drawn after data arrives has them already.
- **Numbers** (money, durations, counts, times) use tabular figures: the `tabular` class sets `font-variant-numeric: tabular-nums`.
- **Weights:** 400 for reading, 500 for secondary labels and navigation, 600 for titles of rows and options and for buttons, 700 for small emphasis and the primary button. Headlines are Archivo's own 900 or 800; nothing else is heavier than 700.
- **Inter's scale.** Sizes of 20 and up are weight 700 with tight letter-spacing built into their tokens (−0.01em up to 26 px, −0.02em at 28 and 40, −0.03em at 54 and above), except `lede`, which is regular.

| Token | Size / line height | Used for |
| --- | --- | --- |
| `text-caption` | 13 / 18 px | A panel's label, captions, timestamps, the line under a field |
| `text-small` | 14 / 20 px | Secondary text, chips, buttons, navigation |
| `text-body` | 16 / 24 px | Reading text, form inputs (16 px stops iOS zooming on focus) |
| `text-option` | 17 / 24 px | The title of an option or a row; large buttons |
| `text-lede` | 20 / 30 px | The website's opening paragraph (regular weight) |
| `text-section` | 20 / 26 px | A panel's title ("Recent sessions") |
| `text-figure` | 24 / 28 px | A figure on a small panel (the credit) |
| `text-headline` | 26 / 28 px | A panel's headline |
| `text-title` | 28 / 34 px | Page titles on a phone; big figures beside the timer |
| `text-question` | 40 / 44 px | The laptop's page question |
| `text-timer` | 54 / 54 px | The running timer on a phone, and the big credit |
| `text-timer-lg` | 60 / 60 px | The running timer in the live session's panel on a laptop |
| `text-timer-xl` | 72 / 72 px | The running timer on a laptop |

- **Archivo's scale** is fluid, between a smallest and a largest size, with the line height as a multiple:

| Token | Size | Line height | Used for |
| --- | --- | --- | --- |
| `text-display-hero` | 44 px to 84 px (6 vw) | 0.98 | The website's headline, uppercase |
| `text-display-band` | 36 px to 60 px (4.4 vw) | 1 | A section of the website, uppercase |
| `text-display-section` | 32 px to 52 px (4 vw) | 1 | A smaller section, the marquee, uppercase |
| `text-display-page` | 32 px to 44 px (3.2 vw) | 1 | An inner page's headline, and the app's page headline from 768 px, uppercase (on a phone the app's headline is `text-display-feature` in the title face, sentence case: `PageHeading`) |
| `text-display-panel` | 40 px | 1.05 | A large panel's title (`font-title`) |
| `text-display-feature` | 30 px | 1.1 | A panel's title (`font-title`) |
| `text-display-card` | 26 px | 1.1 | A card's or sub-section's title (`font-title`) |
| `text-display-number` | 72 px | 1 | The website's step numbers |
| `text-display-figure` | 52 px | 1 | The credit tile's figure |
| `text-display-logo` | 28 px | 1.2 | The wordmark |
| `text-display-wordmark` | 96 px to 280 px (19 vw) | 0.8 | The website footer's large wordmark |

- **Line length:** reading text is at most 68 characters wide (`max-w-prose`, set to 68ch). Tables and lists may be wider.
- **Case:** sentence case for text, titles inside panels, buttons, labels and navigation. Uppercase is for Archivo's headlines, the small tracked labels above them ("FOR BUILDERS") and table headings.

## Colour

Two themes with the same layout. **Night Shift** (dark) is a deep indigo page with panels a step lighter, and three loud colours. **Day Shift** (light) is a pale lilac page with white panels that have a thin edge. A few things are the same in both: the accent blocks (ember, ion, ion-deep), the website's live-session preview card and its "sealed" band, which stay dark as a product preview, and any night panel.

- **Choosing.** An Appearance choice (Match my device, Light, Dark) is a cookie on the parent domain, so the website and the app, on different addresses of one domain, share it on the same browser; it is never a server setting. In the app it is under Account, and on the website a small switch in the footer. With no choice, both follow the device's setting (`prefers-color-scheme`); with no setting, dark.
- **No flash.** A tiny inline script in each page's head (`themeBootScript`, 127 bytes, allowed by its hash in the content security policy) reads the cookie and sets `data-theme` on `<html>` before the first paint. Every colour token has a dark and a light value in `tokens.ts`, switched by that one attribute (and by the device's setting when there is none).
- **Panels, not bordered boxes.** The page is `canvas`; a panel is `panel` (a step lighter at night, white with a 1 px `edge` by day). A field inside a panel is filled with `panel-2`, so it looks pressed in. Depth comes from colour, not shadows: the one shadow is the website's hero card's soft violet glow.
- **Volt is the one primary**, the live numbers and the running states, at night. **By day volt is never text on a light background** (it fails contrast); there it is only a fill under dark text, the primary button is the page's ink with volt text, and links and progress are ion-deep. Ember, ion and ion-deep blocks stay as they are in both themes.
- **Night panels** (`night`, with `on-night`, `on-night-2`, `on-night-3`) are the one thing to look at: what's running, the charge, the request. A `night` tile and anything marked `scope-night` carry the dark theme's colours for everything inside them, whatever the page's theme, so the same classes read correctly in both.
- **Colour is never the only signal.** A state chip has a word in it; an error has an icon and a sentence; the selected option has a check.

| Token | Night Shift | Day Shift | Used for |
| --- | --- | --- | --- |
| `canvas` | `#0C0A1F` | `#F4F3FA` | The page |
| `sidebar` | `#0F0D25` | `#FFFFFF` | The app's sidebar |
| `panel` | `#17142F` | `#FFFFFF` | Panels; a field on the page |
| `panel-2` | `#221E40` | `#F4F3FA` | A panel inside a panel; a field inside a panel |
| `raised` | `#1B1838` | `#EEECF8` | The active nav item, a chip's fill |
| `ghost` | `#1B1838` | `#FFFFFF` | A ghost button's fill |
| `ghost-hover` | `#27234B` | `#EEECF8` | A ghost button when hovered or pressed |
| `line` | `#2A2650` | `#ECEAF5` | Dividers inside a panel; empty bars and tracks |
| `edge` | none | `#E4E2F0` | A panel's 1 px edge (none at night) |
| `control` | `#7A75B0` | `#7A76A0` | Outlines of switches and radio buttons (must meet 3:1) |
| `text` | `#FFFFFF` | `#0C0A1F` | Primary text |
| `text-2` | `#B9B5DA` | `#4A4668` | Secondary text |
| `text-3` | `#8F8AB8` | `#5E5A80` | Labels and captions |
| `volt` | `#C8FF3D` | `#C8FF3D` | The one primary button at night; live numbers and running states on a night surface; a fill under dark text by day |
| `volt-hover` | `#DAFF70` | `#DAFF70` | Volt when hovered or pressed |
| `ember` | `#FF7A2F` | `#FF7A2F` | An accent block (text on it is `ink`) |
| `ion` | `#8E80FF` | `#8E80FF` | An accent block (text on it is `ink`) |
| `ion-deep` | `#5B4BE8` | `#5B4BE8` | Bands, the credit tile and the secondary button (text on it is `on-ion-deep`) |
| `ion-deep-hover` | `#7060F0` | `#4A3BD0` | The secondary button when hovered or pressed |
| `black` | `#000000` | `#000000` | The "sealed" showcase band |
| `ink` | `#0C0A1F` | `#0C0A1F` | Text on volt, ember and ion; the page's dark |
| `on-ion-deep` | `#FFFFFF` | `#FFFFFF` | Text on ion-deep |
| `primary` | `#C8FF3D` | `#0C0A1F` | The primary button: volt at night, the page's ink by day |
| `primary-hover` | `#DAFF70` | `#25214A` | The primary button when hovered or pressed |
| `primary-fg` | `#0C0A1F` | `#C8FF3D` | Text on `primary` |
| `link` | `#C8FF3D` | `#4B3BD6` | Links, and the website's price figures |
| `link-hover` | `#E2FF8F` | `#3427B0` | A link when hovered |
| `progress` | `#C8FF3D` | `#5B4BE8` | The fill of a progress bar |
| `running` | `#C8FF3D` | `#3A7D0A` | Running text: a state, a live figure |
| `running-dot` | `#C8FF3D` | `#47A010` | The dot beside a running state |
| `ok` | `#22351A` | `#E6F7D4` | A "done" chip's fill |
| `ok-fg` | `#C8FF3D` | `#2F6B06` | Text on `ok` |
| `warn` | `#3A2214` | `#FFE9DB` | A warning's fill |
| `warn-fg` | `#FFB27F` | `#9A3D06` | Text on `warn`, and a warning line |
| `danger` | `#FF7A7A` | `#C0262D` | Failed, errors, destructive actions |
| `danger-soft` | `#3A1620` | `#FDECEC` | Error banner background |
| `danger-fg` | `#0C0A1F` | `#FFFFFF` | Text on `danger` |
| `bone` | `#221E40` | `#E4E2F0` | A placeholder (skeleton) |
| `bone-shine` | `#2A2650` | `#F4F3FA` | The skeleton's shimmer |
| `night` | `#17142F` | `#17142F` | A night panel: the same in both themes |
| `night-2` | `#221E40` | `#221E40` | A panel inside a night panel |
| `night-line` | `#2A2650` | `#2A2650` | Lines and tracks inside a night panel |
| `on-night` | `#FFFFFF` | `#FFFFFF` | Text on a night surface |
| `on-night-2` | `#B9B5DA` | `#B9B5DA` | Secondary text on a night surface |
| `on-night-3` | `#8F8AB8` | `#8F8AB8` | Labels on a night surface |
| `danger-on-night` | `#FF8A8A` | `#FF8A8A` | An error line on a night panel |
| `brand` | `#8E80FF` | `#5B4BE8` | Labels in the brand's violet, and the wordmark's accent |
| `focus` | `#C8FF3D` | `#5B4BE8` | The 2 px focus ring, offset 2 px |

### Contrast, WCAG 2.2 AA

Every text pair meets 4.5:1 (1.4.3; the 24 px and larger headlines need only 3:1 and are held to 4.5:1 too), and every control outline, focus ring and graphic meets 3:1 (1.4.11). `web/src/design/tokens.test.ts` computes each ratio from the tokens, **in both themes**, and fails if any is below its minimum, or if it differs from this table by more than 0.01. The running dot's day colour is `#47A010`, not the reference's `#57B414`, which was 2.64:1 on white.

| Foreground on background | Night Shift | Day Shift | Minimum |
| --- | --- | --- | --- |
| `text` on `canvas` | 19.47 | 17.66 | 4.5 |
| `text` on `sidebar` | 19.01 | 19.47 | 4.5 |
| `text` on `panel` | 17.83 | 19.47 | 4.5 |
| `text` on `panel-2` | 15.82 | 17.66 | 4.5 |
| `text` on `raised` | 17.01 | 16.67 | 4.5 |
| `text` on `ghost` | 17.01 | 19.47 | 4.5 |
| `text` on `ghost-hover` | 14.70 | 16.67 | 4.5 |
| `text-2` on `canvas` | 9.90 | 8.05 | 4.5 |
| `text-2` on `sidebar` | 9.67 | 8.87 | 4.5 |
| `text-2` on `panel` | 9.07 | 8.87 | 4.5 |
| `text-2` on `panel-2` | 8.05 | 8.05 | 4.5 |
| `text-2` on `raised` | 8.66 | 7.60 | 4.5 |
| `text-3` on `canvas` | 6.02 | 5.86 | 4.5 |
| `text-3` on `sidebar` | 5.88 | 6.46 | 4.5 |
| `text-3` on `panel` | 5.52 | 6.46 | 4.5 |
| `text-3` on `panel-2` | 4.89 | 5.86 | 4.5 |
| `text-3` on `raised` | 5.26 | 5.53 | 4.5 |
| `primary-fg` on `primary` | 16.52 | 16.52 | 4.5 |
| `primary-fg` on `primary-hover` | 17.17 | 12.78 | 4.5 |
| `ink` on `volt` | 16.52 | 16.52 | 4.5 |
| `ink` on `volt-hover` | 17.17 | 17.17 | 4.5 |
| `ink` on `ember` | 7.49 | 7.49 | 4.5 |
| `ink` on `ion` | 6.19 | 6.19 | 4.5 |
| `on-ion-deep` on `ion-deep` | 5.81 | 5.81 | 4.5 |
| `on-ion-deep` on `ion-deep-hover` | 4.55 | 7.43 | 4.5 |
| `link` on `canvas` | 16.52 | 6.58 | 4.5 |
| `link` on `panel` | 15.14 | 7.25 | 4.5 |
| `link-hover` on `canvas` | 17.55 | 9.23 | 4.5 |
| `link-hover` on `panel` | 16.08 | 10.18 | 4.5 |
| `running` on `canvas` | 16.52 | 4.63 | 4.5 |
| `running` on `panel` | 15.14 | 5.10 | 4.5 |
| `running` on `panel-2` | 13.43 | 4.63 | 4.5 |
| `ok-fg` on `ok` | 11.20 | 5.77 | 4.5 |
| `running` on `ok` | 11.20 | 4.52 | 4.5 |
| `warn-fg` on `warn` | 8.41 | 5.89 | 4.5 |
| `warn-fg` on `panel` | 10.13 | 6.90 | 4.5 |
| `danger` on `canvas` | 7.71 | 5.36 | 4.5 |
| `danger` on `panel` | 7.06 | 5.90 | 4.5 |
| `danger` on `panel-2` | 6.26 | 5.36 | 4.5 |
| `danger` on `danger-soft` | 6.32 | 5.17 | 4.5 |
| `danger-fg` on `danger` | 7.71 | 5.90 | 4.5 |
| `on-night` on `night` | 17.83 | 17.83 | 4.5 |
| `on-night` on `night-2` | 15.82 | 15.82 | 4.5 |
| `on-night` on `black` | 21.00 | 21.00 | 4.5 |
| `on-night-2` on `night` | 9.07 | 9.07 | 4.5 |
| `on-night-2` on `night-2` | 8.05 | 8.05 | 4.5 |
| `on-night-2` on `black` | 10.68 | 10.68 | 4.5 |
| `on-night-3` on `night` | 5.52 | 5.52 | 4.5 |
| `on-night-3` on `night-2` | 4.89 | 4.89 | 4.5 |
| `on-night-3` on `black` | 6.50 | 6.50 | 4.5 |
| `volt` on `night` | 15.14 | 15.14 | 4.5 |
| `volt` on `night-2` | 13.43 | 13.43 | 4.5 |
| `ion` on `night` | 5.67 | 5.67 | 4.5 |
| `ion` on `black` | 6.68 | 6.68 | 4.5 |
| `danger-on-night` on `night` | 7.86 | 7.86 | 4.5 |
| `control` on `canvas` | 4.63 | 3.87 | 3 |
| `control` on `panel` | 4.25 | 4.26 | 3 |
| `control` on `panel-2` | 3.76 | 3.87 | 3 |
| `focus` on `canvas` | 16.52 | 5.27 | 3 |
| `focus` on `panel` | 15.14 | 5.81 | 3 |
| `progress` on `line` | 11.95 | 4.88 | 3 |
| `progress` on `panel` | 15.14 | 5.81 | 3 |
| `running-dot` on `canvas` | 16.52 | 3.02 | 3 |
| `running-dot` on `panel` | 15.14 | 3.33 | 3 |
| `running-dot` on `night` | 15.14 | 5.35 | 3 |
| `brand` on `canvas` | 6.19 | 5.27 | 4.5 |
| `brand` on `panel` | 5.67 | 5.81 | 4.5 |

## Shapes and space

- **Panels, depth from colour.** A panel is `panel` (or a colour, or `night`) with 28 px corners and 18 px of padding on the app, 24 to 40 px on the website. Separation comes from the page's colour between panels, and by day from a 1 px `edge`. A 1 px `line` appears only between rows inside a panel. There are no shadows, but the website's hero card's violet glow.
- **Corners:** 14 px for controls and buttons (`rounded-control`; 16 px for the large button, `rounded-control-lg`); 12 px for chips and icon squares (`rounded-icon`); 20 px for panels inside panels and for question rows (`rounded-inner`); 28 px for panels (`rounded-panel`) and the top of a bottom sheet; 32 px for feature panels (`rounded-feature`); 40 px for the big ones, such as the website's hero card and its bands (`rounded-hero`); 22 px for the phone's tab bar (`rounded-bar`); fully round (9999 px) for chips, status pills, progress bars and avatars.
- **A panel inside a panel** (a field, a ghost button, an option, a row that opens) is pressed in with the next step of the panel's colour (`panel-2`); the code marks a panel's inside with `data-tile` and does this once (the `tiled` variant and one nested-panel rule in `styles.css`), so no component needs to know where it sits.
- **The selected option** is a volt panel with a check (`ink` text); the ones not selected are plain panels.
- **Spacing is an 8 px grid:** 8, 16, 24, 32, 40, 48, 56, 64, 72, 80, 96, 112 px, with 4 px for small gaps inside a control or between an icon and its text. Older half steps (6, 10, 12, 14, 18, 20 and others) remain in the scale for the app's tiles until each screen is rebuilt on the grid, and are not used on the website. Named sizes follow.
  - Between sections: 24 to 32 px in the app, 80 to 112 px on the website; between a panel's title and its content: 16 px; between options: 8 px.
- **Heights:** controls at least 44 px (`touch`); fields 56 px; buttons 56 px (`large`: the one at the foot of a flow, 60 px on a laptop).
- **Named sizes:** the finding ring 88 px on a 168 px grey disc (`finding-ring`, `finding`); the log panel at least 384 px (`panel`); Book's price beside its GPU card 112 px on a laptop (`price`), so it never moves as it arrives; the website's hero chart 168 px (`hero-chart`) and its step cards at least 256 px (`step`).
- **Touch targets are at least 44 × 44 px**, even where the visible control is smaller (the hit area is padded).
- **The layout:**

| Width | Layout | Margin |
| --- | --- | --- |
| Up to 767 px (phone) | One column of panels on a two-column grid; a bottom tab bar of the five most used destinations and "More"; Book and the session are full-screen flows with a back button | 14 px (16 on the website) |
| 768 px and up (tablet, small laptop) | The left sidebar; the same panels, wider | 32 px |
| 1200 px and up (laptop) | The sidebar and a twelve-column grid of panels; Book is its questions beside the "Your request" panel; a live session is its night band over the loss chart and the log. The website's frame is 1280 px, centred | 40 px |

- **Navigation:**
  - **Laptop and tablet:** a **left sidebar**, 256 px wide, on `sidebar`: the wordmark at the top, then Home, Book a GPU, Sessions, My files, Hosting and Credit, and the account at the bottom. The current item has the `raised` fill and a 4 px bar in the accent (volt at night, ion-deep by day). The credit is a panel on Home and on Credit, not a chip in a bar.
  - **Phone (under 768 px):** the sidebar becomes a **bottom tab bar** on `panel`, 64 px tall, 14 px from the edges and the bottom, 22 px corners and a 1 px `line`: the five most used destinations and "More", which opens the rest. The current one is in the text colour at weight 600 with the accent bar, the others in `text-2`. Book and the session screen are flows: on a phone they replace the bar with a back button.
  - On a phone, All sessions is a row under Book again on Home, since it has no tab.
  - A machine, its price and limits, its sessions and adding one are flows on a phone, with their own back button; the Hosting tab and its earnings keep the bar.

## Buttons

14 px corners (16 px for a large button), at least 44 px tall (56 px for a large one, 60 px on a laptop), Inter at weight 600.

- **Primary** (`primary`): volt with `ink` text at night; `ink` with volt text by day; weight 700. At most one on a screen.
- **Secondary** (`secondary`): ion-deep with white text, for a weighty second choice ("Stop session"), a band's own button.
- **Ghost** (`ghost`): the `raised` fill at night, white with a 1 px edge by day, for everyday actions. Inside a panel it is pressed in with `panel-2`.
- **Inside a night panel:** the volt (`live`) button with `ink` text, in either theme.
- **Inside a volt tile:** the `ink` button (the page's dark with volt text), in either theme, so the screen's primary is still seen ("Download outputs").
- **Quiet:** a bold text action, like "Change". **Danger:** confirming something destructive, in the one dialog.
- **Chips** are 44 px pills; the chosen one is `primary`. A pressed button that's waiting shows a small spinner inside it.

## Motion

- **Durations:** 120 ms for presses, hovers and toggles; 180 ms for small things appearing (menus, popovers, toasts); 240 ms for large things (sheets, dialogs, page content fading in). Nothing longer, but for the two continuous motions below.
- **One easing family**, a decelerating curve:
  - entering: `cubic-bezier(0.2, 0, 0, 1)`;
  - leaving: `cubic-bezier(0.4, 0, 1, 1)`, at two-thirds of the entering duration.
- **What moves:** opacity and small translations (at most 8 px) of things appearing or leaving; colour on hover and press; the skeleton's shimmer.
- **Tiles that are links lift 2 px on hover (a laptop) and press in to 98% on tap (a phone), at 120 ms** (`tile-motion`). Nothing else moves.
- **The continuous motions** are the two below, both 2 seconds, and the website's marquee, which crosses its text in 60 seconds. All three stop under reduced motion:
  - **the running pulse:** while a session is `RUNNING`, the dot beside its state pulses its opacity;
  - **the finding ring:** while we find a GPU, the large ring breathes (a little smaller and fainter, then back);
  - **the marquee:** the home page's band of words scrolls slowly and stops entirely under `prefers-reduced-motion`. No other decorative motion.
- **What never moves:** money figures (they change in place, never count up), page layout, and anything the person is reading or about to tap.
- **`prefers-reduced-motion: reduce` removes all non-essential motion:** no translations, no lift or press, no shimmer, no pulse. Changes are instant; only focus and state colours remain.
- **No layout shift, ever** (cumulative layout shift 0 on every screen):
  - skeletons have the same size as the content that replaces them;
  - images and icons have fixed dimensions;
  - a font swapping in moves nothing, because its fallback is scaled to its metrics (see Typography);
  - **banners push the content below them down; they never cover it** (the product owner, Oct 8): they're rare and important, and covering would hide what you're reading.

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

Home follows the approved `AppHome` reference. On a phone it is a two-column grid of these tiles:

| Tile | Size | Colour | What it holds |
| --- | --- | --- | --- |
| Running now | 2 × 2 | night | "Running now", what it's doing and on which GPU, the elapsed time at 54 px and the cost in volt; opens the session. One for each live session. |
| The first service | 1 × 2 | ember | The service's icon, its name and what you bring; opens Book at it. |
| Credit | 1 × 1 | ion-deep | "Credit" and the available amount; opens Credit. |
| Book again | 1 × 1 | ion | "Book again" and the last setup ("Fine-tune · 41 min"); opens Book filled in from it. Not there for a new renter. |
| Another service | 2 × 1 | ion, then panel | The service's name, what you bring, its icon; opens Book at it. |
| Earn with your GPU | 2 × 2 | volt | "For gamers", "Your GPU sits idle. Let it earn.", "Rent out your gaming laptop →"; opens Hosting. |

With nothing running, the first tile is "What do you want to run?" with the two services. On a laptop the same tiles sit on four columns; the services come from the catalog, never typed here, so a third service is a third tile.

## Book

Book is the services first ("What do you want to run?"), then each service's own screen, made from its template in the catalog, never written by hand (ADR 0022). Its questions are stacked tiles:
- **Top:** "← All services", the service's name in big type, and a line on what you bring and what you get.
- **One panel for each question:** each input ("1. Your model"), with Upload (a folder or a file, starting as soon as it's chosen and carrying on where it stopped if the same files are chosen again), From My files and, where the template allows, Link; then each plain question: a preset as **selectable tiles**, the selected one volt with a check, with their typical time and "≈ ₹" at today's prices, labelled as typical; a basic setting; or the GPU memory the job needs.
- **Budget:** the template's chips ("Stop at ₹100"), as pills, and "Other amount".
- **Advanced:** one row, closed, naming what's inside, with every default set; it opens by itself when something in it needs fixing.
- **Your request:** the **night panel**: each answer, what you get, the GPU card with how many fit now and the price, the hold, and "Request a GPU" as a volt button inside it. On a laptop it sits beside the questions; on a phone it is the last tile, and the button is held at the foot like the action bar.
- **The GPU card** names the memory the job needs, from the model's size when the template says so ("Your 14.2 GB model needs a 12 GB GPU"). A model too big for any GPU here says how much it needs and that the biggest have 24 GB, and the button stays off; it never says "No GPU free", since waiting won't help.

My files is a list of the renter's uploads (name, size, files, when last used), reached from the account menu and from each input's From My files; Delete asks once more in its row.

## The session screen

The session screen follows the state, as tiles:

| State | What it shows | Its action |
| --- | --- | --- |
| Finding (`REQUESTED`, `MATCHED`) | One big night panel with the ring, breathing; "Finding your GPU", "Usually under a minute"; what's done ("train.jsonl uploaded"); "Nothing is charged until it runs"; and a small panel for what was asked for | Cancel request |
| Found (`PROVISIONING`) | "GPU found", the GPU and its memory, "Starting…" | Cancel request |
| Running (and moving host, saving) | The night panel holds the time and cost (54 px), the state and "of about 45 min"; then tiles for the GPU (use and temperature), the checkpoints, the log (a panel with a monospace body), and Stop. On a laptop they sit in a grid beside the log | Stop session, asking first |
| Done (`COMPLETED`) | The charge (night panel), the outputs (volt, with Download), the rating, "Report a problem" and Run again | Download outputs, then Run again |
| Ended otherwise | Why, in plain words, and what to do; the charge, often "Nothing was charged" | Try again, filled from it |

All sessions is a list of panels, newest first, with a volt dot on the live one.

Raising the cap and sharing a live link are not built yet.

## Credit

The balance as a big night panel (Available, with On hold and Total beside it), and the history as panels grouped by day.

## Hosting

The owner's screens (ADR 0023), for someone who wants to know "is my machine earning, and is it safe?" from their phone. They follow the same pattern: one dark summary tile, then panels.
- **The Hosting tab:** "Earned this week" as the night panel, big, with the total beside "payouts start with the paid pilot" and the last 7 days as bars in volt (today in full, a day with nothing a thin line); their machines, each an ember or panel with its state in words; "Add a machine". With none, what hosting is and that one step.
- **A machine's states,** one at a time, in this order: running a session (the night panel, its dot pulsing, "earning ₹2.73 so far"), paused (until when), update needed, offline, checking (not yet activated), suspended, idle. A paused machine finishes a session it's running, and says so.
- **One machine:** a night panel with its state, name and GPU; then tiles for its temperature and use against the limit its owner set; "Right now", the session running and what it has earned so far; the privacy line ("Renters never see who you are, and their work never touches your files."); rows to its price and limits, the sessions it ran, and renaming or removing it; and at the foot, "Pause hosting" (a choice of how long, opened in place) or "Resume".
- **Money is the server's:** "earned so far" is read again every 5 seconds while a session runs and changes in place; it's never counted up on the client (principle 4). The elapsed time ticks.
- **Price and limits:** the price an hour with − and + in ₹0.60 steps inside a panel, what the owner keeps of it and the 20% fee, worked out from whole paise; the range others charge (or the usual range for a GPU like theirs); the temperature to pause above (60 to 95 °C); the disk to keep free (10 to 500 GB); and a fixed line that it's one session at a time.
- **Removing** asks first in a sheet that names the machine, never a browser dialog; the one other dialog the app has.
- **Add a machine** is "Earn with this laptop" (ADR 0032): what a laptop needs, the download (a disabled "Windows app: coming soon" until the installer exists), and the one command that links it. No code is shown or typed.
- **The link page** (`/link/<id>/`, features/hosting/LinkApproval.tsx): a signed-in screen in a centred tile. "Link this laptop to your account?" over the laptop as it described itself (name, GPU, system, agent version, minutes left), a name field, the price with "You'll earn ₹x an hour (renters pay ₹y)" and Change (the machine's own price form), "Approve and start earning" and "This isn't my laptop". It is drawn whole while loading. Expired, denied and linked are states of the same tile; linked says new laptops are checked before they go live and opens the machine.
- **Words:** here, and only here, a host's owner calls their own host a **machine**. Renters never see "host" or "machine"; owners never see who rented one.

## Account, sign-in and the rest

Sign-in, the invite step, Account, API keys, the notifications list and the CLI approval are panels on the page colour, with one primary action each. The admin console (ADR 0027) stays dense: it takes the fonts, the colours and the radii (and both themes), with smaller type and tighter tables, and a 240 px sidebar with all its sections in four groups (Overview, Safety, Money, The service), items 36 px tall from 768 px. Its headlines are Archivo uppercase at 26 px, its tables sit in a panel with tracked uppercase headings and 8 px of padding in a row, and its pills are `raised`, `ok` or `danger-soft`.

## Laptop layout

From 1200 px every signed-in screen is laid out for the screen, not as the phone's tiles in a column. The approved references are `AppHome` and `Session` (each in both themes); screens that are not drawn follow the same rules. Judged at 1440 × 900.

**The system**
- **Breakpoints:** phone under 768 px; tablet 768 to 1199 px (the laptop's header and tabs, with 6 columns and stacked rails); laptop from 1200 px (`xl:`, `useLaptop()`); a wide step at 1680 px (`2xl:`).
- **Frame and grid** (`components/shell/Layout.tsx`): the content runs the full width of the window, no maximum and no centred column; side padding 14 px on a phone, 32 from 768 px and 40 from 1200 px; 12 columns with a 16 px gap (20 px from 1680 px), 6 columns on a tablet. A screen that fills the window (Hosting) is 100% of the height less 32 px above and 48 px below (`FIT_FRAME`, `fit-screen`).
- **The shell** is the left sidebar (see Navigation): the wordmark, Home, Book a GPU, Sessions, My files, Hosting and Credit, and the account at the bottom, with the credit in a panel on Home and Credit. Appearance (Match my device, Light, Dark) is under Account, in Profile. A phone has the bottom tab bar instead.
- **Rails** (`RailColumns`): a left column of 400 px (320 px for Account) of summary tiles and the rest beside it, stacked on a tablet. The live session, Hosting's pages, Credit and Account use it; the left column's first tile is dark.
- **Tiles hold their space:** no more than about 35% of a tile is empty at 1440 × 900. A tile is as tall as its content, or two tiles share a row's height; one with nothing to say isn't drawn (no free GPUs, a log with under two loss values, a host with no sessions). Figures are tabular and come only from the API; money is `formatPaise`.
- **Rules that stay:** one dark primary button on a screen; touch targets of 44 px or more; no layout shift, so a skeleton is exactly its tile's height; the first screen's requests are only what it needs to paint, and the tiles that need more ask after the paint (`useAfterPaint`).

**Home** (approved `AppHome`), a column of rows under the page padding (32 px above, 48 px below), 24 px apart:

| Row | Holds |
| --- | --- |
| Headline | "Or drop a folder anywhere on this page" over "What do you want to run?" in Archivo uppercase, and "Book a GPU" (`primary`, large) at the end. Dropping a folder anywhere opens the matching service with it |
| Services | One accent card for each service of the catalog, in a row that wraps below 280 px each: the first ember, the second ion, the others panels. Each has its typical time as a tracked label, its name in the title face and what you bring |
| Running and credit | A night panel for each live session ("Running now", the GPU, what it is doing, the time and the cost in volt; it opens the session), and the ion-deep credit panel (available at 52 px, what is on hold and the last charge, "Add credit"); each at least 380 px wide |
| Recent sessions | A panel with a table of the last four: what, GPU, ran, cost and "Run again" or "Open"; "All sessions" in the link colour |
| Last | "GPUs free right now" (each GPU memory class with whether one is free and the live price range, drawn after the first paint; not there when it has nothing), "Book again" (ion; not there for a new renter) and the volt "For gamers" panel |

**The live session** (approved `Session`): a night band across the top with 32 px corners (the state and the epoch in volt, the service's name in Archivo uppercase, when it started and the live status; on the right the time, the cost so far in volt with what it stops at, and "Stop session" in `secondary`). Under it two panels side by side, 580 px at least so nothing under them moves as the log is read: the GPU's panel (its model and memory, and four small tiles: busy, temperature, checkpoints, step) with the training loss under it as 20 bars in ion-deep and the latest in the running colour (drawn only when the log carries two or more loss values); and the log, a panel with a monospace body, Copy and Download. Under them the four steps of the run (uploaded, GPU found, running, outputs), each a 6 px bar, a title and a line. Finding is the night panel with the ring and the request beside it; Done is the charge, the outputs (volt, with Download), the rating and "Run again". Nothing is drawn that the log or the session doesn't carry.

**Hosting**, two equal rows: the night panel of the week (the amount, the total and hours rented, the seven days as bars) over 8 columns and the ember "Add a machine" (what a machine needs, one-time code, about 5 minutes) over 4; then each machine as a 4-column tile (state, temperature and use, name, GPU and price, "Earning now" or Pause or Resume) and "Latest earnings" with the last entries and All. With more than two machines they are a grid of three a row, and the earnings sit under them. Machine, Price, Sessions it ran, Earnings and Add have the 400 px rail with a dark summary tile (the machine's state, name and rating; or the screen's title and what it is) and panels beside it.

**Account**: the 320 px rail holds a night profile panel (an avatar in volt, the name and email), the sections (Profile and notifications, API keys, My files, Reports and the rest) and Sign out. Beside it Profile (name with Edit, email, how they sign in) with Appearance (Match my device, Light or Dark) under it, and Notifications, a table of a kind and two switches (Email, In the app), the kinds about money and safety locked on with their reason. API keys, My files and Reports are tiles of rows in the same frame.

**The other screens**
- **Book:** the full width with 7 columns of questions and, sticky at the top, the dark "Your request" in 5.
- **Sessions:** a full-width table (service, started, time, state, charge) in one panel, a row a link; Active and Ended pills; paging as before.
- **Credit:** the 400 px rail with the dark balance tile (available, on hold, total) and the history beside it, grouped by day.
- **Sign-in:** the sign-in tile in 5 columns and, beside it, a collage in 7 of what the product is (night "Rent a GPU by the minute", volt "Fine-tune a model", ember "Earn with your GPU", ion "A locked box"), saying only what the website says. A small "← idleo" leads back to the website; "Get started" from the website arrives as "Create your account".
- **Notifications, the CLI approval, the invite step:** one panel, 7 columns, centred, never a phone column.
- **The admin console** takes the same tokens, denser: smaller type, tighter tables, and the sidebar with all its sections.

**The website** (ADR 0029) has its own frame, 1280 px wide and centred (see The public pages).

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

- The name, the wordmark, the accent colour and the favicon live in one place, `web/src/brand/`, so the final brand is one small change.
- **The wordmark is the name in lowercase** ("idleo"), in Archivo at width 125% and weight 900 (`text-display-logo`, 28 px): one `Wordmark` component, in the app's sidebar, sign-in, the admin console and the public pages; it comes from `brand.name` and has no image to load. **The ring** (a ring with a small gap at the top, like a power button at rest) is the favicon and the large, breathing ring of "Finding your GPU".
- No stock logos or icons stand in for the brand.

## Icons

One set only: **Lucide**, imported one icon at a time. 20 to 22 px in controls and rows, 16 px inline with text, 30 px on a service's tile, 1.6 to 1.75 px stroke (2 px for chevrons), coloured with the text they sit beside. A service names its icon by a key from a fixed list (`layers`, `code`, `table`, `cube`, `notebook`, `plug`, `gamepad`, `flask`, `pulse`); `src/lib/service-icons.ts` maps each to its Lucide icon. An icon-only button always has a visible tooltip or an accessible label.

## The public pages

The signed-out pages (ADR 0029) follow everything above: the same tokens, type and panels, volt as the one primary (the ink button by day) and one primary action on a page, except the docs, which are a calm reading layout. They differ in three ways: they are their own build at their own address (the website, with the app at another), they are static HTML with no app code, and their budgets are stricter (on Lighthouse's mobile profile, the median of three runs: LCP 1.5 s, TBT 100 ms, CLS 0, accessibility 100, SEO 100; 80 KB for a first load, which does not count the fonts). A live figure has its place on the page at its final size, so loading, loaded, empty, error and stale never move anything. A number is live (from the API) or marked "typical"; nothing is invented.

**The home page** follows the approved reference section by section: the header (wordmark, the pages, "Log in" as a ghost button, "Get started" as the primary); the hero (a kicker chip, the headline in uppercase Archivo, the opening paragraph, the two buttons and the pilot line) beside a preview of a live session in a night card with the violet glow, labelled as an example; the marquee band in ion-deep; three step cards in ember, ion and volt; what you can run, from the catalog, as accent cards; "For builders" (a panel) beside "For gamers" (volt); the black "Sealed, on both sides." band; "Pick a GPU" (a table of the GPU memory classes, their live price after the first paint and "typical" before it); the questions; a closing ion-deep band; and the footer with its columns, the Appearance switch, and the large wordmark. The header's pages, "Log in" and "Get started" and the footer's Terms, Privacy and Contact are on every page. Pricing, GPUs, Earn, Help, Docs, Contact, Terms and Privacy use the same language: an uppercase Archivo headline, panels with 28 px corners, the same buttons.

