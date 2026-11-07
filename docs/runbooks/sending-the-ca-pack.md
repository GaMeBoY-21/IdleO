# Sending the CA pack

Who: Finance or the Owner (`tax.export`), with a second admin to approve. ADR 0055. Once a month, after the month ends and before it's closed: the month-close checklist waits for the pack (docs/runbooks/month-close.md).

## Making it

1. In the console's Tax, set the month (YYYY-MM) and **Ask for the month's CA pack**, with a reason. A factor is asked for.
2. A second admin approves it on Approvals.
3. Back on Approvals, **download** it. It's made then, from the ledger, and can be downloaded once; ask again for another copy (each is audited).

The zip holds the trial balance at the month's end, the journal, the invoice register, credit notes, GSTR-1's data (JSON for the GST portal's offline tool), the GST summary, the payouts, the month's TDS and the settlement reconciliation, and `manifest.json`: each file's SHA-256, size and what it adds up to. The manifest's own SHA-256 is in the audit log (`tax.ca_pack`) and in Tax, **Packs made**.

The quarterly files (the quarter's TDS deductions, 26Q's data and Form 16A's data) aren't in the monthly pack: ask for each from Tax with any month of the quarter, once the quarter ends.

## Sending it

- Through the company's secure channel to the CA, never email: the files hold whole PANs and GSTINs.
- Say which month, and give the manifest's SHA-256 so the CA can check what they received (`shasum -a 256 manifest.json`, and each file against the manifest).
- Keep nothing on a laptop afterwards: the pack can be made again at any time.

## Before the first one

Every format waits on the CA's confirmation (CA(confirm) in ADR 0055): the SAC code (`INVOICING_SAC`), the place of supply, the TDS rates (`legal.tds`), and whether TCS under section 52 applies. Send the CA a pack from a test month first and ask them to confirm each.
