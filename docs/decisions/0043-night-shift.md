# 0043. Night Shift: a deep indigo look in two themes, Archivo and Inter, and a sidebar

- **Status:** Accepted. Supersedes 0031 (the Bento look).
- **Date:** 2026-10-27

Sources:
- the product owner's approved references: `Main`, `AppHome` and `Session`, each in a dark and a light form
- web/DESIGN.md; ADRs 0018 (the web stack and budgets), 0027 (the admin console), 0029 (the public site), 0031 (the look this replaces)

## Context

The Bento look (ADR 0031, Oct 16) is a light grey page of white tiles in a friendly rounded face. The product owner has chosen a different one for the whole product: **Night Shift**, a deep indigo page with panels a step lighter, three loud accent colours, and a heavy, wide, often uppercase headline face. It is drawn for the website's home page, the signed-in Home and the live session, each in a dark and a light form, and it asks for a left sidebar in place of the header with tabs.

This changes how things look and are laid out. How a GPU is requested, how it is matched, what money does, the routes, the copy of the app's screens and the API stay as they are, and so do the performance and accessibility budgets.

## Decision

**Two themes, one layout.** Night Shift (dark) is the default; Day Shift (light) has the same layout. Every colour token has a dark and a light value in `web/src/design/tokens.ts`, switched by one attribute (`data-theme`) on `<html>`, or by the device's setting when there is none; with no setting at all, dark. A person can choose System, Light or Dark: the choice is a cookie on the parent domain, so the website and the app, which are at different addresses of one domain, share it on the same browser. It is never a server setting. A tiny inline script reads it before the first paint, so there is no flash; it is 127 bytes, 3 more than the script it replaces. The contrast test checks both themes.

**The colours** are the vocabulary of the tokens, named for what they are: surfaces (`canvas`, `sidebar`, `panel`, `panel-2`, `raised`, `line`), three tones of text, the accents (`volt`, `ember`, `ion`, `ion-deep`) and the roles that differ by theme (`primary`, `link`, `running`, the chips). Volt is the one primary and the live numbers at night. By day it is never text on a light background, because it fails contrast; there it is a fill under dark text, and links and progress are ion-deep. `night*` tokens, which are the same in both themes, are for the always-dark preview card and the "sealed" band on the website; a `night` panel carries the dark colours for everything inside it. Old names were mapped one for one in a single mechanical rename (`ground` to `canvas`, `fill` to `panel`, `divider` to `line`, `text-muted` to `text-2`, `dark*` to `night` and `on-night*`, `lime`, `lavender` and `apricot` to `volt`, `ion` and `ember`, `rounded-tile` to `rounded-panel`), and the old `secondary` button is now `ghost`, which is its role.

**The fonts** replace Plus Jakarta Sans: **Archivo** for headlines, as two static instances (width 125% at weight 900, 10.7 KB; width 112% at weight 800, 11.7 KB), **Inter** as one variable file for 400 to 700 (26.2 KB), and **JetBrains Mono** at 400 (18.7 KB) for logs; all subsetted to Latin and the rupee sign, with their OFL licences. They swap (`font-display: swap`) over an Arial fallback scaled to each face's metrics, which the tokens generate, so a swap moves nothing. Pages preload only Inter and Archivo at 125% / 900. Plus Jakarta Sans stays only where the Windows setup app and the invoices still embed it, and goes when they move.

**Shape.** Corners are 14 px on controls, 20 on inner panels, 28 on panels, 32 on feature panels and 40 on the big ones; spacing is on an 8 px grid; depth comes from colour, and the one shadow is the hero card's violet glow. The only continuous decorative motion beyond the pulse and the finding ring is the website's slow marquee, and it stops under reduced motion.

**Navigation** is a left sidebar (the wordmark, Home, Book a GPU, Sessions, My files, Hosting, Credit, and the account), which becomes a bottom tab bar of the five most used items and "More" under 768 px. The phone stays first-class.

**Where it applies,** in order: the tokens, fonts, shared components and the website; the signed-in app; the admin console, denser; the Windows setup window, the tray icon, emails, invoices and brand assets.

**The budgets don't move:** first load of 150 KB of JavaScript, LCP of 2.0 s for the app and 1.5 s for public pages, CLS 0, axe 0 violations, accessibility 100. If the look cannot meet one, the work stops and reports the numbers.

## Alternatives considered

- **Keep the token names and change only their values.** It would have left `lime` holding volt and `ground` holding a panel, and every later screen would have been written against names that lie. A mechanical rename once is cheaper than that, forever.
- **Follow the device only, with no choice.** The product owner asked for a choice, shared between the website and the app.
- **Store the choice in `localStorage`.** The website and the app are at different origins, so it would not be shared.
- **Keep `font-display: optional`** (as ADR 0031 did). With three faces, a slow first visit would show the fallback for the whole page; with metrics-matched fallbacks `swap` costs no layout shift and every face still shows.
- **Ship Archivo's whole variable font.** It is far larger than the two instances the design uses.

## Consequences

- The app's screens are on the new tokens and components straight away but keep their old arrangement until each is rebuilt against the references; the old arrangement's description in DESIGN.md is marked as such.
- Screenshot baselines of every screen change, and are accepted in one step.
- A page that sets a colour in a raw value is refused by the lint rule, as before; a pair of colours that fails contrast in either theme fails the unit test.
- The running dot's day colour is `#47A010`, not the reference's `#57B414`, which is 2.64:1 on white, below the 3:1 a graphic needs.
