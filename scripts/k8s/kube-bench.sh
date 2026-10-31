#!/usr/bin/env bash
# Runs kube-bench's CIS benchmark for k3s against the Mac's cluster (ADR 0048), as a one-off Job in kube-system (the one
# namespace that may read the node's own files and processes, which is what the benchmark checks), and writes its
# report to private/k8s/logs/kube-bench.txt. What it found, what was fixed and why the rest stays, is in ADR 0048; the
# server's k3s flags are deploy/k3s/server/config.yaml.
#
#   scripts/k8s/kube-bench.sh [benchmark]     k3s-cis-1.9 when not given: the newest k3s benchmark kube-bench v0.16.0
#                                             has targets for (its 1.10 and 1.11 folders have none yet)
set -euo pipefail
log() { printf 'kube-bench: %s\n' "$*"; }
fail() { printf 'kube-bench: FAIL: %s\n' "$*" >&2; exit 1; }
. "$(dirname "$0")/lib.sh"
cd "$k8s_root"
cluster_exists || fail "there's no cluster; scripts/k8s/up.sh makes it"
benchmark="${1:-k3s-cis-1.9}"
image=docker.io/aquasec/kube-bench:v0.16.0@sha256:75506f222d1eb6ce2a751a5533bdc0a3b54c898e2e49e7751d0ee22cfb862679
report="$k8s_state/logs/kube-bench.txt"
mkdir -p "$(dirname "$report")"

kubectl -n kube-system delete job kube-bench --ignore-not-found >/dev/null
python3 - "$image" "$benchmark" <<'PY' | kubectl apply -f - >/dev/null
import json, sys
image, benchmark = sys.argv[1:]
mounts = ["/var/lib/rancher", "/etc/rancher", "/var/lib/kubelet", "/etc/passwd", "/etc/group"]
name = lambda path: path.strip("/").replace("/", "-")
print(json.dumps({
    "apiVersion": "batch/v1", "kind": "Job", "metadata": {"name": "kube-bench", "namespace": "kube-system"},
    "spec": {"backoffLimit": 0, "ttlSecondsAfterFinished": 600, "template": {"spec": {
        "hostPID": True, "restartPolicy": "Never", "automountServiceAccountToken": False,
        "containers": [{"name": "kube-bench", "image": image,
                        "command": ["kube-bench", "run", "--benchmark", benchmark],
                        "volumeMounts": [{"name": name(p), "mountPath": p, "readOnly": True} for p in mounts]}],
        "volumes": [{"name": name(p), "hostPath": {"path": p}} for p in mounts]}}}}))
PY
deadline=$((SECONDS + 300))
until [[ -n "$(kubectl -n kube-system get job kube-bench -o jsonpath='{.status.succeeded}{.status.failed}')" ]]; do
  ((SECONDS < deadline)) || fail "kube-bench didn't finish within 300 s"
  sleep 2
done
kubectl -n kube-system logs job/kube-bench >"$report"
kubectl -n kube-system delete job kube-bench >/dev/null
log "the report is in private/k8s/logs/kube-bench.txt:"
grep -E '^\[(FAIL|WARN)\]|checks (PASS|FAIL|WARN|INFO)' "$report" || true
