# 0024. Access by invitation, the waiting account, notification choices, and real sign-in for local development

- **Status:** Accepted
- **Date:** 2026-10-11

Sources:
- docs/plan/product-surface.md sections 3.2, 9, 11.12, 11.14 and 12
- ADRs 0009 (identity through a managed provider), 0013 (development-only behaviour is a setting), 0018 (the web app), 0023 (hosting)
- docs/runbooks/identity-provider-setup.md, docs/runbooks/invites.md

## Context

Free credit and five friendly hosts can't take open sign-ups, and the decision on Oct 7 was invite-only until the paid pilot, with codes handed out by hand (product surface section 12). Until now anyone with a Google account who signed in became an active user. Account was also half built: a name and an email, with no way to see how you signed in and no say over what you're told. And a developer could only sign in through the development sign-in server, so the real Google path had never run in the web app.

Constraints:
- **Identity stays portable (ADR 0009).** Nothing from the provider, and no provider SDK, may enter the control plane.
- **Development behaviour is a setting (ADR 0013),** never a code path.
- **Money and safety messages must reach people.** A person who has switched everything off must still be told their session stopped for lack of credit.
- **Nothing sends email yet.** Choices have to exist before the senders do, so that what someone chose holds when sending starts.

## Decision

**A new account is `waiting` until it enters an invite code.**
- `identity.invite-only` (environment `IDENTITY_INVITE_ONLY`) is true by default. A first sign-in then creates a user with status `waiting`; with it false (the test profile, an end-to-end run that wants to look around by hand, or an open launch later) it creates an active one. Users who signed in before this decision are active.
- A waiting user may call only `GET /v1/me`, `PATCH /v1/me` and `POST /v1/me/activate`. Everything else is 403 `invite-required`. The rule is one decision, in the identity module's access manager, so no other module can forget it.
- The web app sends a waiting person to the invite step: straight after sign-in (from `GET /v1/me`), and whenever any request meets `invite-required`, at the one place every query and action passes, so no screen asks first and nothing is fetched early. The cache then holds the account as waiting, so the step doesn't send the person back.

**Codes are single-use, stored only as a hash, and made by an operator.**
- A code looks like `IDLE-7K2Q-9XMD`: a prefix and 8 characters from an alphabet without 0, O, 1 and I, about 40 bits. The database keeps SHA-256 of the code's letters and digits (so case, dashes and spaces never matter), with its note, its maker, an optional expiry and, once used, who used it and when.
- `create-invites --count N --note "…" [--expires 30d]` is an operator command in the ops profile, like `grant-credits`; it prints the codes once.
- `POST /v1/me/activate { code }` claims a code with a conditional update and activates the user in the same transaction, so two people can't spend one code. A wrong, used or expired code is the same 400 `invite-invalid`, so a guess learns nothing. Every try counts, right or wrong: 5 an hour for each user, counted under the user's row lock, then 429 `too-many-attempts`. An already-active user gets 200 and the code is left unused.

**The account says how you signed in, and what you're told is your choice, except where it can't be.**
- `GET /v1/me` returns `signInMethods` from the token's `firebase.sign_in_provider` (`google.com` is `google`; `password`, which is how Identity Platform names an email link, is `email-link`; anything else is left out). Nothing is stored. The display name is 1 to 60 characters.
- `GET` and `PUT /v1/me/notifications` list every kind in product surface section 9 with a switch for email and one for the app. All are on by default and only departures from that are stored, in the notifications module's own table, with no foreign key to identity. `PUT` replaces the whole set; a kind left out is on again.
- **Locked kinds** can't be switched off, by email or in the app, and a request that tries is 422 `notification-locked`: stopped for credit or the spending cap, host lost, machine offline, payout sent or failed, top-up confirmed, and a new device signing in. They are the messages about a person's money and safety.
- The web app shows what the server last answered: a switch moves only after its save is accepted.

