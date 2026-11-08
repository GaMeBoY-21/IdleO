# Stop the bleeding

Who: anyone with `flags.change` (the Owner, Operations, Finance, Trust & Safety), on the console's **Flags** screen (ADR 0056). Pausing is immediate, needs a factor in the last five minutes and a reason, and reaches every instance within seconds. Nothing a flag pauses stops a session that's already running.

## Which switch for which incident

| What's happening | Pause | Then |
|---|---|---|
| Sessions fail as they start (a bad agent or matcher release, a broken image) | `sessions.start` | Waiting bookings are held, not lost; roll back or fix, then run it again |
| Bookings shouldn't be taken (capacity, a pricing bug, fraud on bookings) | `bookings.new` | Booked sessions still start; running ones carry on |
| Payouts go wrong (wrong amounts, RazorpayX trouble, a ledger check failing) | `payouts.send` | Payouts wait as created with their money in flight; webhooks still land |
| Top-ups go wrong (a fraud wave, Razorpay trouble) | `topups` | Payments already made are still credited by their webhook |
| A bot wave of new accounts | `signups` | Everyone with an account signs in as usual |
| Bad or abusive machines linking | `hosts.link` | Linked machines carry on |
| A bad image or scanner problem | `images.custom` | Running sessions carry on |
| You need the database still (an upgrade, a restore check) | `maintenance` | maintenance-mode.md |

Pick **For how long** when you know: the switch runs again by itself then ("off for 2 h"), and renters see when we expect it back. Leave it open-ended when you don't.

## Running it again

1. On **Flags**, **Run again**, with a reason: what was fixed.
2. Another admin with `flags.change` approves it on **Approvals**. With no one else to, it runs at once (a kill switch never waits out a cooling-off: ADR 0056), and the Owners are emailed.
3. Check the thing it stopped works: a booking, a top-up, a payout sent (`payouts.send`: created payouts go within `payouts.send-every`).

## What people see

- The app shows a banner under the header with the flag's words and the expected end, and every refused request is the `paused` problem, with `Retry-After`.
- The website's footer status line says the same.
- Never an admin's reason: that stays in the audit log (`flag.pause`, `flag.resume`).

## If the flags can't be read

Each instance keeps its last good copy for two minutes, then uses each flag's safe value: `payouts.send` and `images.custom` paused, everything else running. A database outage is database.md first; the flags follow.
