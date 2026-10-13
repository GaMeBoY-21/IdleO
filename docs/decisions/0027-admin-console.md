# 0027. The admin console: an admin role, TOTP, short admin sessions, 404 not 403, one implementation per action, and an append-only audit log

- **Status:** Accepted
- **Date:** 2026-10-13

Sources:
- docs/plan/product-surface.md sections 8, 11.16 and 13
- docs/plan/technical-design.md, "Identity and access" (founders and admins: a second factor, per login) and "Audit trail" (admin actions and certificate issues and revocations in an append-only table)
- ADRs 0014 (operator commands, free credit), 0015 (activating hosts), 0017 (charging and the review queue), 0018 (the web app), 0024 (invites), 0025 (API keys)
- docs/runbooks/admin.md

## Context

Until now the pilot was run by operator commands: `grant-credits`, `reverse-grant`, `activate-host`, `suspend-host`, `reconcile-wallets` and `create-invites`, each a JVM started by hand in the `ops` profile. Nikki couldn't suspend a user, force-stop a session, read a session's timeline, or work the review queue at all. The queue held minutes nobody could decide (V13: "Nobody works this queue yet"), and nothing recorded who did what beyond each command's own columns.

The gate: on the local stack, an admin signs in with a second factor at `/admin` and can:
- activate and suspend hosts;
- make invite codes;
- grant and reverse credit;
- suspend users;
- force-stop a session;
- work the review queue;
- see reconciliation results.

Every action lands in an append-only audit log, and a normal user gets 404 on every admin path.

Constraints:
- **One implementation per rule.** Every console action must be the very method its operator command calls.
- **Nothing depends on metering (ADR 0017),** and modules don't read each other's tables.
- **Database first, then side effects;** the audit row can't be a log line written after the fact.
- **Renters never download the console** (the first-load budget, ADR 0018).

## Decision

**The role.**
- A user is an admin when `identity.admins` has their row.
- Only two operator commands change it: `grant-admin <email> --reason` and `revoke-admin <email> --reason`. There's no API.
- `grant-admin` takes a verified, active account with that email, and refuses an email two accounts share.
- `revoke-admin` deletes the row, and with it the second factor, the recovery codes, the code tries and every admin session (`ON DELETE CASCADE`). Granting again therefore starts a fresh enrolment, and that is the only way out of a lockout.

**The second factor: TOTP (RFC 6238).**
- The code is HMAC-SHA1 over 30-second steps, 6 digits, from a 160-bit secret. It's checked here (`Totp`, about 60 lines, tested against RFC 6238's own vectors), not with a library.
- At an admin's first visit, `POST /admin/v1/second-factor` makes a secret and shows it once: as base32 text and as an `otpauth://` link, which the console draws as a QR code. Asking again before confirming replaces it.
- `POST /admin/v1/second-factor/confirm { totp }` sets it up. It returns the first admin session and ten recovery codes, shown only then. The codes are 10 characters from the invite codes' alphabet, kept as SHA-256 hashes and usable once each.
- The secret is sealed with AES-256-GCM under `identity.admin.totp-key`: 32 bytes from the environment, or `private/dev-ca/admin-totp.key` locally, made by `scripts/dev-ca.sh`. Each seal has a fresh nonce and the admin's user id as associated data, so a sealed secret copied to another row doesn't open.
- A code is accepted for its own step or the one either side, and never again for that step or an earlier one: each row keeps `last_totp_step`.
- **Lockout:** every code tried is kept, right or wrong. The fifth wrong code in 15 minutes locks admin access and ends its sessions, and is audited as `admin.lock`. A locked admin is treated as no admin at all.

**The admin session.**
- `POST /admin/v1/session { totp | recoveryCode }` gives a random 32-byte token, kept as its SHA-256 hash.
- It's tied to the sign-in identity that made it: our row for the ID token's issuer and subject, now carried in the authentication. It works only beside that same ID token.
- It lasts 30 minutes from its last use. Use renews it, at most once a minute so reads aren't writes, and never past 8 hours from when it began.
- The token travels in an `Admin-Session` header beside `Authorization`. The web app keeps it in memory only, never in storage, so a reload asks for a code again.
- `GET /admin/v1/session` says where the admin stands: enrolled, active, and until when. `DELETE` ends it.

**Its own security chain, and 404, never 401 or 403.**
- `/admin/v1/**` has a filter chain of its own (`AdminSecurityConfiguration`), ahead of the API's.
- It takes ID tokens only: an API key is refused even beside a live admin session.
- The role alone reaches the second-factor calls. Every other call needs a live admin session too.
- Anything short of that answers exactly as a path that doesn't exist: Spring's own `NoResourceFoundException`, rendered by the one problem renderer. The body differs only in `instance`, so nobody can tell which admin paths exist.
- CORS allows the `Admin-Session` header from the web app's origins only.

**One implementation per action.**
- Each module's admin controller lives in that module.
- Where an operator command exists, the console calls its very method:
  - `HostActivation.activate` and `suspend`;
  - `CreditGrants.grant` and `reverse` (the console's Idempotency-Key is the grant's own key);
  - `InviteService.create` (through `IdempotentRequests.executeShownOnce`, since the codes are secrets);
  - `WalletReconciliation.run`.
