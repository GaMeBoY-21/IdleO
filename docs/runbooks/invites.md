# Invite codes

The admin console (docs/runbooks/admin.md, Invites) makes codes through the same service and lists who used each.

While access is by invitation (ADR 0024), a new account is **waiting** after its first sign-in. A waiting account can read and rename itself and enter an invite code; every other call answers 403 `invite-required`. A code activates the account, once. Accounts that signed in before invites existed are active.

Access stays by invitation until the paid pilot. `identity.invite-only` (the environment variable `IDENTITY_INVITE_ONLY`) is `true` unless something sets it to `false`: the test profile does, and so does `scripts/e2e/agent-online.sh` (`E2E_INVITE_ONLY=true` turns it on for a run).

## Make codes

Run the control plane's operator command, in the same way as `grant-credits` (docs/runbooks/granting-credits.md):

```
OPERATOR=<you> java -jar control-plane.jar --spring.profiles.active=prod,ops create-invites --count 5 --note "hackathon" [--expires 30d]
```

- `--count`: 1 to 200 codes.
- `--note`: who they are for, such as `hackathon` or `friend`. It's kept with each code and never shown to the person.
- `--expires`: optional; a number of days or hours, like `30d` or `12h`. Without it a code never expires.

Locally, against the `scripts/e2e/agent-online.sh --keep-running` stack:

```
COMPOSE_PROJECT_NAME=idleo-e2e OPERATOR=<you> ./mvnw -q spring-boot:run -Dspring-boot.run.profiles=local,ops -Dspring-boot.run.arguments="create-invites --count 1 --note local"
```

It prints each code once, like `IDLE-7K2Q-9XMD`:

```
made 2 invite codes for "friend", each valid for 30 days
They are shown only now; only their hashes are kept. Each works once.
  IDLE-7K2Q-9XMD
  IDLE-4PRW-HT6E
```

**Copy them now.** Only a keyed hash of each is stored (HMAC-SHA-256 under `IDENTITY_INVITE_CODE_KEY`, docs/runbooks/deployment.md), so a lost code can't be looked up, and a copy of the database alone can't be used to work codes out: make another.

When the key changes, and once when codes moved from plain hashes to keyed ones (V35, Oct 14), every unused code stops working. Make new codes for anyone who was given one and hasn't used it yet.

## Hand one out

Send the code in a private message. The person types it on the page after their first sign-in; capitals, lower case, dashes and spaces don't matter.

## What the person sees

- **A wrong, used or expired code** is answered the same way each time (`invite-invalid`), so a guess learns nothing about which codes exist.
- **Five tries an hour** for each person, right or wrong. The sixth is `too-many-attempts`, even with a good code, until an hour after their earliest try in the last hour. A person who is locked out waits; nothing is needed from you.
- **A used code** records who used it and when (`identity.invite_codes.used_by`, `used_at`).

## Checks and trouble

| Question | Answer |
| --- | --- |
| Was a code used? | `select used_at, used_by from identity.invite_codes where note = '<note>'`. |
| Someone says their code doesn't work | It's wrong, used or expired, and the app can't say which. Check the note's rows above for the used and expiry times; if it's used by someone else, make a new code. |
| Someone typed a code and is still waiting | They hit the limit, or the code wasn't theirs. Ask them to try again in an hour, or give them a new code. |
| A code was sent to the wrong person | If it's unused, expire it: `update identity.invite_codes set expires_at = now() where note = '<note>' and used_at is null` (one note at a time, so you don't expire others). |
