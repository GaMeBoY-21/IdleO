#!/usr/bin/env bash
# An operator command against the Mac's cluster (docs/runbooks/granting-credits.md, hosts.md, invites.md): the chart's
# operator-command pod, rendered with the installed release's own values, so it has the control plane's image,
# settings and database user; it runs the command on the ops profile, prints the result and is deleted.
#
#   OPERATOR=nikki scripts/k8s/ops.sh grant-credits --user <id> --paise 50000 --reason "Pilot credit" --key pilot-1
#
# OPERATOR is who runs it, recorded with the change (local-cluster if unset). IDLEO_DATABASE names another database
# Cluster to run it against, a restored one (ops/backup/restore.sh); the live one when unset.
set -euo pipefail
log() { printf 'k8s: %s\n' "$*" >&2; }
fail() { printf 'k8s: FAIL: %s\n' "$*" >&2; exit 1; }
. "$(dirname "$0")/lib.sh"
cd "$k8s_root"
(($# > 0)) || fail "usage: $0 <command> [arguments]"
cluster_exists || fail "there's no cluster; scripts/k8s/up.sh makes it"

pod="idleo-ops-$(openssl rand -hex 4)"
values="$(mktemp)"
trap 'rm -f "$values"; kubectl -n "$namespace" delete pod "$pod" --ignore-not-found --wait=false >/dev/null 2>&1 || true' EXIT
helm get values idleo --namespace "$namespace" -o yaml >"$values"
helm template idleo deploy/helm/idleo --namespace "$namespace" -f "$values" \
  --set operator.name="$pod" --set operator.operatorName="${OPERATOR:-local-cluster}" \
  --set operator.database="${IDLEO_DATABASE:-}" \
  --set-json "operator.args=$(python3 -c 'import json, sys; print(json.dumps(sys.argv[1:]))' "$@")" \
  --show-only templates/operator-command.yaml | kubectl -n "$namespace" apply -f - >/dev/null

deadline=$((SECONDS + 300))
until phase="$(kubectl -n "$namespace" get pod "$pod" -o jsonpath='{.status.phase}')" &&
  [[ "$phase" == Succeeded || "$phase" == Failed ]]; do
  ((SECONDS < deadline)) || fail "$1 didn't finish within 5 minutes"
  sleep 1
done
kubectl -n "$namespace" logs "$pod"
[[ "$phase" == Succeeded ]] || fail "$1 failed"
