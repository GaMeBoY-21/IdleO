# 0014. Ledger accounts, free credits and how money is held in code

- **Status:** Accepted
- **Date:** 2026-10-06

Sources:
- ADR 0005

## Context

The product owner decided to build the money system before sessions, so that a booking can reserve credit in the same transaction from day one. There's no payment gateway yet: the only money that enters is free credit we grant to invited testers. ADR 0005 set the principles: integer paise, a double-entry ledger and two-witness billing. This ADR fixes the accounting rules every later money feature follows: bookings, settlement, top-ups and payouts.

## Decision

### The accounts

The kind of an account decides which side raises its balance:

| Account | Kind | Raised by | Owner |
| --- | --- | --- | --- |
| `renter_wallet` | Liability: we owe the renter service | Credit | The renter |
| `host_payable` | Liability: we owe the host money | Credit | The host |
| `platform_revenue` | Revenue | Credit | None |
| `gateway_clearing` | Asset: money the gateway holds for us | Debit | None |
| `promotions` | Expense: what free credit costs us | Debit | None |

- **A renter's wallet balance** is their `renter_wallet` credits minus their debits.
- **Ownership:** `renter_wallet` and `host_payable` always carry an owner; the platform accounts never do. A database `CHECK` holds both rules.
- **`promotions`** is new. The list of accounts and the account `CHECK` now include it.

### A transaction

- A ledger transaction is two or more entries under one `txn_id`, every amount above zero, and its debits equal its credits.
- Entries are never changed or deleted: a correction is a new transaction that reverses the old one.
- The database enforces all of this:
  - a deferred constraint trigger checks the balance at commit;
  - triggers refuse `UPDATE`, `DELETE` and `TRUNCATE`;
  - the unique key `(txn_id, account, owner_id)`, with nulls not distinct, means a retry can't post twice.
- **`txn_id` is derived from what caused the transaction:** a session and its settlement window (ADR 0005), a credit grant's idempotency key, or a grant being reversed. Posting the same cause again is therefore a no-op.

### Free credit

- **A grant debits `promotions` and credits `renter_wallet`.** When the renter spends it, settlement debits `renter_wallet` and credits `host_payable` and `platform_revenue`, exactly as for paid money. The host is paid real money and the platform carries the promotion's cost.
- **The ledger can always say how much of a wallet came from promotions:** those entries carry `ref_type = 'credit_grant'`, and reversals carry `'credit_grant_reversal'`. Every grant and reversal also has an append-only audit row, saying who granted it and why.
- **Open question:** which money is spent first, promotional or paid, once paid top-ups exist. It changes what a refund of paid money returns, and it must be settled before payments arrive.
- **Grants are an operator command, not an endpoint.** There are no admins, roles or admin console yet, and Phase 1's testers are a small invited group.
  - **Limits, which bound the damage of a typo:** one grant is at most ₹5,000, and a grant may not take a wallet above ₹20,000. Both are configurable.
  - **A reversal** is refused if the wallet's available balance, balance minus reserved, can't cover it. Credit already spent can't be taken back.

### The wallet cache

- `billing.wallets` caches each renter's balance and reservation.
- It's created on the renter's first posting, and changed only by the posting code, in the same transaction as the entries behind it.
- **Negative balances are refused by the database itself:** `balance_paise >= 0`, `reserved_paise >= 0` and `reserved_paise <= balance_paise`.
  - **Why a database error, not a business rule:** the design keeps a reservation ahead of usage. A booking reserves 30 minutes of credit, a settlement takes from that reservation, and the reservation is topped up after each settlement. So a settlement that would take a wallet below zero can only mean a bug, and it must fail loudly. A soft rule could quietly create debt nobody agreed to.
- **A nightly job proves the cache equals the ledger**. It also checks that the whole ledger balances.

### The wallet is closed-loop

- **What it means:** renters spend credit only on our service, and can never withdraw it as cash or send it to anyone.
- **Why it matters:** India's RBI regulates prepaid payment instruments by kind. The [Reserve Bank of India Master Directions on Prepaid Payment Instruments, 2021](https://www.rbi.org.in/scripts/bs_viewmasdirections.aspx?id=12156) (RBI/DPSS/2021-22/82, 27 August 2021, last updated 30 September 2026), paragraph 2.1, says:

  > "Closed System PPIs: These PPIs are issued by an entity for facilitating the purchase of goods and services from that entity only and do not permit cash withdrawal. These instruments cannot be used for payment or settlement for third party services. The issuance or operation of such instruments is not classified as a payment system requiring approval / authorisation by RBI and are, therefore, not regulated or supervised by RBI."

  Small and full-KYC PPIs are different: they can pay third parties, and full-KYC ones allow cash withdrawal and transfers. Those need RBI authorisation.
- **To confirm with the lawyer and CA before paid top-ups.** Renters buy compute from us, but hosts, who are third parties, perform it and are paid out of what renters spend. The closed-system definition excludes "payment or settlement for third party services", so the question is whether our structure keeps the wallet closed. Free credit carries no paid money, so it's safe meanwhile.
- **The structure to put to them:**
  - **The platform sells compute to renters, as merchant of record.** The renter's contract, price and invoice are with us, and the wallet buys only our service.
  - **Hosts are the platform's suppliers.** We buy their GPU time, and owe it to them in `host_payable`, paid out weekly from our own account, never from a renter's wallet straight to a host.

  Whether that makes the wallet a closed-system PPI, and what it means for GST, TDS on payouts and host contracts, stays an open question for the lawyer and CA.

### Money in code

- **`Paise`**, a small value type in the shared kernel, wraps a `long` count of paise. It's used everywhere money appears.
  - It has no floating point.
  - Its arithmetic can't silently overflow: it uses `Math.addExact`, `Math.subtractExact` and `Math.multiplyExact`, and throws instead.
- **The API returns integer paise only.** Formatting rupees is the web app's job.

## Alternatives considered

- **Free credit as negative revenue, or as a credit from `platform_revenue`:** rejected. It would hide what promotions cost, and make revenue reports wrong.
- **Spending promotional credit first, or paid credit first, decided now:** deferred. It only matters once paid money exists (open question above).
- **A grant endpoint behind a role:** not yet. There are no roles or admin sign-in; an operator command run against production is audited and simple.
- **Floating point, or `BigDecimal` rupees:** rejected (ADR 0005). `long` paise is exact, and `Paise` makes overflow throw.
- **Updating the wallet balance by itself, and fixing the ledger later:** rejected. The cache must never drift from the ledger without a bug, and the nightly check catches any bug.

## Consequences

- Bookings can reserve credit and settlement can post from day one, against a ledger the database itself keeps honest.
- Every paisa of free credit is traceable: its grant, who granted it, why, and its reversal if any.
- **Before paid top-ups:**
  - the RBI classification, confirmed with the lawyer and CA;
  - the spend order of promotional and paid credit, decided;
  - `gateway_clearing` entries reconciled with the gateway's settlement report.
