#!/usr/bin/env bash
# The proof of the cluster's security (ADR 0048; .github/workflows/security.yml runs it on pull requests that touch
# deploy/, and weekly), on the Mac's cluster under Flux, as production runs. It assumes a pod has been broken into and
# tries what the attacker would; every attempt must fail, and each "unreachable" has a control showing the same
# address answers someone allowed to reach it, so a refusal means the policy and not an empty port.
#
#   1. an image with our name that the images workflow didn't sign is refused; staging's real one is admitted, and
#      refused under another workflow's identity; kyverno test on staging's policies as Flux builds them;
#   2. an image by tag, not digest, is refused (a pod, and a debug container);
#   3. a root, a privileged and a hostPath pod in our namespace are refused;
#   4. from the web pod: PostgreSQL, Valkey, the control plane's meters, DNS and the internet are unreachable;
#   5. from the scanner pod: PostgreSQL, Valkey and the control plane's web and meters ports are unreachable; its
#      internal API and the internet on HTTPS, which the table allows, are reachable;
#   6. from every pod of ours: the cloud's metadata address and the node's own API port, a private address, are
#      unreachable;
#   7. from the control plane: PostgreSQL, Valkey and the internet on HTTPS are reachable (it still works);
#   8. Valkey refuses a connection without its password, and a wrong one; its user can't run KEYS, FLUSHALL, CONFIG
#      or DEBUG;
#   9. no pod of ours has a service-account token, and none of our namespace's service accounts can read Secrets or
#      make pods (every service account's rights are written to private/k8s/logs/security-rbac.txt);
#  10. a Secret is encrypted in k3s's datastore on disk (a ConfigMap beside it is the control: it's there in plain);
#  11. Kyverno killed together with a control-plane pod: while Kyverno is down a pod is refused (fail closed), the
#      remaining control plane serves (a request in flight as the pod dies may fail; none after), and both come back;
#      timed.
#  12. Kyverno cut off from the internet (GHCR, Rekor, Sigstore's TUF mirror: everything) and restarted, the worst
#      case: a digest a verified pod controller already names is admitted in under a second; a never-seen unsigned
#      digest is refused, and so is a never-seen signed one, with a message that says why; a killed PostgreSQL primary
#      fails over and is rebuilt, and a killed control plane comes back; reconnected, the signed digest is admitted.
#  13. a new pod's first moments: fresh pods that start as ours do (waiting for their network policy) reach nothing
#      from their first instruction, not the internet, the metadata address, the node's kubelet or PostgreSQL, where
#      the same pods without the wait do; every pod of ours and Kyverno's waits first.
#
# It prints a table, PASS or FAIL a row; one FAIL exits non-zero. Logs: private/k8s/logs/security-test.log.
#
#   scripts/k8s/security-test.sh            up.sh --gitops first (push the branch), then every check
#   scripts/k8s/security-test.sh --no-up    on the cluster that's there (under Flux)
set -euo pipefail
. "$(dirname "$0")/../lib/quiet.sh"
quiet_entry k8s-security-test "$@"
set -- ${QUIET_ARGS[@]+"${QUIET_ARGS[@]}"}
log() { printf 'security-test: %s\n' "$*"; }
fail() { printf 'security-test: FAIL: %s\n' "$*" >&2; exit 1; }
. "$(dirname "$0")/lib.sh"
cd "$k8s_root"
out="$k8s_state/logs"
mkdir -p "$out"
started_at=$SECONDS

if [[ "${1:-}" != --no-up ]]; then
  log "bringing the cluster up under Flux (up.sh --gitops)"
  scripts/k8s/up.sh --gitops || fail "up.sh --gitops failed; see private/k8s/logs/up.log"
fi
kubectl get namespace flux-system >/dev/null 2>&1 || fail "this cluster isn't under Flux: run up.sh --gitops (or without --no-up)"
in_namespace() { kubectl -n "$namespace" "$@"; }
export CURL_CA_BUNDLE="$local_ca/ca.pem"

rows=()
failures=0
# A row of the table: check, attempt, what happened, and whether that's what must happen.
row() {
  rows+=("$1|$2|$3|$4")
  printf 'security-test: %s %s %s: %s\n' "$4" "$1" "$2" "$3"
  [[ "$4" == PASS ]] || failures=$((failures + 1))
}
expect() { if [[ "$3" == "$4" ]]; then row "$1" "$2" "$3" PASS; else row "$1" "$2" "$3 (must be $4)" FAIL; fi; }

# --- Admission (1-3): server-side dry runs, which go through every admission check and run nothing. ---------------

ours_signed="$(python3 - <<'PY'
import re
text = open("deploy/clusters/staging/idleo/release.yaml").read()
print("ghcr.io/gameboy-21/idleo-control-plane@" + re.search(r"control-plane: (sha256:[0-9a-f]{64})", text).group(1))
PY
)"
# A real digest under our name that nobody signed: that build's CycloneDX attestation, as the tests use.
ours_unsigned="$(sed -n 's/^ *image: \(ghcr\.io\/gameboy-21\/idleo-control-plane@sha256:644720[0-9a-f]*\)$/\1/p' \
  deploy/policies/tests/resources.yaml | head -1)"
