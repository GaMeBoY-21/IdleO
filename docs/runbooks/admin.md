# The admin console

The console at `/admin/` runs the pilot from a browser (ADR 0027): hosts, users, invites, credit, sessions, the review queue, reconciliation and the audit log. Every action asks why and is written to the audit log in the same transaction. Anyone who isn't an admin, with an admin session, sees the same "We can't find that page" as any wrong address.

## Make someone an admin

Only an operator command can, after the person has signed in to the web app at least once with a verified email:

```
OPERATOR=<handle> java -jar control-plane.jar --spring.profiles.active=prod,ops grant-admin nikki@example.com --reason "runs the pilot"
```

Locally, against the `scripts/e2e/agent-online.sh --keep-running` stack:

```
COMPOSE_PROJECT_NAME=idleo-e2e OPERATOR=<handle> ./mvnw -q spring-boot:run -Dspring-boot.run.profiles=local,ops -Dspring-boot.run.arguments="grant-admin nikki@example.com --reason 'local admin'"
```

- It's refused (exit 3) for an email nobody has signed in with, an account that isn't active, or an email two accounts share; it names their ids.
- Granting someone who's already an admin changes nothing.
- `revoke-admin <email> --reason "<why>"` takes the role away, with their authenticator, recovery codes and every admin session.

Both are in the audit log (`admin.grant`, `admin.revoke`), with your `OPERATOR` name.

## The key that seals authenticator secrets

`IDENTITY_ADMIN_TOTP_KEY` is 32 random bytes in base64 (`openssl rand -base64 32`), or a `file:` location holding that, from the secret store. The control plane refuses to start without it.

- Locally it's `private/dev-ca/admin-totp.key`, made by `scripts/dev-ca.sh` and kept when the CA is replaced with `--force`.
- **Never change it casually.** Every enrolled authenticator was sealed with it. After a change, nobody's code works: revoke and grant each admin again, and each sets up afresh.

## First visit: set up the authenticator

1. Sign in to the web app as usual, then open `/admin/`.
2. **Set up your authenticator:** scan the QR code with an authenticator app (Google Authenticator, 1Password, Authy…), or type the secret shown under it.
   - The secret is shown only on this page. Leaving and coming back makes a new one, and the old one stops working.
3. Type the 6-digit code the app shows, and **Confirm**.
4. **Save your recovery codes:** ten codes like `K7Q2M-9XD4P`, shown once. Keep them somewhere safe that isn't your phone. Each works once instead of a code. They're stored only as a keyed hash (`IDENTITY_INVITE_CODE_KEY`); if that key changes, or after the move to keyed hashes on Oct 14 (V35), the old ones stop working, and revoking and granting the role again gives you new ones.
5. **I've saved them** opens the console.

## Every visit after: the code check

- Open `/admin/` and type the code your app shows now. **Use a recovery code instead** takes one of the ten.
- The admin session lasts 30 minutes from your last action and never more than 8 hours. It lives only in that tab: reloading, or another tab, asks for a code again.
- **Leave the console** ends it at once.
- A code works once. Within the same 30 seconds, wait for the next one.

## Locked out

**The fifth wrong code in 15 minutes locks admin access.** The console then answers "We can't find that page", and the live session ends. The lock is in the audit log as `admin.lock`, by the system.

Only an operator can undo it, and the admin sets up afresh:

```
OPERATOR=<handle> ... revoke-admin nikki@example.com --reason "locked out by wrong codes"
OPERATOR=<handle> ... grant-admin nikki@example.com --reason "back after a lockout"
```

**Lost phone:**
- With recovery codes left, use one to get in, then have the role revoked and granted to set up the new phone.
- With none left, it's the same two commands.

## What each screen does

Every action opens a sheet that says what will change and asks why, in at least 5 characters. The reason goes into the audit log with your email. A refused action says why and changes nothing.

