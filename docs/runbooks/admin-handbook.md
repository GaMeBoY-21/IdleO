# The admin handbook

One page per role: what each does daily and weekly, and where. Press `?` on any screen for its keys and its runbook. Permissions are the matrix's (`control-plane/src/test/resources/admin-permission-matrix.csv`); this says what the work is.

## Owner

**Daily**
- **Control room** (`g c`): the health panel first, then money and what bookings missed. Every figure opens the list behind it.
- **Approvals** (`g a`): decide what's waiting. You can't approve your own unless you're the only Owner, which is recorded as such.

**Weekly**
- **Audit log**: verify the chain.
- **Your access**: who's an admin, who has a session open.
- **Control room, funnels**: each sign-up week's conversion.

**Monthly**
- **Months**: close the month once its checklist is clean (`month-close.md`).

**In an incident**
- **Flags**: pause what's bleeding. Pausing is immediate; running again needs another admin (`stop-the-bleeding.md`).

## Operations

**Daily**
- **Control room** (`g c`): health, supply, and the demand heatmap for what's being missed.
- **Fleet**: hosts online, their agent versions, reconnects. **Hosts** (`g h`): activate pending hosts.

**Weekly**
- **Fleet, rollouts**: widen a release when it's healthy; it pauses itself if it isn't (`rolling-out-an-agent-release.md`).
- **Notices**: schedule broadcasts for planned work.

**When needed**
- **Flags, maintenance mode** (`maintenance-mode.md`). It makes the app read-only and leaves running sessions alone.

## Support

**Daily**
- **Inbox** (`g i`): claim (`e`), reply or apply a macro (`m`), watch what's past its target. Select many with `x` and `Shift-x` for a bulk action.
- **Tickets**: the threads behind the inbox's tickets.

**Weekly**
- **Inbox, macros**: retire what's stale and write what you keep typing (`writing-a-macro.md`).

**When needed**
- **Users** (`g u`): find the person. A reveal needs a reason, and it's logged.
- **Credit**: grants go through Users (`granting-credits.md`).

## Trust & Safety

**Daily**
- **Inbox** (`g i`), filtered to abuse and reports.
- **Abuse** and **Reports**: decide each on its own. Enforcement beyond a warning is never bulk.
- **Images**: what the scanner refused or held (`image-refused.md`).

**Weekly**
- **Users, linked accounts**: signals only; they never act by themselves.
- **Appeals**: an admin who enforced can't decide its appeal while another can (`handling-an-appeal.md`).

## Finance

**Daily**
- **Money** (`g m`) and **Settlements**: reconciliation first. A mismatch has its runbook (`settlement-mismatch.md`).
- **Payments**, **Refunds**, **Chargebacks**: what needs a decision (`breaching-chargeback.md`).

**Weekly**
- **Payouts**: the batch, then its approval; **Verification** for PANs and UPI IDs (`payouts.md`).

**Monthly**
- **Months**: the checklist and the close (`month-close.md`); **Tax**: the CA's pack (`sending-the-ca-pack.md`).

## Auditor

Reads everything they're allowed to, and changes nothing: every write is refused.

**Weekly**
- **Audit log**: verify the chain (`audit-chain.md`), and read the access log.
- **Ledger** and **Months**: trial balance against what was closed.
