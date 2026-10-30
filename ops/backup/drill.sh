#!/usr/bin/env bash
# The restore drill (ADR 0036, 0047; docs/runbooks/backups.md "The drill"): proves the cluster's backups restore, to
# the second, and measures what a loss costs. On the Mac's cluster (scripts/k8s/up.sh, either way), against the live
# database, CloudNativePG's idleo-database. Each run:
#
#   1. activity: a person granted credit (ledger entries), and a base backup taken now (a Backup, the Barman Cloud
#      plugin's), so the restore starts from one of this run's;
#   2. a ticker: a row committed every second, with the WAL position it was written at, until the run ends;
#   3. a marker row, then a moment T, taken with every table locked so nothing commits across it, and each table's
#      checksum at T;
#   4. data deleted on purpose after T: the marker, and the person (their sign-in and account rows);
#   5. a restore to T (ops/backup/restore.sh) into a new Cluster: every table's checksum equals T's, the marker and the
#      person are back, the money check (ops/backup/verify-ledger.sh --cluster) passes on it, and fails once a wallet
#      is changed behind the ledger's back. Its time, from asking to the database ready, is the RTO;
#   6. what a loss of the whole database (both instances and their disks) would cost at a random moment of the next
#      minute: the newest tick in the WAL archived by then, against that moment. That is the RPO: a restore to the
#      latest point can replay no more than the archived WAL (losing one instance costs nothing: the other has every
#      commit, synchronous replication).
#
# It prints, for each run: the RPO, the RTO, and how long the base backup took. scripts/check-all.sh runs it (the drill
# suite, after scripts/k8s/up.sh); scripts/k8s/failover-test.sh runs it as its last step.
#
#   ops/backup/drill.sh               one run
#   ops/backup/drill.sh --runs 3      three in a row
#   ops/backup/drill.sh --keep        leave the last run's restored Cluster up to look at
set -euo pipefail
[[ " $* " == *" --keep "* ]] && IDLEO_VERBOSE=1
. "$(dirname "$0")/../../scripts/lib/quiet.sh"
quiet_entry drill "$@"
set -- ${QUIET_ARGS[@]+"${QUIET_ARGS[@]}"}

log() { printf 'drill: %s\n' "$*"; }
fail() {
  printf 'drill: FAIL: %s\n' "$*" >&2
  exit 1
}
. "$(dirname "$0")/../../scripts/k8s/lib.sh"
cd "$k8s_root"
root="$k8s_root"
runs=1
keep=false
while (($#)); do
  case "$1" in
    --runs) runs="$2"; shift 2 ;;
    --keep) keep=true; shift ;;
    -h | --help) sed -n '2,27p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "drill: unknown option $1 (--help)" >&2; exit 2 ;;
  esac
done
cluster_exists || fail "there's no cluster; scripts/k8s/up.sh makes it"
out="$k8s_state/logs"
state="$k8s_state/drill"
mkdir -p "$out" "$state"
in_namespace() { kubectl -n "$namespace" "$@"; }
# The stack's shared steps (scripts/lib/stack.sh), pointed at the cluster, for a person signing in.
api="$api_url"
sign_in="$sign_in_url"
grpc_port="$agents_port"
operator_name=restore-drill
# shellcheck source=scripts/lib/stack.sh
. "$root/scripts/lib/stack.sh"
export CURL_CA_BUNDLE="$local_ca/ca.pem"
primary() { in_namespace get cluster "$1" -o jsonpath='{.status.currentPrimary}'; }
# SQL as the superuser on a Cluster's primary, through kubectl (the database's own socket).
psql_on() {
  local cluster="$1"
  shift
  in_namespace exec -i "$(primary "$cluster")" -c postgres -- psql -X -v ON_ERROR_STOP=1 -U postgres -d controlplane -At "$@"
}
live() { psql_on idleo-database "$@"; }

