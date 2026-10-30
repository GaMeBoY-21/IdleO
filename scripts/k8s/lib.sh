# The Mac's k3d cluster (ADR 0045, docs/runbooks/kubernetes.md): its names, ports and pinned versions, shared by up.sh,
# down.sh, status.sh, smoke.sh, secrets.sh and ops.sh. Source it; don't run it. The caller defines log and fail.
# shellcheck shell=bash

k8s_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cluster=idleo
namespace=idleo
# k3s pinned by digest: the release the server runs too (S5), and kubeconform's schemas (scripts/checks/deploy.sh).
k3s_image='rancher/k3s:v1.36.4-k3s1@sha256:edad48e12bf81c3a09ac1c05c0c0ffaaa22145980b989d6fae84543a76b83657'
cert_manager_version=v1.21.3
# The cluster's own registry: pushed to from this machine as localhost:<port>, pulled from inside as <name>:<port>.
registry_name=k3d-idleo-registry
registry_port=5050
# This machine's ports, which the cluster's addresses carry (deploy/helm/idleo/values-local.yaml): HTTPS for the
# website, the app and the API; HTTP, which only redirects; the agents' gRPC; and the development sign-in server,
# object storage and mail catcher. None clashes with ./start.sh's stack.
https_port=8443
plain_http_port=8088
agents_port=19443
sign_in_port=18099
storage_port=19100
mail_port=18025
site_url="https://idleo.localhost:$https_port"
app_url="https://app.idleo.localhost:$https_port"
api_url="https://api.idleo.localhost:$https_port"
sign_in_url="http://signin.idleo.localhost:$sign_in_port/idleo-local"
# Everything this cluster keeps on this machine: its kubeconfig (never ~/.kube/config), the local CA its certificates
# come from, the database's passwords and the logs. Gitignored, under private/.
k8s_state="$k8s_root/private/k8s"
export KUBECONFIG="$k8s_state/kubeconfig"
local_ca="$k8s_state/ca"
# The local age key: the one deploy/clusters/local/secrets/ is encrypted for (scripts/deploy/age-key.sh local).
export SOPS_AGE_KEY_FILE="$k8s_state/age/local.agekey"

# OpenSSL 3, found as control-plane/scripts/dev-ca.sh finds it (OPENSSL names another); macOS ships LibreSSL.
openssl3() {
  local candidate
  for candidate in ${OPENSSL:-} /opt/homebrew/opt/openssl@3/bin/openssl /usr/local/opt/openssl@3/bin/openssl openssl; do
    if command -v "$candidate" >/dev/null && "$candidate" version | grep -q '^OpenSSL 3'; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  fail "needs OpenSSL 3 (macOS ships LibreSSL): brew install openssl@3"
}

cluster_exists() { k3d cluster list -o json 2>/dev/null | grep -q "\"name\":\"$cluster\""; }

# The tools the scripts drive. On a Mac with Homebrew a missing one is installed, and said; elsewhere (CI) it must be
# there already.
k8s_tools() {
  local tool formula
  for tool in docker k3d kubectl helm; do
    command -v "$tool" >/dev/null && continue
    formula="$tool"
    [[ "$tool" == docker ]] && fail "Docker isn't installed: install Docker Desktop (Linux: Docker Engine)"
    command -v brew >/dev/null || fail "$tool isn't installed, and there's no Homebrew to install it with"
    log "$tool isn't installed: installing it with Homebrew (brew install $formula)"
    brew install "$formula" >/dev/null || fail "brew install $formula failed"
  done
  docker info >/dev/null 2>&1 || fail "Docker isn't running: start Docker Desktop, then run this again"
}

# Under Flux (up.sh --gitops, ADR 0046): the repository it follows, its release (deploy/flux) and the chart's own
# version, which the local build is published as <version>-<image tag>.
flux_repository=https://github.com/GaMeBoY-21/IdleO
flux_version=v2.9.6
chart_version="$(sed -n 's/^version: //p' "$k8s_root/deploy/helm/idleo/Chart.yaml")"

# Pushes localhost:<registry>/<name:tag> to the cluster's registry and prints its digest.
local_push() {
  local pushed
  pushed="$(docker push "localhost:$registry_port/$1" 2>&1)" || { printf '%s\n' "$pushed" >&2; return 1; }
  sed -n 's/.*digest: \(sha256:[0-9a-f]\{64\}\).*/\1/p' <<<"$pushed" | tail -1 | grep . || return 1
}

# Packages the chart from this tree as <version>-<tag>, pushes it to the cluster's registry and prints its digest.
local_chart() {
  local tag="$1" folder digest
  folder="$(mktemp -d)"
  helm package "$k8s_root/deploy/helm/idleo" --version "$chart_version-$tag" --app-version "$tag" \
    --destination "$folder" >/dev/null || return 1
  digest="$(helm push "$folder/idleo-$chart_version-$tag.tgz" "oci://localhost:$registry_port/charts" --plain-http 2>&1 |
    sed -n 's/^Digest: //p')"
  rm -rf "$folder"
  [[ "$digest" == sha256:* ]] || return 1
  printf '%s\n' "$digest"
}

# The build Flux releases (the ConfigMap flux-system/local-release, deploy/clusters/local/idleo/release.yaml): the
# image tag, the chart's digest, and each image's digest as up.sh pushed it, or the control plane's given as $3.
release_settings() {
  local image digests=()
  for image in control-plane web scanner postgres; do
    local name
    name="$(tr '[:lower:]-' '[:upper:]_' <<<"$image")_DIGEST"
    if [[ "$image" == control-plane && -n "${3:-}" ]]; then
      digests+=(--from-literal="$name=$3")
    else
      digests+=(--from-literal="$name=$(cat "$k8s_state/digest-$image")")
    fi
  done
  kubectl create configmap local-release --namespace flux-system --from-literal=IMAGE_TAG="$1" \
    --from-literal=CHART_DIGEST="$2" "${digests[@]}" --dry-run=client -o yaml | kubectl apply -f - >/dev/null
}

# Asks Flux to read the source and apply every Kustomization now, rather than at its interval.
flux_reconcile() {
  flux_requested="$(date +%s)"
  kubectl -n flux-system annotate --overwrite gitrepository/flux-system \
    "reconcile.fluxcd.io/requestedAt=$flux_requested" >/dev/null
  kubectl -n flux-system annotate --overwrite kustomization --all "reconcile.fluxcd.io/requestedAt=$flux_requested" >/dev/null
}

# Waits up to $2 seconds for Kustomization $1 to be Ready after the last flux_reconcile: having handled it, or, made
# since (a first bootstrap's infrastructure and idleo), never asked.
flux_ready() {
  local deadline=$((SECONDS + $2)) handled asked ready
  while ((SECONDS < deadline)); do
    handled="$(kubectl -n flux-system get kustomization "$1" -o jsonpath='{.status.lastHandledReconcileAt}' 2>/dev/null || true)"
    asked="$(kubectl -n flux-system get kustomization "$1" -o jsonpath='{.metadata.annotations.reconcile\.fluxcd\.io/requestedAt}' 2>/dev/null || true)"
    ready="$(kubectl -n flux-system get kustomization "$1" -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}' 2>/dev/null || true)"
    [[ ("$handled" == "$flux_requested" || "$asked" != "$flux_requested") && "$ready" == True ]] && return 0
    sleep 5
  done
  return 1
}
