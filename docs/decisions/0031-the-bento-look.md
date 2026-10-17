# 0031. The Bento look: tiles on a light page, Plus Jakarta Sans, and one set of tokens for the app, the admin console and the public site

- **Status:** Accepted
- **Date:** 2026-10-16

Sources:
- docs/design/bento/home-phone.html, the approved Home on a phone (Nikki's design canvas)
- web/DESIGN.md; ADRs 0018 (the web stack and budgets), 0021 (the ride-app look, superseded here, and its behaviour, kept), 0027 (the admin console), 0029 (the public site)
- docs/prompts/bento-look.md

## Context

The ride-app look (ADR 0021, Oct 8) is black and white with grey fills, one question to a screen and a list of rows. Nikki reviewed it against other designs on his canvas and chose a different one on Oct 16: **Bento**. Home becomes a grid of tiles of different sizes, each one a thing to do or to check (what's running, what you have, what you can start), on a light page, with soft colour tiles, very rounded corners and heavy, friendly type.

The choice is about how things look and how screens are laid out. How a GPU is requested, how it is matched, what money does, the routes and the API stay as they are.

## Decision

**The look** (web/DESIGN.md, rewritten) is one idea, applied everywhere:
- **A light grey page, white tiles on it, no borders.** Tiles have 26 px corners and 18 px of padding; the grid is two columns on a phone (rows of 88 px, a 10 px gap) and four on a laptop (a 14 px gap). Separation is the page's colour; a very soft shadow appears only where a tile floats (the phone's tab bar, menus, sheets).
- **One dark tile, three colour tiles.** The dark tile is what matters now (what's running, the charge, the request) with lime as its accent. Lime is the main action and the selected option, lavender is history, apricot is hosting and earning. The colour tiles keep their colours and their dark text in the dark theme too.
- **Plus Jakarta Sans**, self-hosted as one 17.8 KB subset (weights 400 to 800; it replaces the 25.5 KB Inter file), `font-display: optional` over an Arial fallback scaled to its metrics. Headings and figures are weight 800 with tight spacing; money and times use tabular figures. Sizes: tile titles 20 px, labels 13, big numbers 54.
- **Navigation:** on a phone, a floating white tab bar (Home, Book, Hosting, Credit; 22 px corners, 14 px from the edges) with the bell and the avatar in the header; on a laptop, the same four as white pill tabs on the page colour.
- **Buttons** are round: the primary is the dark tile's colour (light in the dark theme), the secondary a white tile, and inside the dark tile a lime button.
- **Motion:** a tile that is a link lifts 2 px on hover and presses in on tap, in 120 ms, and does neither under reduced motion. Nothing else moves.

**Tokens, not a new system.** The token names stay (`ground`, `fill`, `text`, `primary`...) with new values, so every screen picks the look up from `web/src/design/tokens.ts` at once. `ground` is now the page, and `fill` a tile, which is lighter than the page in both themes, so a field inside a tile is filled with `ground` and looks pressed in. New tokens: `dark`, `dark-fg`, `dark-muted`, `live`, `lime`, `lavender`, `apricot` (each with its text colour), `on-colour`, and `bone` for placeholders. `inverse-*` became `dark-*` and `live`, the dark log panel's colours are gone (the log is a white tile with a monospace body), and the radius `panel` is `tile`. Every colour pair is checked for WCAG 2.2 AA in both themes by `tokens.test.ts`, and the type scale has two more steps (`figure`, 24 px, and `headline`, 26 px) that the reference uses.

**Where it applies.** The app's screens, in two rounds: first the tokens, the font, the shared components, Home, Book, Finding, the live session, after it ends and All sessions; then Credit, Hosting, sign-in and invite, Account, API keys, notifications and the CLI approval. The admin console keeps its density and takes the font, the colours and the radii only. The public site gets a bento landing page and live price tiles, and its docs a calm reading layout without tiles.

**The budgets don't move.** The speed budgets (LCP 2.0 s for app screens and 1.5 s for public pages, CLS 0, TBT, 150 KB of first-load JavaScript, accessibility 100) hold; the restyle is CSS, and the font file is smaller than the one it replaces.

## Alternatives considered

- **Keep the ride-app look and add tiles to Home only.** Lost: Nikki chose the look for the whole product; two looks in one app would be the worst of both.
- **A second token set beside the old one while screens move over.** Lost: two sets of colours in the codebase, and a screen half in each. Remapping the existing names moves every screen at once and leaves the rest to layout.
- **A tile for everything in the admin console.** Lost: the console is dense on purpose (ADR 0027); an operator reading a table gains nothing from tiles.
- **Inter, kept.** Lost: the reference's weight 800 and its rounder shapes are the look; Inter at 800 reads harder and its file is larger.
- **The dark log panel, kept.** Lost: the log is one tile among tiles; a black rectangle on a light page was the loudest thing on the session screen.

## Consequences

- Every screen reads as one family from the tokens; a new screen is made of the same tiles.
- The first-load JavaScript was 145.0 KB of 150 at the start of this round, so the restyle is CSS and shared components only; no new library.
- The old look's reference screens in docs/design/redesign/ and docs/design/hosting/ are history; docs/design/bento/ is the reference.
- Screenshot tests stay off for this round; Nikki judges by looks on the local stack.
- Open question: whether the colour tiles should change with a service (a third service, a fourth colour) or stay lime for the main action; the reference has one main action.

## Addendum (Oct 17): the laptop layout

The Bento look on a laptop was first the phone's tiles in a centred column. It is now laid out for the screen (the four approved designs in `docs/design/bento/laptop/`, web/DESIGN.md "Laptop layout"):

- **Full width, a 12-column grid.** No maximum width: 32 px of side padding (40 px from 1680 px) and a 16 px gap (20 px from 1680 px); 6 columns on a tablet (768 to 1199 px); the laptop layout starts at 1200 px, the 2xl step at 1680 px.
- **One header on every screen from 768 px:** a 76 px bar of three zones (the wordmark; one white pill bar of five tabs, Home, Book, Sessions, Hosting, Credit; the credit chip, the bell and the avatar). The avatar is a link to Account (a lime ring when it is the current page), and what the account menu held (My files, the theme, Sign out) is in Account's rail on a laptop. The menu stays on a phone.
- **Rails.** The live session, Hosting's pages and Credit have a 400 px left column of summary tiles with the rest beside it; Account's is 320 px. Home, the live session and Hosting fill the screen without scrolling at 1440 × 900.
- **Tiles hold their space:** no more than about 35% empty at 1440 × 900, which is how the screens were judged; a tile whose figure doesn't exist yet (no free GPUs, a log with fewer than two loss values) is not drawn rather than drawn empty.
- **Home** gets two tiles that ask the API after the first paint and hold their height: what is free now, and the latest sessions. **The live session** reads its steps and its loss from the log (`src/lib/log-signals.ts`) and shows nothing it can't read. Dropping a folder anywhere on Home opens the matching service with it as its first upload.
- **Book again and Run again** offer only services that are offered (the test jobs are `hidden` in the development catalog), and a long file name may wrap after a "/".
- **The website and the app** have their own addresses: ADR 0029's addendum.
- **The budgets don't move** (LCP 2.0 s for the app, 1.5 s for public pages, CLS 0, 150 KB of first-load JavaScript); the new tiles and charts are loaded after the first paint, and the screens' own chunks stay out of the first load.

