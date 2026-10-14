# 0025. API keys beside ID tokens, the CLI's device sign-in, and the renter limits

- **Status:** Accepted
- **Date:** 2026-10-12

Sources:
- docs/plan/product-surface.md sections 4, 11.3 and 11.7
- docs/plan/technical-design.md, "Control plane modules and API" and "Limits"
- ADRs 0009 (identity through a managed provider), 0019 (the booking contract), 0022 (renters bring their own model), 0024 (access by invitation)
- RFC 8628 (OAuth 2.0 device authorization grant), RFC 9110 section 10.2.3 (`Retry-After`)

## Context

The renter's command line, the SDK and scripts can't use the browser's ID token: it comes from a page, lasts an hour, and Firebase has no device sign-in. They need a credential of their own that a person can see, name and revoke. The technical design also lists renter limits (requests, new sessions, sessions at once) that nothing enforced, and API keys make scripting easy, so they had to land with the CLI.

Constraints:
- **Portability (ADR 0009).** A key is our own credential. Nothing from the identity provider may reach it, and a provider move must not touch keys.
- **A stolen key must be a bounded loss.** It must not be able to make more access, or move an owner's machines or money.
- **Valkey may expire and is never required** (CLAUDE.md), so a limit that must be exact can't live only there.
- **One decision in one place** (the access rules, the problem catalog, the idempotency helper).

## Decision

### API keys, the second credential