valkey_image="$(in_namespace get pod idleo-valkey-0 -o jsonpath='{.spec.containers[0].image}')"
# A pod that passes Pod Security "restricted" in every way but the one $3 (a JSON patch of its spec) changes.
pod() {
  python3 - "$1" "$2" "${3:-[]}" <<'PY'
import json, sys
name, image, patch = sys.argv[1], sys.argv[2], json.loads(sys.argv[3])
pod = {"apiVersion": "v1", "kind": "Pod", "metadata": {"name": name},
       "spec": {"automountServiceAccountToken": False,
                "securityContext": {"runAsNonRoot": True, "runAsUser": 10001, "seccompProfile": {"type": "RuntimeDefault"}},
                "containers": [{"name": "main", "image": image, "securityContext": {
                    "allowPrivilegeEscalation": False, "readOnlyRootFilesystem": True, "capabilities": {"drop": ["ALL"]}}}]}}
for path, value in patch:
    target = pod["spec"]
    *parents, last = path
    for key in parents:
        target = target[key]
    target[last] = value
print(json.dumps(pod))
PY
}
# Admits the pod on stdin into namespace $1 (a dry run) and prints "admitted", or who refused it and why.
admit() {
  local answer
  if answer="$(kubectl -n "$1" create --dry-run=server -f - 2>&1)"; then echo admitted; else
    sed -e 's/^Error from server ([A-Za-z]*): //' -e 's/^error when creating "STDIN": //' <<<"$answer" | head -1
  fi
}
refused_by() { [[ "$1" != admitted && "$1" == *"$2"* ]] && echo refused || echo "$1"; }

log "1-3. admission: signatures, digests and privileges"
answer="$(pod unsigned "$ours_unsigned" | admit "$namespace")"
expect 1 "our name, not signed by the images workflow" "$(refused_by "$answer" "idleo-images-signed: no signature")" refused
answer="$(pod signed "$ours_signed" | admit "$namespace")"
expect 1 "staging's control plane, signed with its bill of materials and provenance" "$answer" admitted
# The same image under another workflow's identity: a copy of the live policy that wants release.yml, in a namespace
# of its own, refuses it.
kubectl create namespace security-test --dry-run=client -o yaml |
  kubectl label --local -f - pod-security.kubernetes.io/enforce=restricted -o yaml | kubectl apply -f - >/dev/null
kubectl get imagevalidatingpolicy idleo-images-signed -o json | python3 -c '
import json, sys
p = json.load(sys.stdin)
p["metadata"] = {"name": "security-test-other-identity"}
p["spec"]["matchConstraints"]["namespaceSelector"] = {"matchLabels": {"kubernetes.io/metadata.name": "security-test"}}
for a in p["spec"]["attestors"]:
    for i in a["cosign"]["keyless"]["identities"]:
        i["subjectRegExp"] = i["subjectRegExp"].replace("images\\.yml", "release\\.yml")
p.pop("status", None)
print(json.dumps(p))' | kubectl apply -f - >/dev/null
deadline=$((SECONDS + 60))
until [[ "$(kubectl get imagevalidatingpolicy security-test-other-identity \
  -o jsonpath='{.status.conditionStatus.ready}' 2>/dev/null)" == true ]] || ((SECONDS > deadline)); do sleep 2; done
sleep 5
answer="$(pod signed "$ours_signed" | admit security-test)"
expect 1 "staging's control plane under another workflow's identity" \
  "$(refused_by "$answer" "idleo-images-signed: no signature")" refused
kubectl delete imagevalidatingpolicy security-test-other-identity >/dev/null
kubectl delete namespace security-test --wait=false >/dev/null
policies="$(mktemp)"
kubectl kustomize deploy/policies/base >"$policies"
if scripts/deploy/policy-test.sh "$policies" >>"$out/security-test.log" 2>&1; then
  expect 1 "kyverno test on staging's policies (28 expectations, signatures against GHCR)" passed passed
else
  expect 1 "kyverno test on staging's policies" failed passed
fi
rm -f "$policies"

answer="$(pod by-tag "${valkey_image%@*}" | admit "$namespace")"
expect 2 "an image by tag, not digest" "$(refused_by "$answer" "pinned by digest")" refused
answer="$(pod root "$valkey_image" '[[["securityContext","runAsUser"],0],[["securityContext","runAsNonRoot"],false]]' | admit "$namespace")"
expect 3 "a root pod" "$(refused_by "$answer" "PodSecurity")" refused
answer="$(pod privileged "$valkey_image" '[[["containers",0,"securityContext"],{"privileged":true}]]' | admit "$namespace")"
expect 3 "a privileged pod" "$(refused_by "$answer" "privileged")" refused
answer="$(pod host-path "$valkey_image" '[[["volumes"],[{"name":"node","hostPath":{"path":"/var/lib/rancher"}}]]]' | admit "$namespace")"
expect 3 "a pod mounting the node's folder" "$(refused_by "$answer" "hostPath")" refused
web_pod="$(in_namespace get pods -l app.kubernetes.io/name=web -o jsonpath='{.items[0].metadata.name}')"
answer="$(kubectl -n "$namespace" debug "pod/$web_pod" --image="${valkey_image%@*}" --profile=restricted \
  --container=by-tag -- true 2>&1 || true)"
expect 2 "a debug container by tag in the web pod" "$(refused_by "$answer" "pinned by digest")" refused

