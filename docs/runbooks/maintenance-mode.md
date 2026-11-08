# Maintenance mode

Who: anyone with `flags.change`, on the console's **Flags** screen (ADR 0056). It's the `maintenance` flag: **Start maintenance**, with a reason and how long you expect it to take.

## What it does

The app goes read-only: every `/v1` write gets 503 `maintenance` with Retry-After from your expected end, and the app and the website say so with that time. Reads go on.

What keeps working, every time (`MaintenanceModeTests` sends each):

- the admin console;
- Razorpay's and RazorpayX's webhooks, and Resend's email events: money and mail are never lost;
- the agents' streams, running sessions and metering: minutes go on being metered and billed;
- signing in, in the browser and from the command line;
- a renter stopping or cancelling their session, and confirming a top-up they've already paid;
- cancelling a scheduled deletion.

## Starting

1. Say in a ticket what you'll do and for how long.
2. On **Flags**, **Start maintenance**: choose how long, give the reason. Choosing a time makes it end by itself then; leave it open-ended if the work might overrun.
3. Check: `curl -s $API/v1/public/status` says `"maintenance": true`, and a write (say `PUT /v1/wallet/billing-details`) answers 503.
4. Do the work.

## Ending

1. On **Flags**, **End maintenance**, with what was done. Another admin approves; with no one else to, it ends at once.
2. Check a write works again, and the banner is gone (`GET /v1/public/status` shows nothing paused).

## If it overruns

Pause it again with a later end: pausing a paused flag changes its time and reason, and the banner follows.
