#!/usr/bin/env bash
# The money check after a restore (ADR 0036, docs/runbooks/backups.md): run it against a restored database before
# anyone uses it. It moves no money and changes nothing; it reads the ledger's rules back from the data:
#
#   1. every ledger transaction balances: its debits equal its credits;
#   2. all debits equal all credits;
#   3. no wallet is negative, holds less than nothing, or holds more than its balance;
#   4. every wallet equals the sum of its renter_wallet and renter_purchased entries (the nightly reconciliation's
#      first check);
#   5. every wallet's reserved amount equals its active holds (its second);
#   6. every hold equals the sum of its changes in reservation_changes (its third);
#   7. no host's earnings (host_payable) are below zero;
#   8. every credit lot equals its entries, and no lot is below zero, bought or free (ADR 0039);
#   9. the entries from before lots (no lot_id) net to zero for every renter: all their credit is in a lot;
#  10. nothing frozen for a chargeback or on its way back as a refund is below zero; frozen and refunding credit
#      is outside the balance, so with check 3 nothing frozen, refunding or reserved is ever more than the renter has.
#  11. every paid top-up has exactly one credit to the renter's bought credit, and every processed refund part exactly
#      one debit from refunds_pending (ADR 0039).
#  12. nobody's payouts in flight are below zero (ADR 0040);
#  13. each person's payouts_paid equals the sum of their processed payouts;
#  14. a receivable is only ever recovered or waived, never paid out: no host_receivable in a payout's transaction,
#      and nobody's receivable below zero.
#  15. no session was charged past its spending cap: its settlements, less what waivers gave back, are within it
#      (ADR 0017), the reviewed minutes an admin charged included;
#  16. every minute marked billed had both witnesses, from the same attempt: the agent's report and the control
#      plane's lease (ADR 0017). A reviewed minute an admin charged is never marked billed.
#
#   ops/backup/verify-ledger.sh --cluster idleo-restore-<name>   a restored database in the cluster (ops/backup/restore.sh)
#   ops/backup/verify-ledger.sh --cluster idleo-database         the cluster's live one
#   ops/backup/verify-ledger.sh --container <name>               a database in a Docker container (./start.sh's stack)
#
# A cluster's is read on its primary, as its superuser, through kubectl (KUBECONFIG, the Mac's cluster's when unset;
# --namespace, default idleo). --user and --database name a container's account and database (default controlplane,
# as compose.yaml has them). Exits 0 when every check passes, 4 when one doesn't (the operator commands' code for a
# problem found), 2 on a bad command line.
set -euo pipefail

container=""
cluster=""
namespace=idleo
user="${PGUSER:-controlplane}"
database="${PGDATABASE:-controlplane}"
while (($#)); do
  case "$1" in
    --container) container="$2"; shift 2 ;;
    --cluster) cluster="$2"; shift 2 ;;
    --namespace) namespace="$2"; shift 2 ;;
    --user) user="$2"; shift 2 ;;
    --database) database="$2"; shift 2 ;;
    -h | --help) sed -n '2,36p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "verify-ledger: unknown option $1 (--help)" >&2; exit 2 ;;
  esac
done
[[ -n "$container" || -n "$cluster" ]] || { echo "verify-ledger: say which database: --cluster NAME or --container NAME" >&2; exit 2; }
if [[ -n "$cluster" ]]; then
  export KUBECONFIG="${KUBECONFIG:-$(cd "$(dirname "$0")/../.." && pwd)/private/k8s/kubeconfig}"
  primary="$(kubectl -n "$namespace" get cluster "$cluster" -o jsonpath='{.status.currentPrimary}')" ||
    { echo "verify-ledger: there's no Cluster $cluster in $namespace" >&2; exit 2; }
  database="${PGDATABASE:-controlplane}"
  target="$cluster"
  sql() { kubectl -n "$namespace" exec -i "$primary" -c postgres -- psql -X -v ON_ERROR_STOP=1 -U postgres -d "$database" -Atc "$1"; }
else
  target="$container"
  sql() { docker exec -i "$container" psql -X -v ON_ERROR_STOP=1 -U "$user" -d "$database" -Atc "$1"; }
fi

