# 0037. Personal data: rights, retention and support

- **Status:** Accepted
- **Date:** 2026-10-21

## Context

Before anyone outside the team signs in, IdleO must meet the Digital Personal Data Protection Act 2023 and its Rules 2025: a person can see, correct and erase their data; grievances are answered within 90
days; a breach is told promptly to the people affected and reported to the Data Protection Board (an intimation, then a
detailed report within 72 hours); data is erased once its purpose is served; security and access logs are kept at least a
year; and no one under 18 has an account. The wording of the Terms and the privacy notice, and the periods the law sets,
are a lawyer's, not ours. Until the round, nothing deleted anything but uploads, the audit log held admins' emails, and
Contact was a placeholder.

## Decision

**A map first, and a test that keeps it whole.** docs/reference/personal-data-map.md lists every table with every column (the
personal ones in bold), how to find one person's rows (`Whose`, a SQL condition), where they are in an export, why we keep
them, how long, and what erasure does (Deleted, Cleared or Kept); every storage prefix, Valkey key, log, metric and the
outside services. `PersonalDataMapTests` fails on a table, column or storage prefix the map doesn't list, on a retention
rule without its job or a job the map doesn't name; the export and erasure tests run each `Whose` for two people and check
the map's promise for every table.

**Every module answers for its own data, through one port.** `PersonalData` (shared kernel): a summary for "Your data", the
records for the export, what blocks deletion, what the person gives up, and its part of each erasure step. The privacy
module asks them all and reads no one's tables; nothing depends on it.

**Export.** "Download my data": one at a time, one a day, made by a job into a zip (a JSON file a group, money in paise
with rupees beside it, links to the person's files that work 7 days), stored at `exports/{id}/`, emailed, fetched only by
its owner through a 5-minute link, deleted after 7 days. Correction: the name in Account; the email follows the sign-in
provider's verified one (`email_changed` in the security log), anything else by asking support.

**Deletion.** Asked for only from a browser signed in in the last 10 minutes (the ID token's `auth_time`; `@BrowserSignInOnly`
so no API key can); the development sign-in server sets `auth_time` too, so the tests prove it. Confirmed by an emailed
link that works once, for 24 hours, kept only as its SHA-256. Then 7 days (`legal.erasure.waiting-period`) the person
cancels with one click, in the app or from either email (each link hashed); a reminder a day before. An admin can't skip
the wait but for a legal order, audited with its reason. Blocked, in plain words, by a session not ended, a machine running
someone's session, earnings not paid out, or a report not answered. The free credit left is forfeited (shown with its
amount), through the ledger back to promotions; paid credit, once there is any, goes back where it came from instead
(the one place, `BillingPersonalData`).

**Erasure: eight steps, each recorded, each safe to run twice** (`Erasure`): lock (suspended, keys and device sign-ins
revoked, admin role gone); machines removed as Remove does; files, outputs, checkpoints and logs deleted from storage and the
listing checked empty; profile, identities, choices, inbox, links, exports and tickets' words deleted; money records and
ratings kept by the opaque id only; the sign-in provider's accounts deleted (`SignInAccounts`: Identity Platform's
`accounts:delete` with a service account from `IDENTITY_PROVIDER_ADMIN_CREDENTIALS`, signed here, no Google library; the
prod profile refuses to start without it; the development server's calls are recorded); the id added to the erased list;
a last email to the address put aside before step 4. Each step is one audit row, by id. The account's row stays as a
tombstone (`erased`, no email or name) so the ledger's ids lead to "an erased account" and to nobody.

**Restores re-apply it.** The erased list is `privacy.erased_users` (append-only) and, outside the database, an `erasures/`
object a person, so a restore to a moment before an erasure still finds it. `ops/backup/restore.sh` runs
`reapply-erasures` after every restore; the drill erases someone after T and proves the database restored to T erases
them again.

**The security log is not the audit log.** `identity.security_events` holds sign-ins (a newer `auth_time`), refused and
failed sign-ins, key and device sign-in events, admin console sign-ins and wrong codes, email changes, and export and
deletion requests, with the IP address: the only place an address is kept, read only in the admin console, deleted after
a year (`legal.retention.security-events`). The audit log stays append-only and keeps people by id only: an admin is named
by user id (their email is shown at read time, so an erased admin is an id), `OPERATOR` must be a handle, and an entry with
an email or an address anywhere is refused (`ContactDetails`); admin reasons and invite notes are validated the same way.