**Real sign-in for local development is a switch on the run script.**
- `scripts/run-local.sh --real-sign-in` trusts the Identity Platform project in `control-plane/.env`; without it the script ignores those lines and the development sign-in server stays the default. The script refuses a missing, example or mismatched value and names the line.
- The project's identifiers go only in the git-ignored `control-plane/.env` and `web/.env.local`; the runbook says where to find each.
- Tests and CI keep the mock: no real Google runs in any test.

## Alternatives considered

- **Open sign-up with a waiting list.** Needs someone to approve each person and a place to hold them; a code is the same gate with nothing to administer.
- **A flag the client checks.** Moves the rule to every screen and every future client, including the CLI. The server refuses instead, once.
- **Codes kept in the clear, or HMAC'd with a server secret.** Clear text is a list of keys to anyone who reads the database. An HMAC needs one more secret in every environment, for a code that's single-use and limited to 5 tries an hour; plain SHA-256 was enough.
- **A rate limit in Valkey.** Valkey is allowed to expire and is never required (CLAUDE.md); a limit that vanishes when it restarts is no limit. The attempts are rows.
- **Letting people turn off everything.** A person who muted "stopped for credit" would find out from the charge. Locked kinds are the compromise, and the list is short on purpose.
- **Saving notification choices with one Save button.** Eleven switches and a button is a form to forget to submit; each switch saving by itself, shown only once saved, is how the rest of the app treats server state.

## Consequences

- **Easier:** handing out access one person at a time, and seeing who used which code; a developer can sign in with a real Google account; the senders to come have choices to read.
- **Harder:** every end-to-end run now makes codes and activates its own accounts (`activate_user` in `scripts/e2e/agent-online.sh`), and one journey signs in as a new person. A local run with the development sign-in server also starts as waiting; set `IDENTITY_INVITE_ONLY=false` in `.env` to skip the step.
- **Watch:** the limit counts every try over a sliding hour per user, so someone who makes many accounts can still guess; email verification is what makes accounts cost something. The 40-bit codes are one-use and few. Phone verification for everyone replaces this before automatic starting credit (section 11.12).
- **Open question:** the terms and privacy pages are placeholders that say they're being drafted; the real text needs a lawyer before the first outside tester (section 11.14).
- **Open question:** whether `identity.invite-only=false` should be refused in production. It isn't now: an open launch is a legitimate setting, not a development shortcut.

## Addendum (2026-10-14): keyed hashes for codes, and the waiting user's calls by route

- **Codes are HMAC-SHA-256, not SHA-256.** An invite code has about 40 bits and a recovery code (ADR 0027) about 50: from a copy of the database, every plain hash could be reversed by trying every code. Both are now stored as HMAC-SHA-256 under `identity.invite-code-key` (`IDENTITY_INVITE_CODE_KEY`, 32 bytes from the secret store), with a different prefix for each kind of code (`CodeHashes`). The prod profile doesn't start without the key, or with the tests' committed one; locally it's `private/dev-ca/invite-code.key`, made by `scripts/dev-ca.sh`.
- **The codes stored the old way were retired, not rehashed:** a plain hash can't be turned into a keyed one without the code. Migration V35 expires every unused invite code and marks every unused recovery code used. No real users existed yet, so nothing of value was lost; anyone given a code that wasn't yet used needs a new one, and admins get new recovery codes by being revoked and granted again.
- **A waiting user's three calls are decided after routing,** by the handler the request reached (`@OpenToWaitingUsers` on `GET` and `PATCH /v1/me` and `POST /v1/me/activate`, checked by `AccessRules`), never by matching the path's spelling, and it fails closed: a request that reaches no handler is `invite-required` too (ADR 0025's addendum has the same for keys).
- **The open question above is answered:** `identity.invite-only=false` is now refused in production. An open launch will be a decision with its own change to `ProductionSettingsGuard`, not a setting someone can flip.
