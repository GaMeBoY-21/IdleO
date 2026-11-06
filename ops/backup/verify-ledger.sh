#!/usr/bin/env bash
# The money check after a restore (ADR 0036, docs/runbooks/backups.md): run it against a restored database before
# anyone uses it. It moves no money and changes nothing: it runs the ledger's checks, billing.ledger_checks() (V78), the
# same ones the control plane runs every night and an admin runs from the console (ADR 0055), in a read-only transaction.
# They are, in order:
#
#   1. each transaction balances: its debits equal its credits;
#   2. all debits equal all credits;
#   3. no wallet is negative, holds less than nothing, or holds more than its balance;
#   4. every wallet equals the sum of its renter_wallet and renter_purchased entries;
#   5. every wallet's reserved amount equals its active holds;
#   6. every hold equals the sum of its changes in reservation_changes;
#   7. no host's earnings (host_payable) are below zero;
#   8. every credit lot equals its entries, and no lot is below zero, bought or free (ADR 0039);
#   9. the entries from before lots (no lot_id) net to zero for every renter: all their credit is in a lot;
#  10. nothing frozen for a chargeback or on its way back as a refund is below zero;
#  11. every paid top-up has exactly one credit to the renter's bought credit (ADR 0039);
#  12. every processed refund part has exactly one debit from refunds_pending;
#  13. nobody's payouts in flight are below zero (ADR 0040);
#  14. each person's payouts_paid equals the sum of their processed payouts;
#  15. a receivable is only ever recovered or waived, never paid out, and nobody's is below zero;
#  16. no session was charged past its spending cap, the reviewed minutes an admin charged included (ADR 0017);
#  17. every minute marked billed had both witnesses, from the same attempt (ADR 0017);
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
    -h | --help) sed -n '2,32p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
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

# One -c string runs as one transaction, so the checks run read-only and psql prints the last command's rows.
results="$(sql "set transaction read only; select check_number || '|' || name || '|' || found from billing.ledger_checks()")" || {
  echo "verify-ledger: couldn't run the ledger's checks against $target" >&2
  exit 1
}
failed=0
while IFS='|' read -r _ name found; do
  if [[ "$found" == 0 ]]; then
    printf 'verify-ledger: ok    %s\n' "$name"
  else
    printf 'verify-ledger: FAIL  %s (%s)\n' "$name" "$found"
    failed=1
  fi
done <<<"$results"
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
