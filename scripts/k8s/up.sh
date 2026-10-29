#!/usr/bin/env bash
# The Mac's cluster (ADR 0045, docs/runbooks/kubernetes.md): what production runs, on this machine. A k3d cluster (k3s,
# pinned, one server node, a registry of its own), the server's images built from this tree and pushed to it, the local
# Secrets, cert-manager, and the idleo chart with values-local.yaml; then it prints the addresses.
#
#   scripts/k8s/up.sh          create or update it (again after a change: the images are rebuilt, the chart upgraded)
#   scripts/k8s/down.sh        delete it
#   scripts/k8s/status.sh      what's running, and its memory
#   scripts/k8s/smoke.sh       the proof that it works
#
# The cluster and ./start.sh's stack fit in Docker Desktop's memory together only while both idle (3.9 of 7.75 GiB,
# measured 2026-10-29), not at the chart's limits, so this stops that stack first (./start.sh --stop; start it again
# after down.sh). Everything it keeps is under the gitignored private/k8s/.
set -euo pipefail
log() { printf 'k8s: %s\n' "$*"; }
fail() { printf 'k8s: FAIL: %s\n' "$*" >&2; exit 1; }
. "$(dirname "$0")/lib.sh"
cd "$k8s_root"
mkdir -p "$k8s_state/logs"
logs="$k8s_state/logs"

k8s_tools

if [[ -f private/local/control-plane.pid ]] && kill -0 "$(cat private/local/control-plane.pid)" 2>/dev/null; then
  log "stopping ./start.sh's stack first: it and the cluster fit in Docker's memory together only while idle"
  ./start.sh --stop >>"$logs/up.log" 2>&1 || fail "./start.sh --stop failed; see private/k8s/logs/up.log"
fi

# 1. The cluster: k3s pinned, one server, the registry, and this machine's ports. k3s's Traefik is configured as it
#    boots (deploy/k3s/local/traefik.yaml); the development services' names resolve inside it (dns.yaml).
if ! cluster_exists; then
  log "creating the k3d cluster $cluster (k3s ${k3s_image%%@*})"
  k3d registry list -o json 2>/dev/null | grep -q "\"name\":\"$registry_name\"" ||
    k3d registry create "${registry_name#k3d-}" --port "127.0.0.1:$registry_port" >>"$logs/up.log" 2>&1 ||
    fail "the registry couldn't be made; see private/k8s/logs/up.log"
  ports=()
  for port in "$https_port" "$plain_http_port" "$agents_port" "$sign_in_port" "$storage_port" "$mail_port"; do
    ports+=(--port "127.0.0.1:$port:$port@loadbalancer")
  done
  k3d cluster create "$cluster" --image "$k3s_image" --servers 1 --agents 0 \
    --registry-use "$registry_name:$registry_port" "${ports[@]}" \
    --volume "$k8s_root/deploy/k3s/local/traefik.yaml:/var/lib/rancher/k3s/server/manifests/traefik-config.yaml@server:0" \
    --kubeconfig-update-default=false --kubeconfig-switch-context=false --wait >>"$logs/up.log" 2>&1 ||
    fail "k3d couldn't create the cluster; see private/k8s/logs/up.log"
fi
k3d kubeconfig get "$cluster" >"$KUBECONFIG"
chmod 600 "$KUBECONFIG"
kubectl apply -f deploy/k3s/local/dns.yaml >/dev/null
# CoreDNS reads the custom names only as it starts.
kubectl -n kube-system rollout restart deployment/coredns >/dev/null
kubectl -n kube-system rollout status deployment/coredns --timeout=120s >/dev/null

# 2. cert-manager, for the Gateway's certificate.
log "installing cert-manager $cert_manager_version"
helm upgrade --install cert-manager oci://quay.io/jetstack/charts/cert-manager --version "$cert_manager_version" \
  --namespace cert-manager --create-namespace --set crds.enabled=true --wait --timeout 5m >>"$logs/up.log" 2>&1 ||
  fail "cert-manager didn't install; see private/k8s/logs/up.log"

# 3. The images, built from this tree for this machine and pushed to the cluster's registry, tagged with the commit
#    (and the time, when the tree has changes, so a rebuild always rolls out).
tag="$(git rev-parse --short=12 HEAD)"
[[ -z "$(git status --porcelain)" ]] || tag="$tag-$(date +%Y%m%d%H%M%S)"
for image in control-plane web scanner postgres; do
  case "$image" in
    control-plane | web) context=. dockerfile="$image/Dockerfile" ;;
    scanner) context=scanner dockerfile=scanner/Dockerfile ;;
    postgres) context=ops/postgres dockerfile=ops/postgres/Dockerfile ;;
  esac
  log "building idleo-$image"
  started=$SECONDS
  docker build -f "$dockerfile" -t "localhost:$registry_port/idleo-$image:$tag" \
    --build-arg VERSION="$tag" --build-arg REVISION="$(git rev-parse HEAD)" "$context" >"$logs/build-$image.log" 2>&1 ||
    fail "idleo-$image didn't build; see private/k8s/logs/build-$image.log"
  docker push -q "localhost:$registry_port/idleo-$image:$tag" >>"$logs/build-$image.log" 2>&1 ||
    fail "idleo-$image couldn't be pushed; see private/k8s/logs/build-$image.log"
  log "  idleo-$image: $(docker image inspect "localhost:$registry_port/idleo-$image:$tag" --format '{{.Size}}' |
    awk '{printf "%.0f MB", $1 / 1000000}'), $((SECONDS - started)) s"
done

# 4. The Secrets and development settings.
scripts/k8s/secrets.sh

# 5. The chart. Helm waits for the migration Job (a hook); the rollouts are waited for after.
log "installing the idleo chart ($tag)"
helm upgrade --install idleo deploy/helm/idleo --namespace "$namespace" -f deploy/helm/idleo/values-local.yaml \
  --set images.tag="$tag" --timeout 15m >>"$logs/up.log" 2>&1 ||
  fail "the chart didn't install; see private/k8s/logs/up.log and kubectl -n $namespace get pods"
for workload in statefulset/idleo-postgres statefulset/idleo-valkey deployment/idleo-control-plane \
  deployment/idleo-web deployment/idleo-scanner deployment/idleo-sign-in deployment/idleo-storage deployment/idleo-mail; do
  kubectl -n "$namespace" rollout status "$workload" --timeout=10m >/dev/null ||
    fail "$workload isn't ready; kubectl -n $namespace describe $workload says why"
done
kubectl -n "$namespace" wait certificate/idleo-tls --for=condition=Ready --timeout=120s >/dev/null ||
  fail "the Gateway's certificate wasn't issued; kubectl -n $namespace describe certificate idleo-tls"

cat <<TEXT

  The cluster is up (images $tag). Its certificates come from private/k8s/ca/ca.pem: trust it to open the pages
  without a warning (docs/runbooks/kubernetes.md), or use curl --cacert private/k8s/ca/ca.pem.

  Website        $site_url/
  App            $app_url/
  API            $api_url/  (readiness: $api_url/actuator/health/readiness)
  Agents         localhost:$agents_port (gRPC, mutual TLS to the control plane)
  Sign-in        $sign_in_url  (development)
  Storage        http://storage.idleo.localhost:$storage_port  (development)
  Mail           http://localhost:$mail_port  (development)

  kubectl        export KUBECONFIG=private/k8s/kubeconfig
  Status         scripts/k8s/status.sh       Proof   scripts/k8s/smoke.sh       Stop   scripts/k8s/down.sh
TEXT