checks=(
  "each transaction balances|select count(*) from (select txn_id from billing.ledger_entries group by txn_id
     having sum(case direction when 'debit' then amount_paise else -amount_paise end) <> 0) unbalanced"
  "all debits equal all credits|select count(*) from (select 1 from billing.ledger_entries
     having coalesce(sum(case direction when 'debit' then amount_paise else -amount_paise end), 0) <> 0) total"
  "no wallet below zero or holding more than it has|select count(*) from billing.wallets
     where balance_paise < 0 or reserved_paise < 0 or reserved_paise > balance_paise"
  "every wallet equals its ledger entries|with ledger as (
       select owner_id, sum(case direction when 'credit' then amount_paise else -amount_paise end) as paise
       from billing.ledger_entries where account in ('renter_wallet', 'renter_purchased') group by owner_id)
     select count(*) from billing.wallets w full join ledger l on l.owner_id = w.user_id
     where coalesce(w.balance_paise, 0) <> coalesce(l.paise, 0)"
  "every wallet's reserved amount equals its active holds|with held as (
       select user_id, sum(amount_paise) as paise from billing.reservations where status = 'active' group by user_id)
     select count(*) from billing.wallets w full join held h on h.user_id = w.user_id
     where coalesce(w.reserved_paise, 0) <> coalesce(h.paise, 0)"
  "every hold equals its recorded changes|select count(*) from (
       select r.id from billing.reservations r left join billing.reservation_changes c on c.reservation_id = r.id
       group by r.id, r.status, r.amount_paise
       having coalesce(sum(c.after_paise - c.before_paise), 0)
              <> case when r.status = 'active' then r.amount_paise else 0 end) mismatched"
  "no host's earnings below zero|select count(*) from (
       select owner_id from billing.ledger_entries where account = 'host_payable' group by owner_id
       having sum(case direction when 'credit' then amount_paise else -amount_paise end) < 0) negative"
  "every credit lot equals its entries and none is below zero|select count(*) from (
       select l.id from billing.credit_lots l
       left join billing.ledger_entries e on e.lot_id = l.id and e.account in ('renter_wallet', 'renter_purchased')
       group by l.id, l.remaining_paise
       having l.remaining_paise < 0
           or l.remaining_paise <> coalesce(sum(case e.direction when 'credit' then e.amount_paise
                                                                 else -e.amount_paise end), 0)) mismatched"
  "credit from before lots nets to zero|select count(*) from (
       select owner_id from billing.ledger_entries
       where account in ('renter_wallet', 'renter_purchased') and lot_id is null group by owner_id
       having sum(case direction when 'credit' then amount_paise else -amount_paise end) <> 0) left_over"
  "nothing frozen or refunding below zero|select count(*) from (
       select owner_id, account from billing.ledger_entries
       where account in ('disputes_frozen', 'refunds_pending') group by owner_id, account
       having sum(case direction when 'credit' then amount_paise else -amount_paise end) < 0) negative"
  "every paid top-up credited exactly once|select count(*) from billing.topups t
     where t.status = 'paid' and (select count(*) from billing.ledger_entries e
       where e.ref_type = 'topup' and e.ref_id = t.id and e.account = 'renter_purchased' and e.direction = 'credit') <> 1"
  "every processed refund part debited exactly once|select count(*) from billing.refund_parts p
     where p.status = 'processed' and (select count(*) from billing.ledger_entries e
       where e.ref_type = 'credit_refund_processed' and e.ref_id = p.id and e.account = 'refunds_pending'
         and e.direction = 'debit') <> 1"
  "no payout in flight below zero|select count(*) from (
       select owner_id from billing.ledger_entries where account = 'payouts_in_flight' group by owner_id
       having sum(case direction when 'credit' then amount_paise else -amount_paise end) < 0) negative"
  "paid equals the sum of processed payouts|with paid as (
       select owner_id, sum(case direction when 'credit' then amount_paise else -amount_paise end) as paise
       from billing.ledger_entries where account = 'payouts_paid' group by owner_id),
     processed as (
       select user_id, sum(net_paise) as paise from payouts.payouts where status = 'processed' group by user_id)
     select count(*) from paid full join processed on processed.user_id = paid.owner_id
     where coalesce(paid.paise, 0) <> coalesce(processed.paise, 0)"
  "a receivable is only recovered, never paid out|select (select count(*) from billing.ledger_entries
       where account = 'host_receivable' and ref_type in ('payout', 'payout_paid', 'payout_returned', 'payout_reversed'))
     + (select count(*) from (
       select owner_id from billing.ledger_entries where account = 'host_receivable' group by owner_id
       having sum(case direction when 'debit' then amount_paise else -amount_paise end) < 0) negative)"
  "no session charged past its spending cap|select count(*) from metering.metered_sessions m
     where (select coalesce(sum(amount_paise), 0) from metering.settlements s where s.session_id = m.session_id)
         - (select coalesce(sum(refunded_paise), 0) from metering.waivers w where w.session_id = m.session_id)
         > m.max_spend_paise"
  "every billed minute had both witnesses|select count(*) from metering.usage_minutes
     where billed and not (agent_reported and lease_alive and agent_attempt = lease_attempt)"
)

failed=0
for check in "${checks[@]}"; do
  name="${check%%|*}"
  sql="${check#*|}"
  found="$(sql "$sql")" || {
    echo "verify-ledger: couldn't run \"$name\" against $target" >&2
    exit 1
  }
  if [[ "$found" == 0 ]]; then
    printf 'verify-ledger: ok    %s\n' "$name"
  else
    printf 'verify-ledger: FAIL  %s (%s)\n' "$name" "$found"
    failed=1
  fi
done
summary="$(sql "
  select count(distinct txn_id) || ' transactions, ' || count(*) || ' entries, '
         || coalesce(sum(amount_paise) filter (where direction = 'debit'), 0) || ' paise each side'
  from billing.ledger_entries")"
echo "verify-ledger: $summary"
if ((failed)); then
  echo "verify-ledger: the ledger's rules don't hold; don't reopen on this database (docs/runbooks/backups.md)" >&2
  exit 4
fi
echo "verify-ledger: every check passed"