- **The key:** `idleo_` and 32 random bytes in lower-case base32 (52 characters). It is shown once, in the answer that makes it, and stored only as its SHA-256 hash with its first 8 characters after the prefix for display. 256 random bits need no slow hash: there is nothing to guess.
- **Fields:** name (1 to 60 characters), scope (`read` or `full`), created, last used (written at most once a minute), revoked, and how it was made (`web` or `cli-login`). At most 20 keys that aren't revoked a person, counted under the person's row lock.
- **Endpoints:** `GET /v1/api-keys`, `POST /v1/api-keys {name, scope}` (Idempotency-Key), `DELETE /v1/api-keys/{id}` (204, the same again, 404 for someone else's).
- **One place decides.** A bearer value starting `idleo_` is checked as a key; anything else as an ID token. The one resolver is in the identity module's security configuration. A revoked or unknown key is 401, as a bad token is. A key is held to its owner's status exactly like their token: suspended is 403 `account-suspended`, waiting is limited to the account calls.
- **Shown once, even through Idempotency-Key.** The shared helper stores a create's response so a retry gets it back byte for byte; that would put a key in the database. For a secret it stores no body (`IdempotentRequests.executeShownOnce`), and a repeat with the same key is 409 `secret-shown-once`, naming the key that was made so it can be revoked.

### Scopes and what no key can do

- A `read` key makes GET and HEAD requests only (403 `api-key-read-only` otherwise), and may revoke itself.
- **No key, of any scope, may do what needs a browser's sign-in** (403 `browser-sign-in-required`), listed once in `KeyRules`: create a key, find or approve a CLI sign-in, add, remove or price a machine (`POST /v1/hosts`, `DELETE /v1/hosts/{id}`, `PUT /v1/hosts/{id}/listing`), issue a machine's enrolment code, and change notification choices.
- **A full key may list keys and revoke any of its owner's keys.** Revoking only ever takes access away, and `logout` and `keys revoke` need it. Creating a key always needs the browser: `idleo keys create` runs the same approval as `login`, so a stolen key can never make another.
- The enrolment code is on the browser-only list although the prompt didn't name it: a stolen key that could issue one could enrol a rogue agent. Renaming, pausing and resuming a machine are allowed with a full key.

### Device sign-in for the CLI, shaped like RFC 8628

1. `POST /v1/cli-logins {deviceName}` needs no sign-in. It returns the login's id, a user code like `WDJB-MJHT` (8 consonants, about 34 bits), the web app's verification link `…/cli-login/?code=…`, a poll token, `intervalSeconds` 3 and `expiresInSeconds` 600. The user code and the poll token are stored only as hashes.
2. A person signed in to the web app finds it with `GET /v1/cli-logins?code=` (the page needs the device's name) and approves it with `POST /v1/cli-logins/{id}/approve`. Both are browser-only. An approved login belongs to who approved it.
3. `POST /v1/cli-logins/{id}/token {pollToken}` needs no sign-in; the poll token is its credential. It is 400 `authorization-pending` until approved, then makes a `full` key named "CLI on {device}" and returns it exactly once: the key is made and the login spent in one transaction under the login's row lock, so two polls at once get one key. A spent or expired login is 400 `cli-login-expired`. An unknown login, or a wrong poll token, is 404.
4. If the answer to the poll is lost, the key can't be shown again: the CLI starts again, and the unused key is in the list to revoke.
5. The link's address is `identity.web-app-url`; the prod profile refuses plain HTTP or this machine for it (ProductionSettingsGuard).

### The renter limits

- **Requests:** 60 a minute for each signed-in person, and for each address on calls with no sign-in, in Valkey in fixed one-minute windows. Over it: 429 `rate-limited` with `Retry-After` (the seconds left in the window). A session's event stream is one request however long it stays open (only the first dispatch counts, not the one Servlet makes when the stream ends), and a log read with a cursor is free, so following a log never costs requests. Starting a CLI sign-in is limited further: 10 an hour from one address.
- **If Valkey can't be reached, the request goes ahead** and a warning is logged. Valkey is never required.
- **New sessions:** 10 an hour, and 2 for an account less than a day old: 429 `too-many-new-sessions` with `Retry-After` the time until the oldest of them is an hour old. **At once:** 3 sessions that haven't ended: 409 `too-many-sessions`.
- **These two are counted in PostgreSQL, not Valkey,** in the booking's own transaction under an advisory lock on the renter. Valkey can't know which sessions haven't ended, a limit that vanishes with a restart is no limit, and the database count never counts a retried booking (the same Idempotency-Key) twice.
- Every number is configuration (`limits.*`, `sessions.limits.*`). Tests set them low (`RenterLimitsTests`); the end-to-end harness lifts them for its own accounts, since they poll every half second.

## Alternatives considered

- **Long-lived ID tokens or the provider's refresh tokens for the CLI.** They belong to the provider (a Firebase refresh token is a way into the whole Google project), can't be listed or revoked by us, and would put a provider SDK in the CLI.
- **Keys that carry claims (signed, like JWTs).** They can't be revoked without a list anyway, and a lookup by hash is one indexed read.
- **Keeping keys in the clear, or hashing them slowly.** Clear text is a list of keys to anyone who reads the database; a slow hash protects secrets people choose, and these are random.
- **Letting keys manage keys.** A stolen key could make a second and survive its own revocation. Revoking is allowed; creating isn't.
- **A user code anyone can guess.** At 34 bits, a login that lasts 10 minutes, finding it needs a browser's sign-in, and a request limit on the address, guessing is hopeless; RFC 8628 asks for the same.
- **Counting sessions in Valkey.** See above: it can't be exact.
- **A sliding window for requests.** A fixed window is one counter and one expiry; the difference matters only at the window's edge.

## Consequences

- **Easier:** the CLI, the SDK and CI sign in without a browser token; a person sees and revokes what has access; scripts have limits that protect the control plane.
- **Harder:** every end-to-end run makes its CLI account approve a login, and the harness lifts the limits (so the end-to-end run can't show whether the web app stays under 60 requests a minute; it is measured on its own).
- **Watch:** `last_used_at` is a hint, written once a minute. The request window is fixed, so a burst across a minute boundary can reach twice the limit. Unauthenticated calls are counted by the connecting address, so behind the load balancer the forwarded headers must be trusted first (a deployment setting; open question until the deployment round).
- **Open question:** when keys expire, and whether a read key may be made from the CLI. Nothing needs either yet.
- **Open question:** nothing marks a CLI version as too old yet, so the CLI can't warn about it (docs/plan/product-surface.md 4.3).

## Addendum (2026-10-14): the key rules decide by route, and fail closed

- **The bug:** `KeyRules` matched regular expressions against the raw request URI, while Spring routes on the decoded path. A full key could reach a browser-only handler by spelling its path another way: `DELETE /%761/hosts/{id}` removed a host, and `/v1/api%2Dkeys` or `/v1/cli-logins/{id}/%61pprove` would have made a key or approved a sign-in.
- **The fix:** every browser-only handler carries `@BrowserSignInOnly` (the identity module's public annotation), and one interceptor, `AccessRules`, decides after routing, on the handler the request actually reached: a key is refused there with `browser-sign-in-required`; a read key is refused any write but revoking itself (`@ReadKeyMayRevokeItself`, decided by the route's decoded path variable). Spring Security still decides who is calling and whether their account may; only what depends on the call moved.
- **It fails closed:** a key's request that reaches no handler method (an unknown path, a static resource) is refused unless it's a `GET` or `HEAD`. `KeyRouteTests` reads every `@BrowserSignInOnly` route from Spring's own handler mapping, tries each with every letter and hyphen percent-encoded in turn, all of them in lower- and mixed-case hex, and a trailing slash, and asks Spring which of them route to the handler: every one that does is refused, and every write that doesn't is refused too.
- **The request limits decide by route as well:** `RequestLimits` reads the matched pattern, not the URI, so an encoded `POST /v1/cli-logins` counts against the hourly sign-in limit. **Log reads now always count**, against a limit of their own (`limits.log-reads-per-minute`, 120), instead of reads with a cursor being free; the CLI and the SDK read one page fewer per tick and treat a 429 on a log read as a tick to skip.
- **Production refuses lifted limits:** any of `limits.*` or `sessions.limits.*` above application.yaml's values stops the prod profile (`ProductionSettingsGuard.PRODUCTION_LIMITS`, which a test keeps equal to application.yaml).
- **A booking's Idempotency-Key** comes from what the renter asked for (the template, each input's place, files, sizes and modification times or its URL, the parameters, the answers and the limits), not from the request body, whose upload ids a re-run could change; an upload's record is kept until the booking that uses it is answered, so a re-run after a lost answer reuses the same, ready upload. The CLI and the SDK do the same; the end-to-end run drops three answers through a proxy and sees one session.