**Retention: one rule, one job.** Every period in the map is a `RetentionRule`, run daily by `RetentionJobs` under its own
advisory lock, counted (`retention.deleted`) and watched (`retention.age`; the RetentionStopped alert after a day and a
half). The product's periods are `retention.periods.*`; the law's are `legal.*`, the one block of every value marked
LEGAL(confirm). Metrics and traces carry nothing personal (`TelemetryPrivacyTests`).

**Support.** A module of its own: the Contact page (website and app) takes a message signed in or not, limited per person or
address, refused when a hidden field is filled or it arrives within 3 seconds of the page (no captcha service); every
ticket is acknowledged by email with its number; answers are emailed with the number in the subject, read and answered in
Account, Help ("Reply here, not by email"; every email's Reply-To is a no-reply address). A privacy request or grievance is
due `legal.support.response-due` after it arrived (30 days, never more than 90); the admin list puts the overdue first and
an alert fires 7 days before. The grievance officer on Contact and Privacy comes from `legal.grievance.*`, which the prod
profile requires and refuses placeholders for.

**Notices and age.** The Terms and privacy notice versions accepted are recorded with the time and, the first time, the
person's word that they're 18 or older; a new version (configuration) is asked for once, before anything else, by the app
(the server records; it doesn't refuse the API, so a script's key isn't cut off mid-run). The pages keep "Being drafted."

**Breach.** docs/runbooks/breach.md, and the admin console's Notices: a written notice emailed to chosen people, audited by
their ids.

## Alternatives considered

- **Keep sign-ins in the audit log.** One table, but it would hold addresses, and the audit log's period (as long as the
  money it explains) is far longer than a security log's year.
- **Delete the user's row.** Cleaner, but every money record's id would point at nothing, and a later sign-in with the same
  provider account could not be told from a new person. A tombstone with nothing personal says what happened.
- **Have each module listen for an "account erased" event.** Events are delivered in any order and retried separately; the
  steps must happen in order (lock before removing hosts, files before records) and the job must know when all are done.
- **A random new id for an erased person's money records.** The ledger can't be edited, by design; the opaque id leads to
  nobody once the profile and identities are gone.
- **Enforce the Terms on the API.** Every script's key would stop the moment a version changes; the app asks, and the
  record is kept.
- **A captcha service.** It would show every visitor to a third party; a hidden field and the time on the page stop
  the simple machines, and the per-address limit the rest.

## Consequences

- A new table or storage prefix can't land without its line in the map, and a new module that keeps personal data
  implements `PersonalData` or its rows fail the walks.
- Money records (the ledger and what explains it) and the audit log are kept 8 years (LEGAL(confirm)) and **not yet
  deleted after it**: both are append-only on purpose, the first reaches its period in 2034, and deleting while keeping
  the ledger balanced and the log trustworthy is a later decision. Open question: how, and whether the period differs for
  the audit log.
- An erased person can remain in backups for 30 days (`legal.retention.backups`); the privacy notice must say so.
- A host with unpaid earnings can't delete their account until payouts exist or support pays them by hand.
- Admins see ids where they saw emails in the records other modules keep (granted by, changed by); the audit log shows the
  email of an admin who still has an account.
- Open question: which channel the Data Protection Board accepts reports by, and every LEGAL(confirm) value.

## Addendum: bought credit at deletion (ADR 0039)

Credit can now be bought, and bought credit is the person's money, so it is never forfeited. Before the erasure's blockers
are asked, `PersonalData.beforeErasure` lets billing start a refund of everything left of the person's bought credit to
the payments it came from (`CreditRefunds.forDeletion`, approving a request still waiting if there is one). The deletion
then waits: `refund-in-progress` blocks it until every part is processed, and `refund-failed` until support has sorted out
one Razorpay couldn't refund. An open chargeback (`chargeback-open`) blocks it too. Only free credit is forfeited, as
before. The personal data map lists the new tables (billing details, invoices, refunds and their parts, top-ups and
their failures, chargebacks, payment mismatches).
