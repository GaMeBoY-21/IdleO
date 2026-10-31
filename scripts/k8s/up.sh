#!/usr/bin/env bash
# The Mac's cluster (ADR 0045, 0046; docs/runbooks/kubernetes.md): what production runs, on this machine. A k3d cluster
# (k3s, pinned, one server node, a registry of its own) and the server's images built from this tree and pushed to it;
# then it prints the addresses. Two ways to run what's in it:
#
#   scripts/k8s/up.sh            plain Helm, for quick work: the local Secrets, cert-manager and the idleo chart with
#                                values-local.yaml, installed from this tree (again after a change: the images are
#                                rebuilt, the chart upgraded)
#   scripts/k8s/up.sh --gitops   as staging and production run: Flux, bootstrapped from this repository on GitHub at
#                                this commit (push the branch first), installs the infrastructure (cert-manager,
#                                Flagger, Prometheus) and the release; the chart and images are this tree's, pushed to
#                                the cluster's registry. Again after a change: what changed is released as a canary.
#   scripts/k8s/down.sh          delete it (needed to switch between the two)
#   scripts/k8s/status.sh        what's running, and its memory
#   scripts/k8s/smoke.sh         the proof that it works (either way)
#   scripts/k8s/canary-test.sh   the proof of the canaries (--gitops)
#
# The cluster uses about 4.4 of Docker Desktop's 7.75 GiB at rest (two control planes and two database instances,
# measured 2026-10-31), too much beside ./start.sh's stack, so this stops that stack first (./start.sh --stop; start it
# again after down.sh). Everything it keeps is under the gitignored private/k8s/.
set -euo pipefail
log() { printf 'k8s: %s\n' "$*"; }
fail() { printf 'k8s: FAIL: %s\n' "$*" >&2; exit 1; }
. "$(dirname "$0")/lib.sh"
cd "$k8s_root"
mkdir -p "$k8s_state/logs"
logs="$k8s_state/logs"
gitops=false
case "${1:-}" in
  --gitops) gitops=true ;;
  "") ;;
  *) fail "usage: $0 [--gitops]" ;;
esac

k8s_tools
command -v sops >/dev/null || fail "sops isn't installed (macOS: brew install sops age)"
if [[ "$gitops" == true ]]; then
  # Flux reads the commit from GitHub: it must be there, and what the cluster reads must be what's committed.
  commit="$(git rev-parse HEAD)"
  branch="${GITOPS_BRANCH:-$(git symbolic-ref --short -q HEAD || true)}"
  [[ -n "$branch" ]] || fail "HEAD isn't on a branch; check one out (or set GITOPS_BRANCH)"
  [[ -z "$(git status --porcelain -- deploy .sops.yaml templates/development.yaml control-plane/local-sign-in.json)" ]] ||
    fail "deploy/ has changes that aren't committed: Flux reads the cluster from GitHub, so commit and push them first"
  remote="$(git ls-remote "$flux_repository" "refs/heads/$branch" | cut -f1)"
  [[ "$remote" == "$commit" ]] ||
    fail "$branch on GitHub is ${remote:-missing}, not this commit ($commit): push it first (Flux reads the cluster from there)"
fi

if [[ -f private/local/control-plane.pid ]] && kill -0 "$(cat private/local/control-plane.pid)" 2>/dev/null; then
  log "stopping ./start.sh's stack first: it and the cluster don't fit in Docker's memory together"
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
# One way at a time: Flux would fight a release plain Helm installed, and the other way round.
if kubectl get namespace flux-system >/dev/null 2>&1; then
  [[ "$gitops" == true ]] || fail "this cluster is run by Flux (up.sh --gitops): run down.sh first to use plain Helm"
elif [[ "$gitops" == true ]] && helm status idleo --namespace "$namespace" >/dev/null 2>&1; then
  fail "this cluster's release was installed by plain Helm (up.sh): run down.sh first to put it under Flux"
fi
kubectl apply -f deploy/k3s/local/dns.yaml >/dev/null
# CoreDNS reads the custom names only as it starts.
kubectl -n kube-system rollout restart deployment/coredns >/dev/null
kubectl -n kube-system rollout status deployment/coredns --timeout=120s >/dev/null

# 2. cert-manager, for the Gateway's certificate, and CloudNativePG with its backup plugin, for the database (under
#    Flux, the infrastructure has them). The operator's and plugin's charts and values are Flux's own
#    (deploy/infrastructure/base/cloudnative-pg.yaml), by the same digests.
if [[ "$gitops" == false ]]; then
  log "installing cert-manager $cert_manager_version"
  helm upgrade --install cert-manager oci://quay.io/jetstack/charts/cert-manager --version "$cert_manager_version" \
    --namespace cert-manager --create-namespace --set crds.enabled=true --wait --timeout 5m >>"$logs/up.log" 2>&1 ||
    fail "cert-manager didn't install; see private/k8s/logs/up.log"
  for release in cloudnative-pg plugin-barman-cloud; do
    log "installing $release"
    read -r name chart <<<"$(cnpg_release "$release" "$k8s_state/$release-values.yaml")"
    helm upgrade --install "$name" "$chart" --namespace cnpg-system --create-namespace \
      -f "$k8s_state/$release-values.yaml" --wait --timeout 5m >>"$logs/up.log" 2>&1 ||
      fail "$release didn't install; see private/k8s/logs/up.log"
  done
