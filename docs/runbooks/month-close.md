# Closing a month

Who: the Owner, with a second admin to approve (ADR 0055). Months are India's. Close a month once it's over and the CA has what they need from it, usually in the first week of the next.

## The checklist

The console's Months screen lists each month with its checklist, as it stands now:

- **The ledger's checks pass:** a run of the ledger's checks finished after the month ended and found nothing. If the latest run is older, run them now from Money, "Run the checks now", with a reason. If they find something, docs/runbooks/alerts.md, "Ledger checks failing", says what each check guards; put it right before closing.
- **No open payment reconciliation items:** nothing the payments reconciliation found about the month's days is still open (Payments, "What disagrees"). Each is fixed by a later run, or accepted with a reason (docs/runbooks/payments.md, "Reconciliation").

Each later step (settlements, the payout batch, the CA's pack) adds its own item when it's built; the screen shows whatever is declared.

## Closing

1. On Months, the month that's over shows **Close** once every item holds.
2. Give the reason (for example "the CA has October") and ask. It needs a factor in the last five minutes; the console asks for one.
3. A second admin approves it on Approvals. In solo mode it runs after its delay instead, and the Owner is told first.

From then the database refuses any ledger entry dated in the month: a trigger on `billing.ledger_entries`, so no code path, script or statement can write into it.

## A correction

Something wrong in a closed month is put right in the open month, never in the closed one:

1. On Adjustments, choose the template that fits (a fee correction, a write-off of up to ₹100, a goodwill credit) or a journal entry, and give its legs.
2. Fill **Corrects a closed month** with the month (YYYY-MM), and the reference (the ticket or the reconciliation item).
3. A second admin approves it, seeing every leg. It posts in the open month and names the closed one; the CA sees both.

## Reopening

Only when a correction can't wait for the next month and must be dated in the closed one: docs/runbooks/reopening-a-period.md.
