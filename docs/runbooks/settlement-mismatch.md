# A settlement mismatch

Who: Finance (`recon.read` to look, `recon.resolve` to resolve; an adjustment is `money.adjust` and another admin's approval). ADR 0055.

Every night at 04:30 India time the control plane fetches yesterday's settlements from Razorpay and every item they carried (the combined settlement reconciliation report), and matches each item with the ledger. Settlements, in the console, shows each settlement as matched, partly or unmatched, and each item that disagrees. Fetch a day again from there, with a reason, at any time: it adds nothing twice.

## What each kind means, and what to do

- **`unexpected_fee`**: Razorpay kept more than we expect (`billing.settlements.fee-basis-points`, 2%, and GST at 18% on the fee). Check the payment's method in Razorpay's dashboard: some methods cost more. If the fee is right, put the difference in the ledger with a **fee correction** (Adjustments) whose reference names the item, then resolve the item with that adjustment. If Razorpay overcharged, write to them, and resolve the item once they've refunded it, with a known difference that says so.
- **`missing_ours`**: Razorpay settled a payment, refund or deduction we have no record of. Find it in the dashboard by its id. A payment that never reached us (its webhook and Checkout's answer both lost) is credited by the payments reconciliation; once it has been, this resolves as a known difference with that said. A chargeback deduction we didn't hear of is docs/runbooks/payments.md, "Chargebacks". Anything else is for Razorpay's support.
- **`missing_theirs`**: we credited a payment more than 3 days ago and no settlement has carried it. Razorpay settles on a schedule, usually T+2 working days; across a holiday it may simply be late, and when a settlement carries it the item resolves itself (`settled_later`). Ask Razorpay after a week.
- **`amount`**: the amounts differ. Compare the payment or refund in the dashboard with ours; the difference goes into the ledger with a journal adjustment that names the item, approved by a second admin.

## Resolving

1. On Settlements, open the item.
2. Either **by an adjustment**: ask for it on Adjustments with the item's id in the reference, have it approved, then resolve the item with the adjustment's id. The item's resolution links to it.
3. Or **as a known difference**: say why, in words the CA will understand.

An item open longer than 3 days alerts (SettlementItemsStale), and every open item is in the Inbox as `settlement`. Closing a month waits until the month's items are all resolved (docs/runbooks/month-close.md).
