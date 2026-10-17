# 0032. Linking a host by approval

- **Status:** Accepted
- **Date:** 2026-10-17

## Context

ADR 0011 enrols an agent with a one-time code that its owner copies from the web app into the installer. Nikki's rule
(docs/plan/host-experience.md §3) is that a host is never told to "download this, install this, paste this": one
download, one **Approve** click, then it earns. The CLI already solves the same problem for API keys with a device
sign-in (ADR 0025, shaped like RFC 8628). The code-based enrolment stays for operators, the end-to-end test and the
first laptop sessions, so nothing about certificates (ADR 0010) or the agent's trade of a code for a certificate changes.

## Decision

A host is linked by approval, in the hosts module (`HostLinks`, `HostLinkController`, `hosts.host_links`):

1. **`POST /v1/host-links`** (no sign-in, limited per address like `POST /v1/cli-logins`, `limits.host-links-per-hour`):
   the agent sends what it knows of its machine (name, OS, agent version, up to 8 GPUs) and gets the link's `id`, the
   `approveUrl` (`<web app>/link/<id>/`), a `pollToken` and the poll interval. The machine's own words are only what
   the approval page shows; nothing relies on them. A link lasts 10 minutes.
2. **`GET /v1/host-links/{id}`, `POST …/approve`, `POST …/deny`** need a browser's sign-in (`@BrowserSignInOnly`,
   so an API key is refused, whatever the spelling of the path). Approving takes the name the owner confirmed and the
   listing the machine starts with (the price, the temperature limit, the disk to keep free), makes the host through
   the same service as `POST /v1/hosts` and puts its listing, in one transaction. Approving twice returns the same
   host; a denied or expired link is refused; another person's approved link is a 404.
3. **`POST /v1/host-links/{id}/ticket`** (no sign-in; the poll token is the proof): `authorization-pending` while
   waiting, `host-link-denied` or `host-link-expired` when it is over, and once approved the host's one-time enrolment
   code, **exactly once**, under the link's row lock. The agent then calls `EnrollmentService.Enroll` with it as before.
4. Approving and denying are rows of the audit log (ADR 0027), in the action's own transaction.
5. **The pilot rule:** an approved host is created **pending**, as today; an admin activates it. `hosts.auto-activate`
   (default false) is the setting that will let a host whose self-test passed go live by itself after the pilot. The
   self-test comes in a later round, so the setting only exists, and a test holds that false keeps hosts pending.

Two choices differ from the first sketch:

- **The enrolment code is made when the ticket is handed out, not when the owner approves.** Codes are stored only as
  a hash (ADR 0011), so a code made at approval could not be given to the agent later without being stored. Issuing it
  at the ticket, in the same transaction that spends the link, gives the same guarantee (once, within the hour) and
  stores nothing new.
- **The poll token is stored as a SHA-256, not an HMAC.** It is 256 random bits, like an API key or a CLI sign-in's
  poll token, so there is nothing to guess and no key to keep; invite codes need an HMAC because they are short.

The ticket also says the first letter of the owner's address (`ownerHint`), so the agent can say who it was linked to
without learning more.

## Alternatives considered

- **Keep the one-time code for hosts.** Lost: it is the "paste this" step Nikki ruled out, and a code in a window
  can be read over a shoulder.
- **The approval page creates the code and the browser hands it to the installer** (a deep link or a local port).
  Lost: it needs the browser to reach the installer, and codes would pass through the page.
- **Let an API key approve a link.** Lost: a key can act as its owner from anywhere, and approving creates a host
  and credentials; it needs a person at a browser, as creating keys does (ADR 0025).
- **Activate the host on approval.** Lost for the pilot: Nikki knows every host and approves each one in the admin
  console (docs/plan/host-experience.md §8, decision 2).

## Consequences

- A host's owner never sees or types a code. The agent's key pair is still made on the laptop and never leaves it.
- The two unauthenticated routes are the abuse surface: they are limited per address, store a bounded record, expire
  in 10 minutes, and a poll with a wrong token is a 404.
- `identity` lists the two routes as signed-out calls and counts the per-address limit (the signed-out rules live
  there); `ProductionSettingsGuard` refuses a plain-HTTP or local `hosts.link.web-app-url` and a raised
  `limits.host-links-per-hour`.
- Open question: the approval page does not yet say anything about the machine's real identity beyond what it
  reported; a stronger check (the installer's signature, a hardware id) comes with the Windows app.
