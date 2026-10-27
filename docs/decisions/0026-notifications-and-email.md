# 0026. Notifications as their own module, one email interface, Mailpit locally, and no tracking

- **Status:** Accepted
- **Date:** 2026-10-12

Sources:
- ADRs 0015 (module directions, durable events), 0017 (charging), 0018 (the web app), 0024 (notification choices), 0025 (the renter CLI and limits)
- docs/runbooks/email.md

## Context

ADR 0024 gave people switches for what they are told, and nothing sent anything. A renter whose session ended, ran out of credit or lost its host had to be watching the screen to know; a machine's owner never learned that their laptop dropped a session. The gate for this round: on the local stack these arrive in the app and as email, respecting each person's choices.

Constraints:
- **Nothing depends on metering (ADR 0017),** and modules never read each other's tables.
- **Events are durable and may arrive twice (ADR 0015).** A message and its email must still be made once.
- **The email provider isn't chosen.** Whatever is picked later must plug in at one place.
- **Money and safety kinds always send (ADR 0024),** whatever the switches say.
- **The words must agree.** An email that says what a session cost or why it failed has to say what the screen says.

## Decision

**Notifications listen; the others announce.**
- The notifications module listens, with Spring Modulith's durable `@ApplicationModuleListener`s, to events other modules already publish or now do: `AttemptLost` (sessions), and three new public events, `SessionSettled` (a session that ran has ended and its charge is final), `SessionNeverRan` (it ended without running: no GPU was free, or it failed before starting) and `SessionLowOnCredit` (under 10 minutes of credit).
- Metering doesn't publish them. It tells sessions, through the sessions module's `SessionMilestones` port, and sessions announces them, so nothing depends on metering and ADR 0017's directions hold.
- Notifications reads sessions through one more port, `SessionOutcomes` (who the session belonged to, its service, how it ended, what stopped it, which host ran an attempt) and `SessionCosts` for the charge; it asks hosts who owns a host (`HostOwners.ownerOf`) and identity for a verified address (`UserDirectory.verifiedEmail`). Identity declares `UnreadNotifications`, which notifications implements, so `GET /v1/me` carries the unread count without identity depending on notifications.

**Seven kinds are sent.** The five the gate names, and two more from section 9's P1 rows that need nothing else:

| Kind | To | Sent when | Channels |
| --- | --- | --- | --- |
| session-completed | renter | a session ends without a failure or a stop for credit, and it wasn't stopped by the renter | app, email |
| session-failed | renter | it fails, ends refunded, or fails before it starts | app, email |
| stopped-for-credit | renter | it was stopped because credit ran out or the spending cap was reached | app, email (locked) |
| low-credit | renter | its credit falls under 10 minutes; once for each session | app, email |
| no-host-free | renter | no GPU was free | app |
| host-lost | renter | its host dropped; it is moving to another | app (locked) |
| machine-offline | the host's owner | their machine dropped a session | app, email (locked) |

A session the renter stopped says nothing: they did it themselves. "Agent update needed" isn't sent: nothing detects an outdated agent yet, and a sender nothing calls is dead code. The kinds' channels are in `NotificationKind`; a person's email switch for a kind that never emails changes nothing.

**A message is made once, and emailed at most once.**
- Each message has a dedupe key naming the event that caused it (`session-ended:<session>`, `low-credit:<session>`, `host-lost:<session>:<attempt>`); a second delivery finds it and makes nothing. Low credit is therefore once for each session, however often credit dips.
- The person's choices decide it when it is made: in the app, by email, both or neither; locked kinds are both whatever was stored.
- The email is claimed (`email_claimed_at`) in a transaction of its own before it is handed to the mail server, marked sent after, and the claim is given back only if the server refuses it, so the retry sends it. The price is that a crash between the claim and the hand-over loses one email, never sends two. In-app messages are never lost.
- An owner's email never names the renter, the session or what ran (ADR 0023).

**One `EmailSender`, SMTP today.**
- `EmailSender` is the only place a provider plugs in. Its implementation speaks SMTP through Spring's mail sender, configured by `spring.mail.*`; any provider with an SMTP relay is a change of configuration. If the product owner picks one that is better reached by an HTTP API, it is a second implementation of the same interface.
- Locally, compose runs Mailpit (web view on port 8025, SMTP on 1025, overridable). It keeps every email and delivers nothing. The end-to-end test reads its API.
- Production needs `MAIL_HOST`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `NOTIFICATIONS_EMAIL_FROM` and `NOTIFICATIONS_WEB_APP_URL`; the prod profile refuses to start without them, and refuses a mail server, sender or link on this machine, or plain HTTP (`ProductionSettingsGuard`, ADR 0013).

**No tracking.** Each email is plain text with a simple black and white HTML twin: no images, no remote styles, no scripts, no tracking pixels, and links that are the plain address of a page in the web app, never a redirect through us or anyone else. Its footer says why the person got it and links to Account to change it. We never learn whether it was opened.

**The words are shared.** The sentences for why a session ended are `api/endings.json`, read by the web app and by the control plane (shipped in its jar); money is `Rupees.format`, checked against `formatPaise` with the cases in `api/money-format.json`. A test on each side reads both.

**The web app.** A bell in the header with the unread count (the count is what `GET /v1/me` last said until the list arrives, and its box is the same with or without it); a list at `/notifications/`, newest first, where opening a message goes to what it is about and marks it read, and "Mark all read" reads the lot; and banners for what arrives while the app is open, which sit under the header and push the screen down, never over it. The list is asked for again every 30 s while the tab is visible and when it regains focus.

## Alternatives considered

- **Metering publishes its own events.** Simplest, and it breaks "nothing depends on metering" (ADR 0017). The `SessionMilestones` port costs one small interface.
- **Enrich `SessionEnded` with the charge and reason.** It fires before metering closes the session, so the charge isn't final; and every existing listener would carry fields it doesn't need.
- **At-least-once email, with a unique key at the provider.** The provider isn't chosen, and not every provider has one. Claim-then-send gives "never twice" without it.
- **Push banners through the session event stream.** The stream exists only while a session screen is open, so banners would need a second way for every other screen. Polling on focus and every 30 s serves all screens with one request a half minute.
- **A tracking pixel for opened emails.** Nothing we do depends on it, and it is a privacy cost to renters for no gain.

## Consequences

- A renter hears about every session's end without watching it, and an owner hears a machine dropped a session, with the money figures the screens show.
- Sending later kinds (outputs expiring, weekly earnings, payouts, top-ups, new device sign-in) is a listener, a message and a dedupe key each; none needs a new mechanism.
- A crash between the claim and the hand-over loses one email. Watch for messages with a claim and no send time (`email_claimed_at` set, `email_sent_at` null) beyond a minute: that is a refused or lost email to look at.
- Polling adds about two requests a minute for each open tab, well inside the 60 a minute renter limit (ADR 0025).
- Open question: which email provider, and the domain it sends from (docs/runbooks/email.md lists what the domain will need).

## Addendum (2026-10-14)

Opening a message marks just that one read (`POST /v1/notifications/read` with `{ "id" }`); "Mark all read" still marks everything up to the newest (`{ "upTo" }`). Exactly one of the two; both or neither is `validation-failed`.
