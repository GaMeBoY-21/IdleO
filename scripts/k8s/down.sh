#!/usr/bin/env bash
# Deletes the Mac's cluster and its registry (ADR 0045), with the database and everything else in them. What's kept
# under private/k8s/ (the local CA, the database's passwords, logs) stays for the next up.sh.
set -euo pipefail
log() { printf 'k8s: %s\n' "$*"; }
fail() { printf 'k8s: FAIL: %s\n' "$*" >&2; exit 1; }
. "$(dirname "$0")/lib.sh"
command -v k3d >/dev/null || fail "k3d isn't installed, so there's no cluster to delete"
if cluster_exists; then
  k3d cluster delete "$cluster" >/dev/null
  log "deleted the cluster $cluster"
fi
if k3d registry list -o json 2>/dev/null | grep -q "\"name\":\"$registry_name\""; then
  k3d registry delete "$registry_name" >/dev/null
  log "deleted the registry $registry_name"
fi
rm -f "$KUBECONFIG"
