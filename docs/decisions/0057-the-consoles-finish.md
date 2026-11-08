# 0057. The console's finish

- **Status:** Accepted
- **Date:** 2026-11-08

## Context

The console (ADR 0027, 0051 to 0056) can do the whole back office, but each screen grew its own way of doing some of it. Keys worked only in the inbox, bulk actions only on the inbox, a table was as wide and as ordered as its screen wrote it, codes showed as they were stored, and some admin actions had never been pressed from the screen by any test. This round adds no area: it makes the console fast, consistent and proven.

## Decision

### Keys

`KeysProvider` holds one handler. `g` then a letter goes to the inbox, users, hosts, sessions, money, the control room or approvals; `/` searches the list you're on; `?` opens the one panel with every key on this screen and its runbook. A list's keys (j, k, x, Shift-x, Enter, r) belong to `Table`, and act on the table last touched or else the first on the page. A screen adds its own with `useScreenKeys`. Nothing fires while typing or while a sheet is open. Focus is visible in both themes, and a sheet traps it and gives it back: Radix drops it on the page when there's no `Dialog.Trigger`, so `Sheet` remembers where it came from.

### Tables

One `Table`, so every list has the same things:

- columns that resize, reorder and pin, and a row density, remembered per admin on the server (`console.table_settings`), so they follow a person between browsers. Which columns show stays in the address, with the rest of a view;
- a sticky header, since a long table scrolls inside itself;
- multi-sort: Shift-click adds a column, up to three. `AdminListing` keeps paging by keyset, spelling out the comparison column by column when directions differ. A single sort keeps its row comparison, which its index can answer;
- an id copied with one click;
- windowing from 100 rows, with `@tanstack/react-virtual`. Measured: 10,000 rows, 250 frames, the worst 29 ms against a budget of 50.

A person's mouse resizes on the header's edge; the same is in the Columns menu for a keyboard.

### Bulk from any list

The inbox's engine now runs on any list's rows. A list declares `.bulk(kind, subject, person, title, actions...)` or `.noBulk(why)`, and won't build without one, so a new list has to decide. 16 lists allow something; 27 say why they don't, in words the API returns. The preview, the typed count, the approval above 20, the 10-minute undo and the per-item outcome are all the one engine's.

Outside the inbox, a bulk action can only tag or warn: a tag is reversible and moves nothing, and a warning is the ladder's first step. Lists of money rows either tag or have nothing. `bulk_items` loses its foreign key to the inbox's items, since an item can be a row of any list.

**Alternative rejected:** a bulk endpoint per list. It would have meant a preview and an undo per list, which is how they'd have drifted.

### One look

`Sheet` is the only thing that opens a dialog; `Problem` the only way to show a refusal; `QueryView`, `PagedView` and `Empty` the only states. `idleo/admin-one-look` fails lint otherwise, with a test of its own. No code reaches a screen as it is stored: `word` holds words for every code the database's own rules allow (a test reads the migrations and the ledger's accounts), and a code from outside, such as a bank's reason, comes out in plain words. Every console browser test then reads the page's text and fails on a code it finds.

### Budgets

The admin chunk never reaches an app screen (`admin-first-load.spec.ts`, beside `scripts/first-load.ts`). On the seeded data, each screen paints within 1 s of following its link and a sort or filter changes within 200 ms, measured in the page by frame (`scripts/e2e/console-budgets.sh`, weekly). A hard load isn't what's measured: an admin session lives only in the page's memory, so reloading asks for a code again, and a person moves between screens by link.

### Coverage and the test clock

See below; the proof is what lets the rest of this be trusted.
