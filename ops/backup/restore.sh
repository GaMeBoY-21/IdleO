#!/usr/bin/env bash
# Restores the database from its backups into a NEW CloudNativePG Cluster, never over the live one (ADR 0047,
# docs/runbooks/backups.md "Restoring"): the newest base backup from before the target, then the archived WAL replayed
# up to the target, in a Cluster of its own (idleo-restore-<name>, one instance) beside the live idleo-database. It
# archives nothing, so it can't write into the backups it came from. Run ops/backup/verify-ledger.sh --cluster against
# it before anyone uses it.
#
#   ops/backup/restore.sh --to 2026-10-20T14:05:00+05:30   to that moment (ISO 8601, with its zone)
#   ops/backup/restore.sh --to latest                         to the last archived WAL
#
# Options: --name NAME (the Cluster is idleo-restore-<NAME>; default the time now), --namespace NAMESPACE (default
# idleo), --no-reapply (leave erased accounts as the backup had them, only to compare it with the moment restored to,
# as the drill does).
#
# Once it's up, every account on the erased list is erased again (the control plane's reapply-erasures, run as an
# operator command against it, ADR 0037): a backup never brings back someone who asked to be deleted.
#
# kubectl reaches the cluster through KUBECONFIG (the Mac's cluster's, private/k8s/kubeconfig, when it's unset). The
# backups are read through the live Cluster's ObjectStore (idleo-backups), with its keys. Prints the Cluster, the base
# backup it started from and how long the restore took, from creating it to its being ready. Exits 0 once the new
# database accepts connections, out of recovery.
set -euo pipefail
cd "$(dirname "$0")/../.."
root="$(pwd)"
export KUBECONFIG="${KUBECONFIG:-$root/private/k8s/kubeconfig}"

target=""
name="$(date -u +%Y%m%dt%H%M%S)"
namespace=idleo
reapply=true
while (($#)); do
  case "$1" in
    --to) target="$2"; shift 2 ;;
    --name) name="$2"; shift 2 ;;
    --namespace) namespace="$2"; shift 2 ;;
    --no-reapply) reapply=false; shift ;;
    -h | --help) sed -n '2,22p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "restore: unknown option $1 (--help)" >&2; exit 2 ;;
  esac
done
[[ -n "$target" ]] || { echo "restore: say where to restore to: --to '<time with zone>' or --to latest" >&2; exit 2; }
if [[ "$target" != latest ]]; then
  python3 -c '
import datetime, sys
moment = datetime.datetime.fromisoformat(sys.argv[1].replace("Z", "+00:00"))
sys.exit(0 if moment.tzinfo else "restore: give the time with its zone, for example 2026-10-20T14:05:00+05:30")' "$target"
fi
in_namespace() { kubectl -n "$namespace" "$@"; }
cluster="idleo-restore-$name"
if in_namespace get cluster "$cluster" >/dev/null 2>&1; then
  echo "restore: $cluster already exists; choose another --name, or remove it (kubectl -n $namespace delete cluster $cluster)" >&2
  exit 2
fi
image="$(in_namespace get cluster idleo-database -o jsonpath='{.spec.imageName}')" ||
  { echo "restore: there's no idleo-database Cluster in $namespace to restore the backups of" >&2; exit 1; }
storage="$(in_namespace get cluster idleo-database -o jsonpath='{.spec.storage.size}')"

started_ms="$(perl -MTime::HiRes=time -e 'printf "%d", time * 1000')"
python3 - "$cluster" "$image" "$storage" "$target" <<'PY' | in_namespace apply -f - >/dev/null
import sys, yaml
cluster, image, storage, target = sys.argv[1:]
recovery = {"source": "idleo-database"}
if target != "latest":
    recovery["recoveryTarget"] = {"targetTime": target}
manifests = [
    {
        "apiVersion": "postgresql.cnpg.io/v1",
        "kind": "Cluster",
        "metadata": {"name": cluster, "labels": {"app.kubernetes.io/part-of": "idleo", "idleo.in/restore": "true"}},
        "spec": {
            "instances": 1,
            "imageName": image,
            "enableSuperuserAccess": False,
            "storage": {"size": storage},
            "resources": {"requests": {"cpu": "250m", "memory": "512Mi"}, "limits": {"memory": "1024Mi"}},
            # The live Cluster's backups, by its name: no WAL archiver of its own, so nothing is written back.
            "bootstrap": {"recovery": recovery},
            "externalClusters": [{
                "name": "idleo-database",
                "plugin": {
                    "name": "barman-cloud.cloudnative-pg.io",
                    "parameters": {"barmanObjectName": "idleo-backups", "serverName": "idleo-database"},
                },
            }],
        },
    },
    # The same doors as the live database's (deploy/helm/idleo/templates/database.yaml): its clients, itself and the
    # operator.
    {
        "apiVersion": "networking.k8s.io/v1",
        "kind": "NetworkPolicy",
        "metadata": {"name": cluster},
        "spec": {
            "podSelector": {"matchLabels": {"cnpg.io/cluster": cluster}},
            "policyTypes": ["Ingress"],
            "ingress": [
                {"from": [{"podSelector": {"matchLabels": {"idleo.in/database-client": "true"}}},
                          {"podSelector": {"matchLabels": {"cnpg.io/cluster": cluster}}}],
                 "ports": [{"port": 5432, "protocol": "TCP"}]},
                {"from": [{"namespaceSelector": {"matchLabels": {"kubernetes.io/metadata.name": "cnpg-system"}}},
                          {"podSelector": {"matchLabels": {"cnpg.io/cluster": cluster}}}],
                 "ports": [{"port": 8000, "protocol": "TCP"}]},
            ],
        },
    },
]
print(yaml.safe_dump_all(manifests))
PY

# Ready once its one instance has replayed to the target, been promoted and answers.
deadline=$((SECONDS + ${RESTORE_TIMEOUT:-1800}))
until [[ "$(in_namespace get cluster "$cluster" -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}' 2>/dev/null)" == True ]]; do
  phase="$(in_namespace get cluster "$cluster" -o jsonpath='{.status.phase}' 2>/dev/null || true)"
  if [[ "$phase" == *[Ff]ail* ]]; then
    echo "restore: $cluster failed ($phase); kubectl -n $namespace describe cluster $cluster, and the full-recovery job's log" >&2
    exit 1
  fi
  ((SECONDS < deadline)) || { echo "restore: $cluster not ready in time ($phase); kubectl -n $namespace describe cluster $cluster" >&2; exit 1; }
  sleep 2
done
took_ms=$(($(perl -MTime::HiRes=time -e 'printf "%d", time * 1000') - started_ms))
primary="$(in_namespace get cluster "$cluster" -o jsonpath='{.status.currentPrimary}')"
backup="$(in_namespace logs -l "cnpg.io/cluster=$cluster,cnpg.io/jobRole=full-recovery" --all-containers --tail=-1 2>/dev/null |
  sed -n 's/.*"backupName":"\([^"]*\)".*/\1/p;s/.*"backupId":"\([^"]*\)".*/\1/p' | head -1)"
if $reapply; then
  IDLEO_DATABASE="$cluster" OPERATOR="${OPERATOR:-restore}" "$root/scripts/k8s/ops.sh" reapply-erasures >&2 ||
    { echo "restore: erasing the erased list again failed; don't use $cluster until it has run" >&2; exit 1; }
fi
printf 'restore: %s is ready (%s), from %s to %s, in %d.%03d s\n' \
  "$cluster" "$primary" "${backup:-the newest base backup before it}" "$target" $((took_ms / 1000)) $((took_ms % 1000))
