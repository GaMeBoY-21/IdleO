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

### The test clock

A whole stack can have its time moved, so the things that only happen with time can be seen happening: a month closes, a target runs out, a strike stops counting. The kernel's `clock` bean reads an offset from the file `e2e.clock-file` names (`OffsetFileClock`, re-read every 100 ms). It's a file and not an endpoint, so nothing on the network can move time. It's a setting and not a code path, and `ProductionSettingsGuard` refuses it.

ID tokens keep the real clock (`IdTokenClock`) while it's set: the sign-in server stamps its tokens with the real time, so against a moved clock every token would look expired. Everything else moves together, TOTP included, so the suite's own code generators read the same file.

**Alternative rejected:** a test-only endpoint to set the time. It would be one more thing a mistaken deployment could expose.

With it, month close runs on **the month the money moved in**: the clock moves to the 2nd of next month, Finance reconciles and makes the CA pack, and the Owner closes it. Before this the suite could only close last month, which had nothing in it.

### Coverage

Every admin `POST`, `PUT` and `DELETE` in the permission matrix has been pressed from a screen by some browser test. `signIn` records every admin write a page sends; `scripts/admin-ui-coverage.ts` compares them with the matrix and fails, listing what's missing, in the web browser run's result. Only a page's own requests count, so `admin-call.py` from a script proves nothing here. A write no other spec reaches is a row of `admin-actions.spec.ts`: 101 of 101.

The check found four writes with no button at all, and they have one now: assigning an inbox item, changing a macro, defining a tag, and erasing under a legal order.

Coverage says a button sends its request. What the request then does is the control plane's tests and the journeys'.

### Role journeys

`admin-roles.spec.ts` is a realistic day for each role against the real stack. CI runs it as four e2e jobs, each a stack of its own with only what its journeys need, so none waits on another's 30-second factor steps: the desk (Support, the Auditor), safety, operations, and the money (Finance and the Owner, after an earning this week):

- **Support:** the inbox, a macro, a reveal with a reason, and a target running out when the clock moves three days.
- **Trust & Safety:** linked accounts, the ladder, and an appeal the admin who enforced can't decide while another can. Six months on, the strike has stopped counting.
- **Operations:** a rollout widened with approval and paused; maintenance mode.
- **Auditor:** reads what they may, and the audit chain holds. `admin-writes-refused.py` then sends every write in the matrix as an Auditor: each must be refused.
- **Finance:** reconciliation, a payout batch and the CA pack, at the start of next month.
- **Owner:** an approval, a kill switch, and closing the month.

### The contact sheet

`.github/workflows/contact-sheet.yml`, by hand only: every screen in its main states and both themes at 1440 px, the phone-ready ones at 390 px, on one `index.html`. It's for a person to look through, and checks nothing the browser run doesn't.

## Consequences

- A new `AdminListing` has to decide about bulk; a new code has to have words; a new admin write has to be pressed from a screen; a new screen has to be in `src/screens.ts`, its runbook in `runbooks.ts`. Each fails a test otherwise.
- Local browser specs must run against `scripts/serve.ts`: `vite preview` serves no prerendered pages, which hid `/admin/approvals/` and `/admin/security/` hydrating another screen's page. That was a real fault for anyone opening those addresses directly, found by this round's tests.
- The first paint budget is measured following a link. An admin session lives only in the page's memory, so a hard load asks for a code first, which no budget could hold.
- Bulk from a list covers 16 of the 43 lists. The other 27 are logs, money rows, access and decisions made one at a time, and each says why.
- A booking names memory, not a GPU model, so demand is by class (ADR 0056's addendum).
