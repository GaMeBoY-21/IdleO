# Granting and reversing free credits (by Nikki)

The admin console (docs/runbooks/admin.md, Users and Money) grants, reverses and runs the wallet check through the same services, from a browser.

Free credit is the only money that enters the platform until payments exist (ADR 0014). It's granted by an operator command, not a web page:
- every grant and reversal is a balanced ledger transaction;
- every grant and reversal is an append-only audit row, saying who did it and why;
- every command is safe to re-run.

There are no admins or roles yet, so whoever can run the control plane with production's settings can grant credit. Keep that set of people to you.

## The commands

```
OPERATOR=<handle> <control plane> --spring.profiles.active=<prod or local>,ops \
  grant-credits --user <user id> --paise <n> --reason "<why>" --key <idempotency key>

OPERATOR=<handle> <control plane> --spring.profiles.active=<prod or local>,ops \
  reverse-grant --grant <grant id> --reason "<why>"
```

- **`OPERATOR`** is required. It's recorded as `granted_by` or `reversed_by`, so use your own name.
- **`--user`** is our user id: a UUID, never an email.
- **`--paise`** is the amount in paise. ₹500 is `50000`.
- **`--key`** makes the grant safe to repeat. Use one key per intended grant, for example `pilot-welcome-<user id>`.
  - **Same key, same user and amount:** prints `already granted` with the original grant and changes nothing.
  - **Same key, anything else:** refused.
- **The `ops` profile** runs one command, prints the result and exits. It starts no web or gRPC server, so it can run beside a live control plane.

**Limits**, which stop a typo from doing damage. Both are configurable as `billing.credits.max-grant-paise` and `billing.credits.max-wallet-paise`:
- one grant gives at most ₹5,000 (`500000` paise);
- a grant is refused if it would take the wallet above ₹20,000 (`2000000` paise).

**Exit codes:**

| Code | Meaning |
| --- | --- |
| 0 | Done, including "already granted" and "already reversed" |
| 2 | A bad command line: a missing option, a malformed id, or `OPERATOR` not set |
| 3 | Refused, and nothing changed: a limit, an unknown user or grant, a reused key, or too little available balance to reverse |

## Locally, against the local stack

1. **Start the stack and sign in** (docs/runbooks/local-stack.md):
   ```
   scripts/e2e/agent-online.sh --keep-running
   ```
   Run the `/v1/me` curl it prints, and copy the `id` from the response: that's the user to grant to.
2. **Grant**, from `control-plane/` in a second terminal. The development build runs through Maven, which also attaches to the stack's database:
   ```
   COMPOSE_PROJECT_NAME=idleo-e2e OPERATOR=nikki ./mvnw -q spring-boot:run \
     -Dspring-boot.run.profiles=local,ops \
     -Dspring-boot.run.arguments="grant-credits --user <user id> --paise 50000 --reason 'Welcome credit' --key pilot-welcome-<user id>"
   ```
   It prints, for example:
   ```
   granted 50000 paise (₹500.00) to user 01a10f1e-…
     grant 01a10f1e-…, ledger transaction 11bd32f0-…
     by nikki at 2026-10-06T02:49:58Z, reason: Welcome credit
     wallet balance now 50000 paise (₹500.00)
   ```
   `COMPOSE_PROJECT_NAME=idleo-e2e` points it at the stack the script started. Without it, Maven would try to start a second stack.
3. **Check:** the same token with `GET /v1/wallet` shows `"availablePaise":50000`, and `GET /v1/wallet/transactions` shows one `free_credit`.

## Against production

There's no production yet. When there is, run the command on a control-plane machine with the deployment's environment, the same variables the server uses (`.env.example` lists them):

```
OPERATOR=nikki java -jar control-plane.jar --spring.profiles.active=prod,ops \
  grant-credits --user <user id> --paise 50000 --reason "Pilot tester welcome credit" --key pilot-welcome-<user id>
```

The prod profile refuses to start with any development setting (ADR 0013), so a local issuer or development hosts can't sneak in.

## What to check afterwards

1. **The printed line:** the right user, the right amount (paise and rupees side by side), and your name.
2. **The renter's view:** with the renter's own sign-in, `GET /v1/wallet` and `GET /v1/wallet/transactions`. Or, read-only against the database:
   ```sql
   select id, user_id, amount_paise, reason, granted_by, created_at
   from billing.credit_grants order by created_at desc limit 5;
   ```
3. **The next morning:** the nightly reconciliation (02:30 IST) reports `billing.reconciliation.mismatches` as 0, and has updated `billing.reconciliation.last_success`. To check at once instead, run the same check as an operator command; it changes nothing:

   ```
   OPERATOR=<handle> java -jar control-plane.jar --spring.profiles.active=prod,ops reconcile-wallets
   ```

   It prints the ledger's debit and credit totals and any mismatch (its kind, the user id, the cached and expected paise), then exits 0 for none, 4 for any, or 3 if another instance is running the check.

## Reversing a grant

A mistaken grant is never edited or deleted, and the database refuses both anyway. Reverse it with a new, opposite transaction:

```
OPERATOR=nikki <control plane> --spring.profiles.active=prod,ops \
  reverse-grant --grant <grant id> --reason "Granted to the wrong account"
```

- The grant id is in the grant's printed output and in `billing.credit_grants`.
- **What it posts:** it debits the renter's wallet, credits `promotions`, and writes an audit row in `billing.credit_grant_reversals`.
- **Running it again** prints `already reversed` and changes nothing.
- **Refused if the wallet's available balance (balance minus reserved) can't cover it.** Credit already spent or reserved can't be taken back. Wait for any session to end and release its reservation, then try again. If the credit was spent, it stays spent; record that, and don't grant more to make up for it.

The grant and its reversal both stay in the ledger and the audit tables forever. The renter sees `free_credit` and then `free_credit_reversed`.
