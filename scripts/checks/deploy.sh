#!/usr/bin/env bash
# The Helm chart's checks (deploy/helm/idleo, ADR 0045; .github/workflows/deploy.yml runs this; so does
# scripts/check-all.sh): helm lint, every environment's rendering checked by kubeconform (strict, against Kubernetes'
# schemas and the Gateway API's and cert-manager's), and the chart's unit tests (helm-unittest), which hold every pod to
# the security settings. Each tool runs from its own image, pinned by digest, so a laptop and CI run the same ones and
# neither needs them installed.
set -euo pipefail
. "$(dirname "$0")/../lib/quiet.sh"
quiet_entry deploy "$@"
set -- ${QUIET_ARGS[@]+"${QUIET_ARGS[@]}"}
cd "$(dirname "$0")/../.."
root="$(pwd)"

# helm-unittest's image carries Helm itself (4.3.0), so it lints and renders too.
helm_image=helmunittest/helm-unittest:4.3.0-1.2.1@sha256:28ea86c3e0b07f501f0c2e9c08b9f41e240f4a4b571cb5106b36cf0f0607e989
kubeconform_image=ghcr.io/yannh/kubeconform:v0.8.0@sha256:faffaf43f95aa6425306e1ab8d6fcad72acb9049158f38e574c085ea1ec0f64e
# The schemas: Kubernetes' for k3s's release (deploy/k3s), and the custom resources' from the CRDs catalog at a pinned
# commit (the Gateway API's and cert-manager's).
kubernetes_version=1.36.0
crd_schemas='https://raw.githubusercontent.com/datreeio/CRDs-catalog/3eb90d47aba8d61919d4419dd38301536e32a94b/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json'
# Downloaded schemas are kept between runs.
cache="$root/private/k8s/kubeconform-cache"
mkdir -p "$cache"

helm_run() {
  docker run --rm --entrypoint helm -v "$root/deploy/helm/idleo:/apps:ro" -w /apps "$helm_image" "$@"
}

for environment in local staging production; do
  echo "deploy: lint and kubeconform, values-$environment.yaml"
  helm_run lint --strict . -f "values-$environment.yaml" --set images.tag=check
  helm_run template idleo . --namespace idleo -f "values-$environment.yaml" --set images.tag=check |
    docker run --rm -i -v "$cache:/cache" "$kubeconform_image" -strict -summary -cache /cache \
      -kubernetes-version "$kubernetes_version" -schema-location default -schema-location "$crd_schemas"
done
echo "deploy: helm-unittest"
# On a copy: helm-unittest writes a snapshot folder into the chart's tests, even with no snapshot to keep.
chart_copy="$(mktemp -d)"
trap 'rm -rf "$chart_copy"' EXIT
cp -R "$root/deploy/helm/idleo/." "$chart_copy/"
chmod -R a+rwX "$chart_copy"
docker run --rm -v "$chart_copy:/apps" "$helm_image" .
