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