ticker=""
restored=""
cleanup() {
  [[ -n "$ticker" ]] && kill "$ticker" 2>/dev/null || true
  live -c "select pg_terminate_backend(pid) from pg_stat_activity where query like 'call drill.tick()%'" >/dev/null 2>&1 || true
  live -c "drop schema if exists drill cascade" >/dev/null 2>&1 || true
  if [[ -n "$restored" ]] && ! $keep; then
    in_namespace delete cluster "$restored" --wait=false >/dev/null 2>&1 || true
    in_namespace delete networkpolicy "$restored" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM HUP

# The tables a restore must bring back exactly: every table but the drill's own.
tables_sql="select format('%I.%I', schemaname, tablename) from pg_tables
  where schemaname not in ('pg_catalog', 'information_schema', 'drill') order by 1"
# Each table's checksum (and row count), in one statement; its rows in a fixed order whatever their physical order.
checksums_sql() {
  local first=true table
  for table in $(psql_on "$1" -c "$tables_sql"); do
    $first || printf ' union all '
    first=false
    printf "select '%s', count(*), md5(coalesce(string_agg(md5(t::text), '' order by md5(t::text)), '')) from %s t" \
      "$table" "$table"
  done
  printf ' order by 1;\n'
}

results=()
for run in $(seq 1 "$runs"); do
  stamp="$(date -u +%Y%m%d%H%M%S)"
  # 1. Activity, and a base backup now.
  user="drill-$stamp-$run"
  person="$(curl -sf "$api/v1/me" -H "Authorization: Bearer $(stack_token "$user")" | json_get .id)" ||
    fail "the drill's person couldn't sign in"
  OPERATOR=restore-drill scripts/k8s/ops.sh grant-credits --user "$person" --paise 5000 --reason "'Restore drill $run'" \
    --key "$user" >>"$out/drill-ops.log" 2>&1 || fail "grant-credits failed; see private/k8s/logs/drill-ops.log"
  backup="drill-$stamp"
  backup_started=$SECONDS
  in_namespace apply -f - >/dev/null <<YAML
apiVersion: postgresql.cnpg.io/v1
kind: Backup
metadata:
  name: $backup
spec:
  cluster:
    name: idleo-database
  method: plugin
  pluginConfiguration:
    name: barman-cloud.cloudnative-pg.io
YAML
  deadline=$((SECONDS + 600))
  until [[ "$(in_namespace get backup "$backup" -o jsonpath='{.status.phase}')" == completed ]]; do
    [[ "$(in_namespace get backup "$backup" -o jsonpath='{.status.phase}')" == failed ]] &&
      fail "the base backup failed: $(in_namespace get backup "$backup" -o jsonpath='{.status.error}')"
    ((SECONDS < deadline)) || fail "the base backup didn't finish in 10 minutes"
    sleep 2
  done
  backup_seconds=$((SECONDS - backup_started))
  log "run $run: a person with credit; a base backup in $backup_seconds s"

  # 2. The ticker, by the database's own clock, with the WAL position of each row.
  live >/dev/null <<'SQL'
create schema if not exists drill;
create table if not exists drill.ticks (at timestamptz not null, lsn pg_lsn not null);
create table if not exists drill.markers (id uuid primary key, at timestamptz not null);
create or replace procedure drill.tick() language plpgsql as $$
begin
  loop
    insert into drill.ticks values (clock_timestamp(), pg_current_wal_insert_lsn());
    commit;
    perform pg_sleep(1);
  end loop;
end $$;
SQL
  in_namespace exec "$(primary idleo-database)" -c postgres -- psql -X -U postgres -d controlplane -c 'call drill.tick()' \
    >>"$out/drill-ticker.log" 2>&1 &
  ticker=$!

  # 3. The marker, then T: every table locked (in share mode, so nothing commits across T), T read, each checksummed.
  marker="$(python3 -c 'import uuid; print(uuid.uuid4())')"
  live -c "insert into drill.markers values ('$marker', clock_timestamp())" >/dev/null
  tables="$(live -c "$tables_sql" | paste -sd, -)"
  at_t="$( (
    echo "begin;"
    echo "lock table $tables in share mode;"
    echo "select 'T=' || to_char(clock_timestamp() at time zone 'UTC', 'YYYY-MM-DD\"T\"HH24:MI:SS.US') || '+00:00';"
    echo "select 'WAL=' || pg_walfile_name(pg_current_wal_lsn());"
    checksums_sql idleo-database
    echo "commit;"
  ) | live)"
  target="$(sed -n 's/^T=//p' <<<"$at_t")"
  wal_at_t="$(sed -n 's/^WAL=//p' <<<"$at_t")"
  grep -v '^T=\|^WAL=\|^BEGIN\|^LOCK\|^COMMIT' <<<"$at_t" >"$state/checksums-at-t"
  [[ -n "$target" && -s "$state/checksums-at-t" ]] || fail "couldn't take T's checksums"
  log "run $run: T is $target, $(wc -l <"$state/checksums-at-t" | tr -d ' ') tables checksummed"

  # 4. Data deleted on purpose, after T.
  live >/dev/null <<SQL