fi

# 3. The images, built from this tree for this machine and pushed to the cluster's registry, tagged with the commit
#    (and the time, when the tree has changes, so a rebuild always rolls out), each one's digest kept.
tag="$(git rev-parse --short=12 HEAD)"
[[ -z "$(git status --porcelain)" ]] || tag="$tag-$(date +%Y%m%d%H%M%S)"
for image in control-plane web scanner; do
  case "$image" in
    control-plane | web) context=. dockerfile="$image/Dockerfile" ;;
    scanner) context=scanner dockerfile=scanner/Dockerfile ;;
  esac
  log "building idleo-$image"
  started=$SECONDS
  docker build -f "$dockerfile" -t "localhost:$registry_port/idleo-$image:$tag" \
    --build-arg VERSION="$tag" --build-arg REVISION="$(git rev-parse HEAD)" "$context" >"$logs/build-$image.log" 2>&1 ||
    fail "idleo-$image didn't build; see private/k8s/logs/build-$image.log"
  local_push "idleo-$image:$tag" >"$k8s_state/digest-$image" 2>>"$logs/build-$image.log" ||
    fail "idleo-$image couldn't be pushed; see private/k8s/logs/build-$image.log"
  log "  idleo-$image: $(docker image inspect "localhost:$registry_port/idleo-$image:$tag" --format '{{.Size}}' |
    awk '{printf "%.0f MB", $1 / 1000000}'), $((SECONDS - started)) s"
done

if [[ "$gitops" == false ]]; then
  # 4. The Secrets and development settings.
  scripts/k8s/secrets.sh

  # 5. The chart. Helm waits for the migration Job (a hook); the rollouts are waited for after.
  log "installing the idleo chart ($tag)"
  helm upgrade --install idleo deploy/helm/idleo --namespace "$namespace" -f deploy/helm/idleo/values-local.yaml \
    --set images.tag="$tag" --timeout 15m >>"$logs/up.log" 2>&1 ||
    fail "the chart didn't install; see private/k8s/logs/up.log and kubectl -n $namespace get pods"
  control_plane=deployment/idleo-control-plane
else
  # 4. The chart, packaged from this tree and pushed beside the images.
  scripts/k8s/secrets.sh --files
  chart_digest="$(local_chart "$tag")" || fail "the chart couldn't be published to the cluster's registry"
  log "the chart: idleo $chart_version-$tag"

  # 5. Flux, then what it reads: the local age key (sops-age), the commit to follow and the build to release.
  log "bootstrapping Flux ${flux_version} from $flux_repository at $branch ($commit)"
  kubectl apply --server-side --force-conflicts -k deploy/flux >>"$logs/up.log" 2>&1 ||
    fail "Flux's components didn't apply; see private/k8s/logs/up.log"
  kubectl -n flux-system wait --for=condition=Available deployment --all --timeout=5m >/dev/null ||
    fail "Flux's controllers aren't ready; kubectl -n flux-system get pods"
  apply() { kubectl create "$@" --dry-run=client -o yaml | kubectl apply -f - >/dev/null; }
  apply secret generic sops-age --namespace flux-system --from-file=age.agekey="$SOPS_AGE_KEY_FILE"
  apply configmap local-source --namespace flux-system --from-literal=GIT_BRANCH="$branch" --from-literal=GIT_COMMIT="$commit"
  release_settings "$tag" "$chart_digest"
  # The sync itself, as Flux will keep it from then on.
  sed -e "s|\${GIT_BRANCH}|$branch|" -e "s|\${GIT_COMMIT}|$commit|" deploy/clusters/local/flux-system/sync.yaml |
    kubectl apply -f - >/dev/null
  flux_reconcile
  log "waiting for Flux: the infrastructure, then the release (its migration Job, then the control plane)"
  for kustomization in flux-system infrastructure idleo; do
    flux_ready "$kustomization" 1800 || fail "Flux's $kustomization isn't ready: kubectl -n flux-system describe kustomization $kustomization"
  done
  control_plane=deployment/idleo-control-plane-primary
fi

kubectl -n "$namespace" wait cluster/idleo-database --for=condition=Ready --timeout=10m >/dev/null ||
  fail "the database isn't ready; kubectl -n $namespace describe cluster idleo-database says why"
for workload in statefulset/idleo-valkey "$control_plane" \
  deployment/idleo-web deployment/idleo-scanner deployment/idleo-sign-in deployment/idleo-storage deployment/idleo-mail; do
  kubectl -n "$namespace" rollout status "$workload" --timeout=10m >/dev/null ||
    fail "$workload isn't ready; kubectl -n $namespace describe $workload says why"
done
kubectl -n "$namespace" wait certificate/idleo-tls --for=condition=Ready --timeout=120s >/dev/null ||
  fail "the Gateway's certificate wasn't issued; kubectl -n $namespace describe certificate idleo-tls"

cat <<TEXT

  The cluster is up (images $tag$([[ "$gitops" == true ]] && echo ", under Flux at ${commit:0:12}")). Its certificates
  come from private/k8s/ca/ca.pem: trust it to open the pages without a warning (docs/runbooks/kubernetes.md), or use
  curl --cacert private/k8s/ca/ca.pem.

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
