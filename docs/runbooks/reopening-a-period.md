# Reopening a closed month

Who: the Owner, with a second admin to approve and a reason (ADR 0055). It pages everyone (`PeriodReopened`), because whoever relied on the month being final, the CA first, needs to know.

## Before you do

Almost every correction belongs in the open month instead: an adjustment that names the closed one (docs/runbooks/month-close.md, "A correction"). Reopen only when the CA says the entry must be dated in that month, for example a filing that hasn't gone yet.

## Reopening

1. On Months, the closed month shows **Reopen**. Give the reason: what must be dated in it, and the CA's instruction.
2. A second admin approves it. From then entries can be dated in the month again; the audit log has `period.reopen`, and the alert pages.
3. Make the correction.
4. Close the month again (docs/runbooks/month-close.md): the checklist has to hold once more.
5. Send the CA the corrected figures, and say what changed and why.
