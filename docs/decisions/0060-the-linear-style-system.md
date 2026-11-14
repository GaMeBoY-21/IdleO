# 0060. The IdleO system: a Linear-style structure, IdleO's identity

- **Status:** Accepted. Supersedes 0043 (Night Shift).
- **Date:** 2026-11-14

Sources:

- the product owner's design reference, kept in `web/design-reference/linear.md`
- web/DESIGN.md; ADRs 0018 (the web stack and budgets), 0043 (the look this replaces), 0058 (four sites), 0059 (the UI standard)

## Context

Night Shift (ADR 0043) is a deep indigo page with three loud accent colours and a heavy, wide, uppercase headline face. It was drawn for three screens and carried to the rest, and on a dense screen (the console, a table of sessions, a form) its colour and its headlines compete with the data. The product owner has chosen a calmer, denser system for the whole product: Linear's structure (a surface ladder, hairline borders, compact controls, quiet type, one accent), with IdleO's own identity.

This changes how things look and are laid out. How a GPU is requested, how it is matched, what money does, the routes, the copy of the screens and the API stay as they are, and so do the performance and accessibility budgets.

## Decision

**What we take from Linear:** the surface ladder, hairline borders in place of shadows, compact controls, quiet type with negative tracking on headings, and a single accent used sparingly.

**What we do not take:** its name, its fonts, and its lavender (`#5e6ad2`). The accent stays IdleO's volt, `#C8FF3D`, with the canvas colour as the text on it.

**Two themes, one layout.** Dark is the default (a `#08090A` canvas and four surfaces up to `#1C1D1F`); light is the device's choice (white, and the ladder reversed). The Appearance choice and its cookie are unchanged (`web/src/design/theme.ts`). The tokens are in `web/src/design/tokens.ts`: `canvas`, `surface-1` to `surface-4`, `hairline`, `hairline-strong`, `hairline-tertiary`, `control`, `ink`, `ink-muted`, `ink-subtle`, `ink-tertiary`, then `volt` with its hover, pressed and text colours, `live`, `focus`, and the four status colours.

**Volt is used for four things:** the screen's one primary button, the focus ring (2 px, at half strength in the dark theme), the brand mark, and live or running indicators. It is never a background, a card fill or decoration. In the light theme, where volt fails contrast as text and as a ring, `live` and `focus` are a darker volt (`#4A7300`); the primary button is still volt.

**Status colours** (success `#27A644`, warning `#D9A400`, danger `#EB5A5F` in the dark theme and `#C0262D` in the light one, info `#4C8DF6`) are for a chip's dot, small marks and charts; never a fill, except the danger button, which is solid in the confirm dialog only. A status chip is a `surface-2` pill with a dot and words.

**The type** is Inter for everything and JetBrains Mono for ids, logs and code. Archivo is removed, with its three font files, and so are the uppercase headlines. The scale is: display 56, hero 40, page title 28, section 20, card title 16, body 15 (14 in the console), small 13, caption 12, button 14 and the eyebrow 12, with weights from 400 to 600 and negative tracking from 20 px up. Every number is in tabular figures.

**Shape.** A 4 px base. Corners are 6 px on tags, 8 on buttons and inputs, 12 on cards, 16 on big panels, and pills on chips and segmented tabs. Buttons are 32 px tall (44 on a touch screen), with 8 × 14 px of padding. Depth is the ladder and hairlines; the only shadows are a soft one on menus, popovers and dialogs over a scrim, and a faint top-edge highlight on a lifted panel. Motion is 150 ms ease-out, and none under reduced motion.

**Layout.** A 240 px left sidebar (collapsible to icons) and a 48 px top bar; content at most 1200 px on the renter and host sites and full width in the console; a docked bottom tab bar on a phone. Tables have 44 px rows (36 in the console), hairline dividers, sticky headings, and numbers aligned right.

**Four small departures from the reference,** so the contrast table still holds in both themes (WCAG 2.2 AA): the light theme's `ink-subtle` (`#5F646D`) and `ink-tertiary` (`#666B74`) are darker than the reference's `#6B7079`; the dark theme's `ink-tertiary` is `#80858E`, not `#62666D` (3.45:1 on the canvas); `control`, the border of a field or a switch, is `#666B73` (dark) and `#80858E` (light), stronger than a hairline, because a control's edge needs 3:1 (WCAG 1.4.11); and `danger` is lighter in the dark theme (`#EB5A5F`, not `#E5484D`, which is 4.31:1 on the selected-row surface) and `#C0262D` in the light one.

**Where it applies,** in six parts, each its own pull request: the foundation (tokens, fonts, every shared component, the gallery, DESIGN.md); the console; the renter app; the host site; the website; and everything outside the browser apps (emails, PDFs, the Windows setup window and tray, the error pages).

**The budgets don't move:** first-load JavaScript of 150 KB (the host site and the console have their own, ADR 0058), LCP, CLS 0, axe 0 violations, accessibility 100.

## Alternatives considered

- **Keep Night Shift and tune it.** Its structure (loud fills, panels told apart by colour) is the problem on dense screens, not its values.
- **Adopt Linear's lavender or its fonts.** The reference is a structure to borrow, not an identity; volt is already the product's mark.
- **Keep the old token names and change only their values.** `panel`, `raised` and `text-2` would then hold things their names no longer say. The rename was one mechanical pass (`panel` to `surface-1`, `panel-2` to `surface-2`, `raised` to `surface-3`, `line` and `edge` to `hairline`, `text` to `ink`, `text-2` to `ink-subtle`, `text-3` to `ink-tertiary`, `primary` to `volt`, and `running`, `progress` and `brand` to `live`).
- **Use the reference's greys exactly.** Three of them fail the contrast table; the product's accessibility budget is not ours to loosen.

## Consequences

- Every screen is on the new tokens and components at once. Screens that built their own coloured fills (the service cards, the credit card, the website's panels) lose them; colour never fills a card again.
- Screenshot baselines of every screen change, and are accepted in one step.
- A page that sets a colour in a raw value is refused by the lint rule, as before; a pair of colours that fails contrast in either theme fails the unit test.
- The Windows setup window, emails and PDFs still carry Night Shift until their own part lands (the last in the list above), and the website follows in its own.
