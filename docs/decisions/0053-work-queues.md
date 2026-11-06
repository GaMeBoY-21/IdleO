# 0053. Work queues: one inbox, claims, timers, priority, macros, bulk actions, an enforcement ladder and appeals

- **Status:** Accepted
- **Date:** 2026-11-05

Sources:
- ADR 0051 (permissions, step-up, approvals, audit) and ADR 0052 (the palette, record pages, the list engine), which this builds on
- ADR 0037 (personal data), ADR 0041 (payouts held for host misconduct)
- docs/runbooks/breaching-chargeback.md, handling-an-appeal.md, writing-a-macro.md

## Context

Work waiting for an admin lived on a dozen screens, with no one place to see what's next, who's on it or what's about to be late. Two admins could answer the same ticket. Some deadlines are legal or are Razorpay's, and nothing watched them together. Sanctions were one switch with no record of steps, no template citing the Terms, and no way for a person to appeal.

## Decision

### Providers

**Each module says what's open, through one kernel interface** (`WorkQueue`), reading its own tables: review minutes, abuse, reports, chargebacks, tickets and grievances, PAN and UPI verification, data requests (privacy tickets, deletions, exports), approvals and appeals. There are no image appeals yet, so no provider for them.

**The `queues` module keeps a projection** (`queues.items`): who's on each item, its timers and its priority. A pass every 30 s (`QueueSync`, one instance at a time) brings it up to date, and an action refreshes its own item at once. What's gone from a module's answer is resolved. A module that fails its query changes nothing: it's never read as empty.

**Why a projection, not a query across modules.** The inbox sorts by priority and due time across every kind of work, within budget at 50k items. That can't be a fan-out at read time, and modules don't read each other's tables.

**An item's version moves only when an admin changes it,** with no trigger, unlike the other versioned records. A timer moving on must not make someone's open page stale.

**Each kind keeps its own detail screen.** Decisions stay in the module that owns them.

### Claiming

A claim is a conditional update: of two at once, exactly one wins, and the other is told who has it. A claim with no action for 30 minutes is released by itself, audited. Activity on the item's own screen counts, where the module reports it. Reassigning needs `queues.assign` and is audited. Every action takes A1's `If-Match` and `Idempotency-Key`.

### Timers

Targets are per kind, in `queues.sla_targets`, changed by the Owner (`queues.sla`):

| Target | First reply | Resolve | Pauses |
|---|---|---|---|
| `grievance` | 24 h | 15 days (IT Rules 2021; LEGAL(confirm)) | No |
| `ticket` | 24 h | none | While waiting on the person |
| `chargeback` | none | Razorpay's own evidence deadline | No |
| `abuse-running` | none | 15 min | No |
| `abuse` | none | 24 h | While waiting on the person |
| `report` | none | 24 h (no target was set: our default) | While waiting on the person |
| `data-request` | none | 30 days (DPDP Rules; LEGAL(confirm)) | No |
| `verification` | none | 48 h | While waiting on the person |
| `review-minute` | none | 24 h | No |
| `approval` | none | when the request lapses | No |
| `appeal` | none | 7 days (no target was set: our default) | No |

"Breaching soon" is the last quarter of a target, or the 48 hours before Razorpay's date for a chargeback. A breach is a daily-digest alert, except a chargeback, which pages. Prometheus has open items, the oldest's age, those past target, breaches, time to first reply and time to resolve, by kind; the `Inbox` dashboard shows them.

### Priority

A score: age against the target (up to 200), money at stake (10 to 50), a running session affected (100), `vip` (50), `fraud-suspect` (75). It reads as urgent, high, normal or low. An admin can override it, with a reason. The list sorts by one stored number, priority and then the soonest due, so it pages by keyset like any other list.

### Macros

A reply with variables (`{{firstName}}`, `{{amount}}`, `{{link}}`) and optional state, tag and priority changes, applied in one step. Per kind of work, versioned: an edit is a new version, owned by `macros.manage`, and applying one is audited with its version. Its words go through the module's own reply (`WorkQueue.reply`, today only tickets): there's no new email sender. A macro with words for a kind that can't reply is refused when it's written.

### Bulk actions

Assign, tag, set priority, close tickets, apply a macro, or the ladder's warn. **Money and enforcement beyond a warning aren't actions here at all**, rather than refused by a rule that could be got round.

- The preview and the run share one function (`BulkActions.plan`), and a test checks they agree item by item.
- The number of items is typed back. More than 20 goes through A1's approval.
- It runs as one batch, item by item, each in its own transaction, so a failure is that item's and is reported as its own. A batch left by a stopped instance resumes, and finished items stay finished.
- Assign, tag, priority and close can be undone for 10 minutes. A reply or a warning can't be unsent.

### The ladder

Warn, limit (no custom images, a cap on session hours a week, or payouts held for hosts), suspend (with an end date) and ban, for renters and hosts separately, each from a template citing the Terms (LEGAL(confirm)). The person is told why through the existing notifications, in the step's own transaction, with a link to appeal. Stopping is immediate and never gated; lifting goes to another admin. A strike counts for 180 days. Limits are asked for through the kernel's `AccountLimits` by the module each is about, so nothing depends on `trust` to enforce one.

### Appeals

One per step, from the person's account (Reports, or the held page) or the email's link, open to a held account: a text box and up to 3 of their own uploads. It lands in the inbox with the step's evidence and the history. **The admin who enforced can't decide it while another eligible admin can.** With no one else, they may, and `same_reviewer` shows on the decision and in the audit log. A reversal goes through the same approved lift; the appeal stays open until it runs, so a rejected lift leaves it undecided. The outcome is sent to the person.

## Alternatives considered

- **Events from every write path instead of a pass.** Fresher, but it touches every module's writes, and one missed event leaves an item wrong for good. A pass heals itself.
- **Bulk actions in one transaction.** Partial failure couldn't be reported, and one bad item would undo the rest.
- **Refusing other kinds of bulk action by a permission.** A list of what's allowed can't be widened by accident.

## Consequences

- An item changed on its own screen can take up to 30 s to show in the inbox.
- Each pass reads every open item of every kind. That's fine at today's volumes; a kind that grows past tens of thousands open needs a cheaper `open()`.
- A held account may upload only an appeal's files, through the same path as any upload: one file of at most 10 MB, at most 3 in 30 days. Uploads aren't checked for malware, these included, so an admin opens one as a download through a 5-minute link, and each opening is in the access log.
- Report and appeal targets are our defaults, and the legal ones and the templates' clauses wait for a lawyer.
