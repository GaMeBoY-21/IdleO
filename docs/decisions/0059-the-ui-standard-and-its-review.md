# 0059. The UI standard, and the review that checks it

- **Status:** Accepted
- **Date:** 2026-11-12

## Context

Four sites (ADR 0058) share one design language (web/DESIGN.md, ADR 0043), but each screen was built in its own round, and the ways of looking at them grew one at a time: the console's contact sheet (ADR 0057: admin screens at 1440 px and a few at 390), `pnpm gallery` (the renter's screens at 390 and 1280 px), and `pnpm screenshots` (the website, the app and the console at 1440 and 390 px, with the non-web pictures). None showed every screen at the widths people use (768 and 1024 px were never pictured), none showed what a screen looks like with long names or many rows, and none said what was wrong: a person had to see it. The shared components had no page of their own, so a pattern drifted screen by screen (the console's pills beside the renter's state chips; two ways of showing an empty list).

The product owner set one standard for every screen, in both themes, at 1440, 1280, 1024, 768 and 390 px: spacing on the grid, one page header pattern, type only from the scale, money and times one way, one state map, feedback within 100 ms, every list's four states designed, forms with labels and checks on blur, motion in 150 to 200 ms on one curve and none under reduced motion, breadcrumbs, tab titles, and one verb per action.

## Decision

- **The UI review** (`web/e2e/ui-review.ts`, `pnpm ui-review`, `.github/workflows/ui-review.yml` by hand) pictures every page of every site in six states of its data, made from the sites' fixtures in one place (`e2e/ui-review.spec.ts`: full, loading, error, empty, long text, many rows), and every state the screens specs draw, at the five widths in both themes, into one page per site. It never gates: it reports.
- **Its checks** (`e2e/ui-audit.ts`) read each picture's page for what a machine can see breaks the standard, with a severity: P0 broken or unusable, P1 inconsistent or confusing, P2 polish. A person's findings go in `private/ui-review/findings.md`, one line each with the site, screen, width or theme, severity, rule and picture.
- **The component gallery** (`/_gallery` on each development server, `src/gallery/`) shows every shared component in every variant and state, in both themes. It's a development server's alone: no build holds it, and `scripts/site-modules.ts` fails one that does.
- The review replaces the contact sheet, `pnpm gallery` and the web groups of `pnpm screenshots`, which keeps what isn't a web page (the Windows setup window, emails, PDFs, the tray).

## Alternatives considered

- **Keep the three and add the missing widths to each:** three lists of screens to keep in step, and still no states but the hand-made ones.
- **Visual baselines as the check:** they say a picture changed, not that it's wrong, and they're off while the design settles (web/README.md "Screenshots").
- **Gate on the audit:** its checks are heuristics (a number in a table, a word in capitals); a false alarm would block a merge. The specs gate; the review informs.
- **A Storybook:** another tool and build for what one page on the development server does.

## Consequences

- A screen's states come from one transform of the fixtures, so a new screen in a site's screen list is reviewed in all six without being listed.
- A full run is about 9,000 pictures and four hours on a laptop; the workflow runs one job per width and theme.
- Open question: whether some checks (sideways scrolling, layout shift, the page's one h1) should become gates once every screen passes them.