# --- The network (4-7): a debug container in the pod shares its network, so its policies are the pod's. -----------

# Runs bash script $3 (with the rest as its arguments) in a debug container in pod $2 of namespace $1, and prints what
# it printed. The image is Valkey's, already on the node and allowed by every policy; it has bash and valkey-cli. It
# runs as a user of its own, for pods (Kyverno's) that want a non-root user without naming one.
debug_user="$(mktemp)"
trap 'rm -f "$debug_user"' EXIT
echo '{"securityContext": {"runAsUser": 65532}}' >"$debug_user"
debug_run() {
  local ns="$1" target="$2" script="$3" container
  shift 3
  container="probe-$(openssl rand -hex 3)"
  kubectl -n "$ns" debug "pod/$target" --image="$valkey_image" --profile=restricted --custom="$debug_user" \
    --container="$container" \
    -- bash -c "$script" probe "$@" >>"$out/security-test.log" 2>&1 || { echo "debug container refused"; return; }
  local deadline=$((SECONDS + 120))
  until [[ -n "$(kubectl -n "$ns" get pod "$target" \
    -o jsonpath="{.status.ephemeralContainerStatuses[?(@.name==\"$container\")].state.terminated.reason}")" ]]; do
    ((SECONDS < deadline)) || { echo "debug container didn't finish"; return; }
    sleep 1
  done
  kubectl -n "$ns" logs "$target" -c "$container"
}
# Tries each name=address:port from pod $2 (namespace $1) and prints "name open" or "name closed" (refused, dropped or
# nothing within 3 s).
probe() {
  local ns="$1" target="$2"
  shift 2
  debug_run "$ns" "$target" 'for t in "$@"; do n="${t%%=*}"; a="${t#*=}"
    if timeout 3 bash -c "exec 3<>/dev/tcp/${a%:*}/${a##*:}" 2>/dev/null; then echo "$n open"; else echo "$n closed"; fi
    done' "$@"
}
state_of() { awk -v n="$2" '$1 == n {print $2}' <<<"$1"; }

service_ip() { kubectl -n "$1" get service "$2" -o jsonpath='{.spec.clusterIP}'; }
database="$(service_ip "$namespace" idleo-database-rw):5432"
valkey="$(service_ip "$namespace" idleo-valkey):6379"
dns="$(service_ip kube-system kube-dns):53"
control_planes=($(in_namespace get pods -l idleo.in/control-plane=true --field-selector=status.phase=Running \
  -o jsonpath='{.items[*].metadata.name}'))
((${#control_planes[@]} >= 2)) || fail "fewer than two control planes are running"
control_plane_ip="$(in_namespace get pod "${control_planes[1]}" -o jsonpath='{.status.podIP}')"
node_ip="$(kubectl get nodes -o jsonpath='{.items[0].status.addresses[?(@.type=="InternalIP")].address}')"
internet=1.1.1.1:443
metadata=169.254.169.254:80
scanner_pod="$(in_namespace get pods -l app.kubernetes.io/name=scanner -o jsonpath='{.items[0].metadata.name}')"
canary_load_pod="$(in_namespace get pods -l app.kubernetes.io/name=canary-load -o jsonpath='{.items[0].metadata.name}')"
prometheus_pod="$(kubectl -n monitoring get pods -l app.kubernetes.io/name=prometheus -o jsonpath='{.items[0].metadata.name}')"
targets=(postgres="$database" valkey="$valkey" meters="$control_plane_ip:9464" web-port="$control_plane_ip:8080"
  scanner-api="$control_plane_ip:8095" dns="$dns" internet="$internet" metadata="$metadata" node-api="$node_ip:6443")

log "4-7. the network: probing from the web, scanner, control plane, Valkey, canary load and Prometheus pods"
from_web="$(probe "$namespace" "$web_pod" "${targets[@]}")"
from_scanner="$(probe "$namespace" "$scanner_pod" "${targets[@]}")"
from_control_plane="$(probe "$namespace" "${control_planes[0]}" "${targets[@]}")"
from_valkey="$(probe "$namespace" idleo-valkey-0 "${targets[@]}")"
from_canary_load="$(probe "$namespace" "$canary_load_pod" "${targets[@]}")"
# The controls: Prometheus may reach the meters and the node's API port (it finds its targets there).
from_prometheus="$(probe monitoring "$prometheus_pod" "${targets[@]}")"
printf '%s\n' "web:" "$from_web" "scanner:" "$from_scanner" "control plane:" "$from_control_plane" "valkey:" \
  "$from_valkey" "canary load:" "$from_canary_load" "prometheus:" "$from_prometheus" >>"$out/security-test.log"

for target in postgres valkey meters dns internet; do
  expect 4 "from the web pod: $target" "$(state_of "$from_web" "$target")" closed
done
for target in postgres valkey meters web-port; do
  expect 5 "from the scanner pod: $target" "$(state_of "$from_scanner" "$target")" closed
done
expect 5 "from the scanner pod: its internal API (allowed)" "$(state_of "$from_scanner" scanner-api)" open
expect 5 "from the scanner pod: the internet on HTTPS (allowed)" "$(state_of "$from_scanner" internet)" open
for source in web scanner control_plane valkey canary_load; do
  probed="from_$source"
  for target in metadata node-api; do
    expect 6 "from the ${source/_/ } pod: $target" "$(state_of "${!probed}" "$target")" closed
  done
done
expect 6 "control: Prometheus reaches the node's API port" "$(state_of "$from_prometheus" node-api)" open
expect 6 "control: Prometheus reaches the control plane's meters" "$(state_of "$from_prometheus" meters)" open
for target in postgres valkey internet; do
  expect 7 "from the control plane: $target" "$(state_of "$from_control_plane" "$target")" open
done

# --- Valkey (8) ----------------------------------------------------------------------------------------------------

log "8. Valkey's password and its refused commands"
answers="$(debug_run "$namespace" "${control_planes[0]}" '
  echo "none $(valkey-cli -h "$1" -p "$2" ping 2>&1 | head -1)"
  echo "wrong $(valkey-cli -h "$1" -p "$2" --user idleo --pass not-the-password --no-auth-warning ping 2>&1 | head -1)"' \
  "${valkey%:*}" "${valkey#*:}")"
expect 8 "Valkey without a password" "$(awk '$1 == "none" {print $2}' <<<"$answers")" NOAUTH
expect 8 "Valkey with a wrong password" "$(grep -q "^wrong.*WRONGPASS" <<<"$answers" && echo WRONGPASS || awk '$1 == "wrong"' <<<"$answers")" WRONGPASS
expect 8 "control: its own user's PING" "$(in_namespace exec idleo-valkey-0 -c valkey -- valkey-cli --user idleo ping)" PONG
for command in "keys *" flushall "config get maxmemory" "debug sleep 0"; do
  read -r -a words <<<"$command"
  answer="$(in_namespace exec idleo-valkey-0 -c valkey -- valkey-cli --user idleo "${words[@]}" 2>&1 | head -1 || true)"
  # DEBUG is refused before the user's rights are looked at (enable-debug-command is off): refused all the same.
  [[ "$answer" == NOPERM* || "$answer" == *"command not allowed"* ]] && answer=refused
  expect 8 "its user's ${words[0]}" "$answer" refused
done

# --- Tokens and RBAC (9) -------------------------------------------------------------------------------------------

log "9. service-account tokens and what every service account may do"
tokens="$(in_namespace get pods -l app.kubernetes.io/instance=idleo -o json | python3 -c '
import json, sys
pods = json.load(sys.stdin)["items"]
with_token = [p["metadata"]["name"] for p in pods
              if p["spec"].get("automountServiceAccountToken") is not False
              or any("serviceAccountToken" in str(v.get("projected", {})) for v in p["spec"].get("volumes", []))]
print(len(pods), ",".join(with_token) or "none")')"
expect 9 "pods of ours with a service-account token (of ${tokens%% *})" "${tokens#* }" none
rbac="$out/security-rbac.txt"
: >"$rbac"
while read -r sa_namespace sa_name; do
  {
    echo "== system:serviceaccount:$sa_namespace:$sa_name"
    kubectl auth can-i --list --as="system:serviceaccount:$sa_namespace:$sa_name" -n "$namespace" 2>&1 | sed 1d
  } >>"$rbac"
done < <(kubectl get serviceaccounts -A -o jsonpath='{range .items[*]}{.metadata.namespace}{" "}{.metadata.name}{"\n"}{end}')
for sa in $(in_namespace get serviceaccounts -o jsonpath='{.items[*].metadata.name}'); do
  # CloudNativePG's instances' own account reads its Cluster's Secrets by name (the operator's, ADR 0048); every other
  # account here may do nothing.
  [[ "$sa" == idleo-database* ]] && continue
  rights="$(for verb in "get secrets" "list secrets" "create pods" "create pods/exec"; do
    if kubectl auth can-i $verb -n "$namespace" --as="system:serviceaccount:$namespace:$sa" >/dev/null 2>&1; then
      echo "$verb"
    fi
  done | paste -sd, - || true)"
  expect 9 "service account $sa: reads Secrets or makes pods" "${rights:-no}" no
done

# --- Secrets at rest (10) ------------------------------------------------------------------------------------------

log "10. a Secret in k3s's datastore on disk"
marker="security-test-$(openssl rand -hex 12)"
kubectl -n "$namespace" create secret generic security-test-at-rest --from-literal=value="$marker" >/dev/null
kubectl -n "$namespace" create configmap security-test-at-rest --from-literal=value="$marker-plain" >/dev/null
sleep 2
datastore="$(mktemp -d)"
for file in state.db state.db-wal; do
  docker cp "k3d-$cluster-server-0:/var/lib/rancher/k3s/server/db/$file" "$datastore/" >/dev/null 2>&1 || true
done
found="$(python3 - "$datastore" "$marker" <<'PY'
import glob, sys
data = b"".join(open(f, "rb").read() for f in glob.glob(sys.argv[1] + "/*"))
secret, configmap = sys.argv[2].encode(), sys.argv[2].encode() + b"-plain"
# The marker is the Secret value; the ConfigMap value starts with it, so only the excess counts as the Secret.
print("plain" if data.count(secret) > data.count(configmap) else "absent",
      "control" if configmap in data else "no-control",
      "encrypted" if b"k8s:enc:aescbc:v1:" in data else "unencrypted")
PY
)"
rm -rf "$datastore"
kubectl -n "$namespace" delete secret/security-test-at-rest configmap/security-test-at-rest >/dev/null
expect 10 "the Secret's value in the datastore" "$(cut -d' ' -f1 <<<"$found")" absent
expect 10 "control: the ConfigMap's value in the datastore" "$(cut -d' ' -f2 <<<"$found")" control
expect 10 "Secrets stored encrypted (k8s:enc:aescbc:v1)" "$(cut -d' ' -f3 <<<"$found")" encrypted
encryption="$(docker exec "k3d-$cluster-server-0" k3s secrets-encrypt status 2>/dev/null | sed -n 's/^Encryption Status: //p')"
expect 10 "k3s's secrets encryption" "$encryption" Enabled

# --- Kyverno down (11) ---------------------------------------------------------------------------------------------

log "11. Kyverno and a control-plane pod killed together"
now() { perl -MTime::HiRes=time -e 'printf "%.1f", time'; }
# The control planes that are ready, not on their way out (a hard kill sets deletionTimestamp at once).
ready_control_planes() {
  in_namespace get pods -l idleo.in/control-plane=true -o json | python3 -c '
import json, sys
print(sum(1 for p in json.load(sys.stdin)["items"] if not p["metadata"].get("deletionTimestamp")
          and any(c["type"] == "Ready" and c["status"] == "True" for c in p["status"].get("conditions", []))))'
}
victim="${control_planes[0]}"
replicas_before="${#control_planes[@]}"
requests=0
failures_at=()
killed="$(now)"
kubectl -n kyverno delete pod -l app.kubernetes.io/component=admission-controller --grace-period=0 --force \
  --wait=false >/dev/null 2>&1
in_namespace delete pod "$victim" --grace-period=0 --force --wait=false >/dev/null 2>&1
refused_while_down="" kyverno_back="" control_plane_back=""
deadline=$((SECONDS + 600))
while [[ -z "$kyverno_back" || -z "$control_plane_back" ]]; do
  ((SECONDS < deadline)) || break
  requests=$((requests + 1))
  code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$api_url/v1/public/availability" || true)"
  [[ "$code" == 200 ]] || failures_at+=("$(python3 -c "print(round($(now) - $killed, 1))") s: ${code:-none}")
  if [[ -z "$refused_while_down" && -z "$kyverno_back" ]]; then
    answer="$(pod while-down "$valkey_image" | admit "$namespace")"
    [[ "$answer" == *"failed calling webhook"* || "$answer" == *"kyverno"* ]] && refused_while_down="$answer"
  fi
  if [[ -z "$kyverno_back" ]] && [[ "$(kubectl -n kyverno get pods -l app.kubernetes.io/component=admission-controller \
    -o jsonpath='{.items[*].status.containerStatuses[0].ready}' 2>/dev/null)" == true ]]; then
    kyverno_back="$(now)"
  fi
  ready="$(ready_control_planes)"
  [[ -z "$control_plane_back" && "$ready" -ge "$replicas_before" ]] && control_plane_back="$(now)"
  sleep 1
done
refusals="$(in_namespace get events --field-selector reason=FailedCreate -o json | python3 -c '
import json, sys
print(sum(e.get("count", 1) for e in json.load(sys.stdin)["items"] if "webhook" in e.get("message", "")))')"
kyverno_took="$(python3 -c "print(round(float('${kyverno_back:-0}') - $killed, 1))")"
control_plane_took="$(python3 -c "print(round(float('${control_plane_back:-0}') - $killed, 1))")"
expect 11 "a pod while Kyverno was down" "${refused_while_down:+refused}" refused
[[ -n "$kyverno_back" ]] && row 11 "Kyverno back" "ready ${kyverno_took} s after the kill" PASS ||
  row 11 "Kyverno back" "not within 600 s" FAIL
[[ -n "$control_plane_back" ]] &&
  row 11 "the control plane back to $replicas_before ready" \
    "${control_plane_took} s after the kill ($refusals pod creations refused while Kyverno was down)" PASS ||
  row 11 "the control plane back to $replicas_before ready" "not within 600 s" FAIL
# A request in flight to the pod as it's killed can fail before its address leaves the Service (a hard kill has no
# drain); after that the other control plane must answer every one.
late="$(printf '%s\n' ${failures_at[@]+"${failures_at[@]}"} | awk -F' s:' '$1 > 5' | paste -sd, -)"
failed_list="$(printf '%s\n' ${failures_at[@]+"${failures_at[@]}"} | paste -sd, -)"
if [[ -z "$late" ]]; then
  row 11 "renters' requests while both were down ($requests)" "${#failures_at[@]} failed${failed_list:+ ($failed_list)}, none after the first 5 s" PASS
else
  row 11 "renters' requests while both were down ($requests)" "failed after the first 5 s: $late" FAIL
fi
echo "kill: kyverno ${kyverno_took} s, control plane ${control_plane_took} s, $refusals refusals, ${#failures_at[@]} of $requests failed (${failed_list:-none}); while down: $refused_while_down" \
  >>"$out/security-test.log"

# --- GHCR and Sigstore unreachable (12) ----------------------------------------------------------------------------

log "12. admission with Kyverno cut off from the internet, and restarted"
# Kyverno's network policy without its way to the internet. Flux would put it back, so it waits: the infrastructure, and
# flux-system above it, which every 10 minutes applies the infrastructure's Kustomization from git and with it clears a
# suspend kubectl set (one such run put the internet back 13 s into this check, and the new pod reached GHCR).
kyverno_policy="$(kubectl -n kyverno get networkpolicy kyverno -o json)"
reconnect_kyverno() {
  python3 -c '
import json, sys
p = json.loads(sys.argv[1])
for key in ("resourceVersion", "uid", "creationTimestamp", "managedFields", "generation"):
    p["metadata"].pop(key, None)
print(json.dumps(p))' "$kyverno_policy" | kubectl replace -f - >/dev/null
  kubectl -n flux-system patch kustomization infrastructure --type merge -p '{"spec":{"suspend":false}}' >/dev/null
  kubectl -n flux-system patch kustomization flux-system --type merge -p '{"spec":{"suspend":false}}' >/dev/null
}
trap 'reconnect_kyverno 2>/dev/null || true; rm -f "$debug_user"' EXIT
kubectl -n flux-system patch kustomization flux-system --type merge -p '{"spec":{"suspend":true}}' >/dev/null
kubectl -n flux-system patch kustomization infrastructure --type merge -p '{"spec":{"suspend":true}}' >/dev/null
python3 -c '
import json, sys
p = json.loads(sys.argv[1])
p["spec"]["egress"] = [r for r in p["spec"]["egress"]
                       if not any("except" in t.get("ipBlock", {}) for t in r.get("to", []))]
for key in ("resourceVersion", "uid", "creationTimestamp", "managedFields", "generation"):
    p["metadata"].pop(key, None)
print(json.dumps(p))' "$kyverno_policy" | kubectl replace -f - >/dev/null
# A fresh process: no connection still open from before, nothing cached, no trust root loaded.
kubectl -n kyverno rollout restart deployment/kyverno-admission-controller >/dev/null
kubectl -n kyverno rollout status deployment/kyverno-admission-controller --timeout=180s >/dev/null
# Kyverno's new pod waited for its network policy before any of its own containers started (check 13), so its very
# first connection to GHCR must fail: one try, no waiting for the block to arrive.
kyverno_pod="$(kubectl -n kyverno get pods -l app.kubernetes.io/component=admission-controller \
  --sort-by=.metadata.creationTimestamp -o jsonpath='{.items[-1:].metadata.name}')"
waited="$(kubectl -n kyverno get pod "$kyverno_pod" \
  -o jsonpath='{.status.initContainerStatuses[?(@.name=="wait-for-network-policy")].state.terminated.reason}')"
cut_off="$(debug_run kyverno "$kyverno_pod" 'timeout 3 bash -c "exec 3<>/dev/tcp/ghcr.io/443" 2>/dev/null && echo "reached GHCR" || echo "refused at once"')"
if [[ "$waited" == Completed && "$cut_off" == "refused at once" ]]; then
  row 12 "offline: Kyverno's new pod can't reach GHCR, from its first connection" "it waited for its policy; $cut_off" PASS
else
  row 12 "offline: Kyverno's new pod can't reach GHCR, from its first connection" "its wait: ${waited:-none}; $cut_off" FAIL
fi
deadline=$((SECONDS + 120))
until [[ "$(pod ready "$valkey_image" | admit "$namespace")" == admitted ]] || ((SECONDS > deadline)); do sleep 2; done

# Known: the database's image, which the control plane's Deployment names (its schema-wait init container).
known="$(in_namespace get cluster idleo-database -o jsonpath='{.spec.imageName}')"
named="$(in_namespace get deployments -o jsonpath='{range .items[*].spec.template.spec.initContainers[*]}{.image}{"\n"}{end}' | grep -cxF "$known" || true)"
# The admission request's own time, as the API server answered it (kubectl -v=6), in milliseconds: kubectl's own
# start-up isn't Kyverno's.
admit_ms() {
  pod timed "$1" | kubectl -n "$namespace" create --dry-run=server -f - -v=6 2>&1 |
    sed -n 's/.*verb="POST".*status="201 Created" milliseconds=\([0-9]*\).*/\1/p' | tail -1
}
timings=()
for attempt in 1 2 3 4 5 6; do timings+=("$(admit_ms "$known")"); done
slowest="$(printf '%s\n' "${timings[@]:1}" | sort -n | tail -1)"
if [[ -n "${timings[*]:1}" && "$(printf '%s\n' "${timings[@]}" | grep -c .)" == 6 ]]; then
  row 12 "offline: a digest $named verified Deployment(s) name, admitted" "admitted 6 of 6" PASS
else
  row 12 "offline: a digest $named verified Deployment(s) name, admitted" "admitted $(printf '%s\n' "${timings[@]}" | grep -c .) of 6" FAIL
fi
if [[ -n "$slowest" ]] && ((slowest < 1000)); then
  row 12 "offline: how long it takes" "first ${timings[0]} ms, then at most $slowest ms (${timings[*]:1} ms)" PASS
else
  row 12 "offline: how long it takes" "first ${timings[0]:-?} ms, then up to ${slowest:-?} ms (must be under 1000)" FAIL
fi
# Refused either way: with GHCR unreachable Kyverno can't even fetch the image, and says so; with only Sigstore
# unreachable it can't check the signature, and the policy's message says that.
offline_refusal() { [[ "$1" == *'Get "https://ghcr.io'* || "$1" == *"could not be reached to check one"* ]] && echo refused || echo "$1"; }
answer="$(pod unsigned "$ours_unsigned" | admit "$namespace")"
expect 12 "offline: a never-seen unsigned digest under our name" "$(offline_refusal "$answer")" refused
answer="$(pod signed "$ours_signed" | admit "$namespace")"
expect 12 "offline: a never-seen signed digest, refused and saying why" "$(offline_refusal "$answer")" refused
echo "offline refusal of a signed digest: $answer" >>"$out/security-test.log"

primary="$(in_namespace get cluster idleo-database -o jsonpath='{.status.currentPrimary}')"
killed="$(now)"
in_namespace delete pod "$primary" --grace-period=0 --force --wait=false >/dev/null 2>&1
promoted="" rebuilt=""
deadline=$((SECONDS + 300))
while [[ -z "$rebuilt" ]] && ((SECONDS < deadline)); do
  current="$(in_namespace get cluster idleo-database -o jsonpath='{.status.currentPrimary}')"
  if [[ -z "$promoted" && "$current" != "$primary" && -n "$current" ]] &&
    [[ -n "$(in_namespace get endpointslices -l kubernetes.io/service-name=idleo-database-rw \
      -o jsonpath='{.items[*].endpoints[*].addresses[0]}')" ]]; then
    promoted="$(python3 -c "print(round($(now) - $killed, 1))")"
  fi
  [[ -n "$promoted" && "$(in_namespace get cluster idleo-database -o jsonpath='{.status.readyInstances}')" == 2 ]] &&
    rebuilt="$(python3 -c "print(round($(now) - $killed, 1))")"
  sleep 1
done
if [[ -n "$rebuilt" ]]; then
  row 12 "offline: the database's primary killed" "the standby promoted with writes back at ${promoted} s; the old primary rebuilt as standby at ${rebuilt} s" PASS
else
  row 12 "offline: the database's primary killed" "promoted: ${promoted:-never}; not back to two instances within 300 s" FAIL
fi

victim="$(in_namespace get pods -l idleo.in/control-plane=true -o jsonpath='{.items[0].metadata.name}')"
before="$(ready_control_planes)"
killed="$(now)"
in_namespace delete pod "$victim" --grace-period=0 --force --wait=false >/dev/null 2>&1
back=""
deadline=$((SECONDS + 300))
while [[ -z "$back" ]] && ((SECONDS < deadline)); do
  ready="$(ready_control_planes)"
  [[ "$ready" -ge "$before" ]] && back="$(python3 -c "print(round($(now) - $killed, 1))")"
  sleep 1
done
if [[ -n "$back" ]]; then
  row 12 "offline: a control-plane pod killed" "back to $before ready at ${back} s" PASS
else
  row 12 "offline: a control-plane pod killed" "not back to $before ready within 300 s" FAIL
fi
refused_offline="$(in_namespace get events --field-selector reason=FailedCreate -o json | python3 -c '
import json, sys
print(sum(1 for e in json.load(sys.stdin)["items"] if "could not be reached" in e.get("message", "")))')"
# Everything above ran with Kyverno cut off only if the cut was still there at the end.
if [[ "$(kubectl -n kyverno get networkpolicy kyverno -o json | python3 -c '
import json, sys
print(sum(1 for r in json.load(sys.stdin)["spec"]["egress"] for t in r.get("to", []) if "except" in t.get("ipBlock", {})))')" == 0 ]]; then
  row 12 "offline: Kyverno still cut off once every offline check had run" "cut off" PASS
else
  row 12 "offline: Kyverno still cut off once every offline check had run" "its way to the internet was back" FAIL
fi

reconnect_kyverno
trap 'rm -f "$debug_user"' EXIT
answer=refused
deadline=$((SECONDS + 180))
while [[ "$answer" != admitted ]] && ((SECONDS < deadline)); do
  answer="$(pod signed "$ours_signed" | admit "$namespace")"
  [[ "$answer" == admitted ]] || sleep 5
done
expect 12 "reconnected: the same signed digest" "$answer" admitted
echo "offline: known digest ${timings[*]} ms; ${refused_offline} pods refused for an unverifiable image meanwhile" >>"$out/security-test.log"

# --- A new pod's first moments (13) --------------------------------------------------------------------------------

log "13. a new pod's first moments"
# kube-router enforces a new pod's rules only once it has seen the pod, so until then the pod reaches anything; every
# pod of ours, and Kyverno's, first waits for them (wait-for-network-policy, ADR 0048). Fresh pods that start as ours do
# (the scanner Deployment's own wait) try the internet, the metadata address, the node's kubelet and PostgreSQL from
# their main container's first instruction, every 20 ms for 3 s, and print what answered and when. The control is the
# same pods without the wait, which must reach something, or the check proves nothing.
wait_container="$(in_namespace get deployment idleo-scanner -o jsonpath='{.spec.template.spec.initContainers[0]}')"
first_moments() {
  local run="$1" count="$2" init="$3"
  python3 - "$namespace" "$run" "$count" "$valkey_image" "$init" "$internet" "$metadata" "$node_ip:10250" "$database" <<'PY' |
import json, sys
namespace, run, count, image, init, *targets = sys.argv[1:]
names = ["internet", "metadata", "kubelet", "postgres"]
script = """start=${EPOCHREALTIME/./}; end=$((start + 3000000))
watch() { while (( ${EPOCHREALTIME/./} < end )); do
  if timeout 0.25 bash -c "exec 3<>/dev/tcp/${2%:*}/${2##*:}" 2>/dev/null; then
    echo "$1 $(( (${EPOCHREALTIME/./} - start) / 1000 ))"; return; fi; sleep 0.02; done; }
""" + "\n".join(f"watch {n} {t} &" for n, t in zip(names, targets)) + "\nwait"
security = {"runAsNonRoot": True, "readOnlyRootFilesystem": True, "allowPrivilegeEscalation": False,
            "capabilities": {"drop": ["ALL"]}, "seccompProfile": {"type": "RuntimeDefault"}}
items = [{"apiVersion": "v1", "kind": "Pod",
          "metadata": {"name": f"{run}-{i}", "labels": {"idleo.in/security-test": run}},
          "spec": {"restartPolicy": "Never", "automountServiceAccountToken": False, "enableServiceLinks": False,
                   "securityContext": {"runAsNonRoot": True, "runAsUser": 65532, "seccompProfile": {"type": "RuntimeDefault"}},
                   "initContainers": [json.loads(init)] if init else [],
                   "containers": [{"name": "probe", "image": image, "command": ["bash", "-c", script],
                                   "resources": {"requests": {"cpu": "10m", "memory": "16Mi"}, "limits": {"memory": "64Mi"}},
                                   "securityContext": security}]}} for i in range(int(count))]
print(json.dumps({"apiVersion": "v1", "kind": "List", "items": items}))
PY
    in_namespace apply -f - >/dev/null
  local deadline=$((SECONDS + 360))
  until [[ "$(in_namespace get pods -l idleo.in/security-test="$run" \
    -o jsonpath='{range .items[*]}{.status.phase}{"\n"}{end}' | grep -c Succeeded)" == "$count" ]]; do
    ((SECONDS < deadline)) || { echo "unfinished"; return; }
    sleep 2
  done
  for pod in $(in_namespace get pods -l idleo.in/security-test="$run" -o name); do
    in_namespace logs "$pod" -c probe | sed "s|^|${pod#pod/} |"
  done
  in_namespace delete pods -l idleo.in/security-test="$run" --wait=false >/dev/null
}
# "<n> of <count> pods reached <target> (at <ms> ms at the latest), ..." for what $1 (pod target ms lines) shows.
reached() {
  python3 -c '
import collections, sys
seen = collections.defaultdict(list)
for line in sys.stdin.read().split("\n"):
    if line.strip():
        _, target, ms = line.split()
        seen[target].append(int(ms))
print(", ".join(f"{len(v)} of {sys.argv[1]} reached {t} (by {max(v)} ms)" for t, v in sorted(seen.items())) or "none")' "$2" <<<"$1"
}
waiting="$(first_moments first-moments 20 "$wait_container")"
control="$(first_moments first-moments-control 10 "")"
printf '%s\n' "first moments, waiting:" "$waiting" "first moments, without the wait:" "$control" >>"$out/security-test.log"
if [[ "$waiting" == unfinished ]]; then
  row 13 "a new pod of ours, in its first 3 s: the internet, metadata, kubelet, PostgreSQL" "not all 20 pods finished in 360 s" FAIL
else
  expect 13 "a new pod of ours, in its first 3 s: the internet, metadata, kubelet, PostgreSQL" "$(reached "$waiting" 20)" none
fi
if [[ "$control" != unfinished && "$(reached "$control" 10)" != none ]]; then
  row 13 "control: the same pods without the wait" "$(reached "$control" 10)" PASS
else
  row 13 "control: the same pods without the wait" "${control:-reached nothing} (must reach something)" FAIL
fi
# Every pod of ours but CloudNativePG's (its operator makes them, ADR 0047), and Kyverno's, waits first.
not_waiting="$({ in_namespace get pods -l '!cnpg.io/cluster,!idleo.in/security-test' -o json
  kubectl -n kyverno get pods -l app.kubernetes.io/component=admission-controller -o json; } | python3 -c '
import json, sys
decoder, text, pods = json.JSONDecoder(), sys.stdin.read(), []
while text.strip():
    found, end = decoder.raw_decode(text.lstrip()); pods += found["items"]; text = text.lstrip()[end:]
print(" ".join(p["metadata"]["name"] for p in pods
               if [c["name"] for c in p["spec"].get("initContainers", [])][:1] != ["wait-for-network-policy"]) or "none")')"
expect 13 "pods of ours and Kyverno's that don't wait for their network policy first" "$not_waiting" none

echo
printf '  %-3s %-78s %-6s %s\n' "#" "attempt" "result" "what happened"
for line in "${rows[@]}"; do
  IFS='|' read -r number attempt outcome verdict <<<"$line"
  printf '  %-3s %-78s %-6s %s\n' "$number" "$attempt" "$verdict" "$outcome"
done
echo
log "${#rows[@]} checks, $failures failed, $((SECONDS - started_at)) s"
((failures == 0)) || exit 1