| Screen | Shows | Actions |
| --- | --- | --- |
| Dashboard | Hosts online, sessions running, sessions today (India), settlement backlog, the latest reconciliation's mismatches, the review queue. Read every 30 s. A backlog or a mismatch is red: look into it. | None |
| Users | Search by email, name or id, and status. One user: account, last sign-in, admin, API keys, the invite that let them in, credit grants. | **Suspend** (an active account; from the next request its sign-ins and keys are refused; running sessions carry on, so force-stop those too), **Reinstate**, **Grant credit** (at most ₹5,000 a grant, ₹20,000 in a wallet), **Reverse** a grant (refused if the credit is already spent or held) |
| Hosts | Search by name, host id or owner id, and status. One host: listing, pause, agent, GPUs, certificates, status history, telemetry now. | **Activate** (refused without a valid certificate, a listing and a reported GPU), **Suspend** (no new sessions; refused at its next connection), **Revoke certificates** (its agent is cut off at once; the owner enrols it again with a new code) |
| Sessions | Search by session, renter, host, state and when booked. One session: its price, cap and charge, the timeline, and each attempt. Never its parameters or inputs. | **Force stop**: a running session stops at once, without a checkpoint, and settles like any stop. The renter is charged the minutes it ran and told "We stopped it". |
| Review queue | Minutes only the agent or only the lease saw, or that the cap or the credit couldn't pay for, oldest first. Each with its price. | **Charge** (a settlement of its own at its attempt's price; refused past the cap or the renter's credit, or for a minute given back with a lost host), **Waive** (no money moves). Each minute is decided once. |
| Disputes | Renters' reports (ADR 0028), open ones first and the oldest first. One report: why it was sent and what they wrote, the session's timeline, each minute as both witnesses saw it (with its settlements and waivers), what the renter paid and who kept what (the platform's commission, each host's share), and the renter's rating and comment. | **Refund** (an amount up to what the session cost, less anything given back; it comes out of the platform's commission first, then the hosts' shares; refused above that), **No refund**. Each report is resolved once, and the renter reads your reason, so say what you found, in plain words. |
| Money | The latest reconciliation runs (nightly at 02:30 IST and any run from here or the command), with each mismatch. The ledger by transaction id, or by account and owner. | **Run the check now** (as `reconcile-wallets`; moves no money) |
| Invites | Every code with its note, who made it, when it lapses and who used it; never the code itself. | **Make invite codes** (1 to 200; the reason is their note). The new codes are shown once, on this page. |
| Audit log | Every privileged action, newest first: admins', operators' commands, certificate issues and revocations, lockouts. Filter by who, an action or its start (`host.`, `credit.`), and target id. | None: nothing can change a row |

## When a figure needs a look

- **Settlement backlog above zero for more than a few minutes:** metering isn't settling. Check the control plane's logs for `MeteringJobs`; a backlog that clears by itself was a slow tick.
- **Reconciliation mismatches:** see docs/runbooks/granting-credits.md, "What to check afterwards". Never correct a wallet by hand: every fix is a new ledger transaction.
- **Review queue growing:** minutes pile up when agents report what leases didn't cover (or the reverse). Charge a minute only when you're sure it was worked; waive it otherwise.
- **A report waiting more than 2 days:** the renter was promised a reply within 2 days, and nothing counts it yet. Work the Disputes queue from the top: it is oldest first, with open reports before resolved ones.
- **Answering a report:** open the session's timeline and minutes before deciding. A minute only the agent saw, or one the lease saw but the agent didn't, is in Review queue, not here. A refund is real money (it credits the renter and takes the commission and, past that, the host's share, which the host sees as "Refunded to renter"), and it can't be undone; a mistake is put right with a credit grant, with its own reason. While a report is open the session's earnings show "Held from payout" on the host's screen; resolving it ends the hold.
- **A rating's comment:** only admins read it (Disputes shows the one beside a report; `GET /admin/v1/ratings` lists them). Never repeat it to a host, and never say who rated.
