# The payer pepper

A UPI payment keeps a keyed hash of the payer's UPI ID in `billing.payer_hashes`: HMAC-SHA-256 under a secret pepper. It's never a plain hash, since UPI IDs are easy to guess, and the ID itself is never stored. It's used only for the "same payer" signal on a person's Linked accounts tab, and is kept as long as the payment record.

Without a pepper, no hash is kept and the signal is off. The control plane says so in its log at start, and everything else works.

## Turning it on

The owner of the environment's age key does this, for staging and then production.

1. Make a pepper: `openssl rand -base64 32`.
2. Add it to `deploy/clusters/<env>/secrets/idleo/control-plane.yaml` with `sops`, as `BILLING_PAYER_PEPPER`. Never in plain text, and never the same value in two environments.
3. Deploy the change through a pull request as usual (docs/runbooks/deploying.md).
4. After the next UPI payment, check that a row appeared: `select key_version, count(*) from billing.payer_hashes group by 1`.

Payments made before this have no hash: the UPI ID was never kept, so they can't be filled in.

## Rotating it

Rotate if the pepper may have leaked, or on the schedule the other secrets follow. Old hashes stay as they are, under their version, since the IDs aren't kept to hash again.

1. Make a new pepper as above.
2. Set, in the same SOPS file:
   - `BILLING_PAYER_PEPPER` to the new pepper;
   - `BILLING_PAYER_PEPPER_VERSION` to the old version plus one;
   - `BILLING_PAYER_PREVIOUS_PEPPER` to the old pepper;
   - `BILLING_PAYER_PREVIOUS_PEPPER_VERSION` to the old version.
3. Deploy. Each new payment now keeps one row per pepper, so it still links to hashes made under the old one.

**If the old pepper leaked,** remove the previous pepper in the same change. New payments then link only to each other. Delete nothing: the old rows are only readable by someone who has both the pepper and a guess at the ID.

**If it didn't leak,** keep the previous pepper for as long as you want old payments to link to new ones. Removing it later ends that link for good.

The audit log doesn't change. Never put a pepper or a UPI ID in a ticket, a log or a note.
