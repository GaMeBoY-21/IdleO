# 0052. Finding things in the console

- **Status:** Accepted
- **Date:** 2026-11-05

Sources:
- ADR 0051 (permissions, masking, step-up, approvals, the access log), which this builds on
- ADRs 0037 (personal data), 0039 and 0040 (money in and out)
- docs/runbooks/admin.md

## Context

An admin answering one question opened five screens and searched each by hand. The console needs one way to find anything, one page for each thing that matters, one history, and somewhere to write down what was learned, on top of what ADR 0051 built: permissions, masking, step-up, approvals and the access log. No module may read another's tables.

## Decision

**A `console` module, with small interfaces in the shared kernel.** A module contributes by declaring a bean of `AdminSearch`, `AdminTimeline`, `AccountLinks` or `AdminSubject`, each reading its own tables. The interfaces sit in the kernel, not in `console`, so identity can contribute without a cycle (`console` depends on identity's public API). `ConsoleFanOut` asks them all at the same time on virtual threads, each within `console.search.provider-timeout` (500 ms): one that's slow costs its own part, and the answer names what didn't answer.

**One search box** (`GET /admin/v1/search`, Cmd-K or Ctrl-K). It finds users, hosts, sessions, Razorpay payments, orders and refunds, payouts, invoices, tickets, image digests, UPI IDs and PANs.
- Personal values match exactly after normalising: an email or a UPI ID lowercased, a PAN uppercased. A part finds nothing, so nobody can walk a table a letter at a time.
- Names match by trigram (`pg_trgm`), from 3 characters.
- A PAN is sealed, so it's found by `pan_lookup`, an HMAC under a key derived from its sealing key. `PanLookups` fills rows from before it, or from the release before (ADR 0046).
- Each kind has a read permission. Its provider isn't asked unless the admin holds it: a Support admin can't learn that a payout id exists.
- Hits go out through the same masking as every admin response.
- Each search is an access-log entry (`search`), with the query masked by `PersonalDataMask.query`. It doesn't count towards the insider alert for reveals. Searches are limited per admin, `limits.admin-searches-per-minute`.
- **Phones aren't searchable, because IdleO keeps none.** The migration that took top-ups through Razorpay decided not to keep the payer's phone, and nothing else asks for one.

**The palette's actions** are filtered by permission and run the page's own handler, so they open the same action sheet with its reason, step-up and approval.

**One timeline** (`GET /admin/v1/timeline`), the one component on every record's page. Each module gives its events; the controller merges them, newest first, with a cursor of an instant and an event's key. An admin only sees the kinds they may read, and asking for another by name changes nothing.

**One page for each user, host, session and payment**, each tab read through the module that owns it. Opening a host, session or payment writes the access log with its person's id, as a user's page already did. Every id in the console links to its page (`links.ts`).

**Notes and tags**, in the `console` schema. A note is Markdown-lite rendered as text, never HTML; its author can change it for 10 minutes; mentions email a link to the page, never the note's words. Tags come from a vocabulary the Owner manages, each with a design-system colour. Every change is audited in its transaction.

**A note about a person is their personal data.** `AdminSubject` says who a host, session or payment is about, so a note on one is in that person's export and goes when they do. LEGAL(confirm): the export includes notes and tags, except a note written for a fraud investigation while it's open.

**One list engine** (`AdminListing`, in the kernel). A list is declared once by its module: its rows, filters, orders and CSV. Its endpoint and its export run the same query, so a file is exactly what the screen shows. Paging is by keyset, the sort's value and the row's id, and a cursor belongs to one order. Users, hosts, sessions, payments, refunds, invoices, payouts, tickets, reports, abuse, images, chargebacks, clawbacks, ratings and invites are on it. The tickets list was the one offset left; it's gone.
- Three lists keep their own query and a fixed order, with a cursor: the audit log, which has its own search and CSV (ADR 0051); approvals, whose "mine" depends on who's asking; and review minutes, a queue with a three-part key.
- A list's filters, order and hidden columns live in its address, so any view can be linked. A saved view (`console.saved_views`) is a name for that address, private or shared. Opening one still asks the server.
- A tag filters any list: the module asks `AdminTagged` for what carries it, by the person where the row is someone's.

**CSV of any list** (`POST /admin/v1/exports`). It needs `export.create`, which is sensitive so a recent factor comes with it, and the list's own read permission. A list holding personal data goes through approval (ADR 0051), and its requester fetches the file once. Every value is masked, and a cell can't become a spreadsheet formula. It's written row by row up to 50,000, and a last line says how many were left out. Each is in the access log (`export`) and the audit log.

**Linked accounts** (`trust.read`: the Owner and Trust & Safety), worked out on the read by the module that holds each signal, which hands over who matched, how strong it is and masked evidence, never the raw value. Nothing acts on them.

| Signal | Strength | From |
|---|---|---|
| The same browser from the same address | Strong | identity (`security_events.device`, a hash of the user agent, from this release on) |
| The same network (/24) within 24 hours | Weak | identity |
| The same email without its dots and +tags | Strong | identity (`users.email_canonical`) |
| The same PAN | Strong | payouts (`pan_lookup`) |
| The same UPI ID to be paid at | Strong | payouts |

**Left out: the same card or UPI ID paying.** We keep Razorpay's ids and not its payload (ADR 0039), and Razorpay gives no card fingerprint on a payment. Keeping a hash of the payer's UPI ID would be a new kind of personal data, for the product owner to decide.

**Left out of a host's page: presence over time.** It isn't kept: it shows when the agent was last seen, and its changes of status. Its score is kept a day at a time from now on, for 180 days (`hosts.host_score_history`).

## Alternatives considered

- **A search index beside the database** (OpenSearch, or a table every module writes to). One more thing to run and keep in step, and a table of everyone's personal values in one place. Each module's own indexes are enough at this size, and the budgets are measured.
- **One SQL query across schemas.** Fast and simple, and it breaks the rule that no module reads another's tables.
- **Searching every PAN by opening it.** It would open every sealed PAN on every search.
- **Offering the interfaces from `console`.** identity would then depend on `console`, which depends on identity.

## Consequences

- Adding a kind of thing to search or the timeline is one bean in the module that owns it, and the masking and matrix tests find any new endpoint by themselves.
- `pg_trgm` is a trusted extension, so the database's owner can create it, on CloudNativePG too.
- The search box writes an access-log row per pause in typing. It's the price of being able to say who looked for whom.
