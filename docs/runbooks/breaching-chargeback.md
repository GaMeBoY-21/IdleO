# A breaching chargeback

The alert `ChargebackEvidenceDue` pages when a chargeback isn't settled and Razorpay's evidence deadline is within 48 hours, or past. The date is Razorpay's and doesn't move. Evidence after it means the money is lost.

## Do

1. Open the console's **Inbox**, kind **Chargebacks**. The soonest due is at the top. **Claim it**, so no one else works it too.
2. Open it. Its screen shows the payment, what we hold as evidence (the sessions it paid for, what ran, the invoices) and the date.
3. In **Razorpay's dashboard**, on the dispute, submit that evidence. Nothing here submits it for you.
4. Its state changes when Razorpay's webhook arrives, and the item then leaves the inbox. If it doesn't within a few minutes, check that the webhook is arriving (`docs/runbooks/payments.md`).

## If the date has passed

Submit anyway if Razorpay still accepts it. If not, it will settle as lost. Credit already frozen stays frozen until it does, and the loss goes through the ledger as usual (`docs/runbooks/payments.md`).

## Check afterwards

The alert clears when no chargeback is inside its window. If it stays, one still is: look at Inbox, Breaching soon.
