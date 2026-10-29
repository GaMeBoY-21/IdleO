#!/usr/bin/env bash
# What the Mac's cluster runs (ADR 0045): its pods, the memory each uses and the node's, and Docker's view of the
# cluster's containers, which is what the Mac pays for it.
set -euo pipefail
log() { printf 'k8s: %s\n' "$*"; }
fail() { printf 'k8s: FAIL: %s\n' "$*" >&2; exit 1; }
. "$(dirname "$0")/lib.sh"
cluster_exists || fail "there's no cluster; scripts/k8s/up.sh makes it"
kubectl -n "$namespace" get pods -o wide
echo
echo "Memory in use, by pod (the metrics server's, about a minute behind):"
kubectl top pods --all-namespaces --sort-by=memory 2>/dev/null || echo "  (not yet measured; try again in a minute)"
echo
kubectl top nodes 2>/dev/null || true
echo
echo "The cluster's containers, as Docker counts them:"
docker stats --no-stream --format '  {{.Name}}  {{.MemUsage}}  CPU {{.CPUPerc}}' $(docker ps -q --filter "name=k3d-")