- Those methods now take an `AuditTrail.Actor` (an admin, an operator, a host's owner or the system) instead of a name. Commands keep their records' `granted_by` and `changed_by` as the plain operator name; admins appear as `admin <email>`.
- Actions without a command are new services: `UserSuspensions`, `HostCertificateService.revokeAll`, `SessionForceStops` and `ReviewQueue`.

**The audit log.**
- `identity.audit_log`, written only by identity's `AuditTrail.record`, in the action's own transaction (`Propagation.MANDATORY`), so an action and its row commit or roll back together.
- Each row: who (kind, user id, name), what (`host.activate`), the target (type and id), the reason, the target before and after as JSON (never a secret: invite creation lists code ids, never codes), and when.
- Triggers refuse UPDATE, DELETE and TRUNCATE. The migration also revokes those rights from PUBLIC and from the database user the application runs as.
- Operator commands, the system's certificate issues (enrolment, renewal), certificate revocations (an admin's, or an owner removing a machine) and lockouts are logged as well.
- `GET /admin/v1/audit` filters by who, what (an action or its start, `credit.`), target and time.

**What an action does.**
- **Suspending a user:** active accounts only, never one's own. Their tokens and keys are refused from the next request; reinstating makes them active.
- **Revoking a host's certificates:** revokes every one and publishes `HostCertificatesRevoked`, which closes the agent's stream. A stream held by another instance closes at its next "seen" heartbeat, which now also checks the host has a certificate left.
- **Force stop:** a running session goes through the same `RunningStops` as a renter's stop. It's stopped for `operator_stopped`, at once and without a checkpoint, and settles like any stop.
  - The agent hears `STOP_REASON_ABUSE`, the protocol's one reason for a stop the platform decides. A dedicated value can be added later; additive values are allowed.
  - The renter gets the "didn't finish" message with `operator_stopped`'s words from `api/endings.json`.
- **The review queue:** an admin charges or waives each minute, once.
  - A charge is a settlement of its own: the session's next window, through `SessionCharges.settle`, at the attempt's price, never past the cap or the renter's credit.
  - It doesn't mark the usage minute billed, since one witness never saw it. This is the one way a one-witness minute is ever charged: by a person, with a reason.
  - A waive moves no money. A trigger lets a decided minute never change again.
- **Reconciliation:** every complete run, nightly or asked for, is kept with its mismatches (`billing.reconciliation_runs`), and the console can run one now.

**The dashboard.**
- Identity declares `DashboardFigures`. Hosts, sessions, metering and billing each implement it for the figures they own, so nothing depends on metering.
- The figures: hosts online, sessions running, sessions today (from midnight IST), settlement backlog, the latest reconciliation's mismatches, and the review queue's size. A test checks each figure comes from exactly one module.

**The web console.**
- Pages under `/admin/`: Dashboard, Users, Hosts, Sessions, Review queue, Money, Invites and the Audit log, plus enrolment and the code check.
- Its code is one lazily loaded chunk (`src/features/admin/`), fetched only on an `/admin/` address and kept out of the "load every screen after the first" list. `scripts/first-load.ts` fails if any first-load file holds admin code.
- Every action opens one sheet that names it, says what will change and needs a reason of 5 to 500 characters, mirrored from the server's `AdminReason`. There are no browser dialogs.
- The look is the app's black and white, dense: a left navigation (a strip at the top on a phone) and tables.

## Alternatives considered

- **A 12th module for the console.** The technical design names eleven modules, and every module already depends on identity, which owns who admins are. Putting the audit log and the dashboard's port in identity needs no new direction.
- **Each module writing its own audit table.** That means one reader per module and no single log to search. A port in identity gives one table and one guard.
- **403 for a normal user.** It tells a probe which paths exist. 404, identical to a missing page, tells nothing.
- **The admin token as the bearer, without the ID token.** Then a stolen admin token alone would open the console. Requiring both, tied together, means it needs the person's sign-in too.
- **Keeping the admin token in sessionStorage,** so a reload keeps the session. The app's rule is that our code never stores a token, and a code per reload is cheap for one admin.
- **A TOTP library.** RFC 6238 is a page long and has official vectors; a dependency would add more than it saves.
- **A separate HTML entry for `/admin`,** so the main bundle wouldn't change by a byte. Shared modules would then be split into chunks of their own, changing which files the renter's first screen fetches. A lazy route costs 135 bytes gzipped.
- **Charging a review minute by marking it billed.** The usage table's check insists a billed minute had both witnesses, and it's right: the reviewed charge is recorded on the review row and its settlement instead.

## Consequences

- One person can run the pilot from a browser. Every privileged act, by anyone or anything, is in one table that can't be edited.
- `grant-credits`, `reverse-grant`, `activate-host`, `suspend-host`, `reconcile-wallets` and `create-invites` behave as before and now write audit rows. `reconcile-wallets` also keeps its result.
- **Changing `identity.admin.totp-key` makes every enrolment unreadable.** Each admin must then be revoked and granted again (docs/runbooks/admin.md). Open question: key rotation, with a key id per sealed secret.
- **The main bundle grew by 135 bytes gzipped** (147,489 to 147,624), for the route. The console itself is a 59 KB chunk renters never fetch.
- **The audit log grows forever.** The technical design keeps it a year. Open question: an archive job that moves old rows out without deleting them in place.
- **A force stop's agent reason reads "abuse"** until the protocol has an operator reason.
- **Suspending a user doesn't stop their running sessions;** an admin force-stops those separately.
- **Disputes** (the next round), **payouts** and **the eleven metrics** come to this console later. Open question: the admin subdomain, at deployment.