delete from drill.markers where id = '$marker';
delete from identity.notice_acceptances where user_id = '$person';
delete from identity.external_identities where user_id = '$person';
delete from identity.users where id = '$person';
SQL
  # T can be restored to once the WAL segment holding it is archived: within archive_timeout.
  deadline=$((SECONDS + 150))
  until [[ "$(live -c "select coalesce(last_archived_wal, '') >= '$wal_at_t' from pg_stat_archiver")" == t ]]; do
    ((SECONDS < deadline)) || fail "the WAL holding T ($wal_at_t) wasn't archived in time"
    sleep 1
  done

  # 5. Restore to T, into a new Cluster.
  restored="idleo-restore-drill-$stamp"
  to_t="$(ops/backup/restore.sh --to "$target" --name "drill-$stamp" --no-reapply 2>>"$out/drill-restore.log")" ||
    fail "the restore to T failed; see private/k8s/logs/drill-restore.log and kubectl -n $namespace describe cluster $restored"
  restore_seconds="$(sed -n 's/.* in \([0-9.]*\) s$/\1/p' <<<"$to_t")"
  checksums_sql "$restored" | psql_on "$restored" >"$state/checksums-restored"
  if ! diff -q "$state/checksums-at-t" "$state/checksums-restored" >/dev/null; then
    diff "$state/checksums-at-t" "$state/checksums-restored" | head -20 >&2
    fail "the database restored to T isn't the database at T"
  fi
  [[ "$(psql_on "$restored" -c "select count(*) from drill.markers where id = '$marker'")" == 1 ]] ||
    fail "the marker written before T isn't in the restored database"
  [[ "$(psql_on "$restored" -c "select email from identity.users where id = '$person'")" == "$user@example.com" ]] ||
    fail "the person deleted after T isn't back in the restored database"
  ops/backup/verify-ledger.sh --cluster "$restored" >"$state/verify-ledger.log" ||
    fail "the money check failed on the restored database: $(cat "$state/verify-ledger.log")"
  psql_on "$restored" -c "update billing.wallets set balance_paise = balance_paise + 1
    where user_id = (select user_id from billing.wallets order by user_id limit 1)" >/dev/null
  if ops/backup/verify-ledger.sh --cluster "$restored" >"$state/verify-ledger-tampered.log" 2>&1; then
    fail "the money check passed a wallet that doesn't match its ledger"
  fi
  log "run $run: restored to T in $restore_seconds s; all $(wc -l <"$state/checksums-at-t" | tr -d ' ') tables match, the marker and the person deleted after T are back, the money check passes (and catches a changed wallet)"

  # 6. What a loss of everything would cost at a random moment: the newest tick in the archived WAL against it. A
  #    segment's end is its start (the timeline's log and segment numbers, from its name) plus one segment.
  wait_before_loss=$((RANDOM % 60))
  log "run $run: measuring a loss in ${wait_before_loss} s"
  sleep "$wait_before_loss"
  data_lost="$(live <<'SQL'
with archived as (
  select ('x' || substr(last_archived_wal, 9, 8))::bit(32)::bigint * 4294967296
         + ('x' || substr(last_archived_wal, 17, 8))::bit(32)::bigint * setting::bigint
         + setting::bigint as wal_end
  from pg_stat_archiver, pg_settings where name = 'wal_segment_size'
)
select round(extract(epoch from clock_timestamp() - max(t.at))::numeric, 1)
from drill.ticks t, archived where (t.lsn - '0/0'::pg_lsn) < archived.wal_end
SQL
)"
  [[ -n "$data_lost" ]] || fail "no tick was in the archived WAL"
  log "run $run: data lost ${data_lost} s, restore to T ${restore_seconds} s, base backup ${backup_seconds} s"
  results+=("$(printf '%-4s %12s %12s %16s %13s' "$run" "${wait_before_loss} s" "${data_lost} s" "${restore_seconds} s" "${backup_seconds} s")")

  kill "$ticker" 2>/dev/null || true
  ticker=""
  live -c "select pg_terminate_backend(pid) from pg_stat_activity where query like 'call drill.tick()%'" >/dev/null
  live -c "drop schema drill cascade" >/dev/null
  if ! $keep || ((run < runs)); then
    in_namespace delete cluster "$restored" --wait=false >/dev/null
    in_namespace delete networkpolicy "$restored" >/dev/null 2>&1 || true
    restored=""
  fi
done

printf '\ndrill: every run restored to T exactly and passed the money check\n'
printf '  %-4s %12s %12s %16s %13s\n' run "loss after" "data lost" "restore to T" "base backup"
printf '  %s\n' "${results[@]}"
