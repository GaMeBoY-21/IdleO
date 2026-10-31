#!/usr/bin/env bash
# Scans what is deployed, every night (ADR 0048; .github/workflows/scan.yml), with Trivy as the images workflow runs it:
#
#   1. each image staging and production pin (deploy/clusters/<env>/idleo/release.yaml), by its exact digest: a
#      CRITICAL or HIGH vulnerability with a fix fails, as at build time, but a fix published since the build counts;
#   2. with --cluster, the cluster the current KUBECONFIG names (the workflow's is a k3d cluster built from main under
#      Flux, as production runs): every running image in the namespaces we own, ours and the vendors', for fixable
#      CRITICAL or HIGH vulnerabilities, and their manifests, RBAC and secrets for CRITICAL or HIGH findings.
#
# The accepted findings are scripts/images/trivyignore.yaml, each with its reason and an expiry.
#
#   scripts/images/scan-deployed.sh             the pinned images
#   scripts/images/scan-deployed.sh --cluster   and the cluster
set -euo pipefail
. "$(dirname "$0")/../lib/quiet.sh"
quiet_entry scan-deployed "$@"
set -- ${QUIET_ARGS[@]+"${QUIET_ARGS[@]}"}
cd "$(dirname "$0")/../.."
root="$(pwd)"
# Trivy as .github/workflows/images.yml pins it.
trivy_image="$(sed -n 's/^  TRIVY_IMAGE: //p' .github/workflows/images.yml)"
[[ "$trivy_image" == *@sha256:* ]] || { echo "scan-deployed: no pinned Trivy in images.yml" >&2; exit 1; }
cache="$root/private/trivy-cache"
mkdir -p "$cache"
trivy() {
  docker run --rm -v "$cache:/root/.cache" -v "$root/scripts/images/trivyignore.yaml:/ignore.yaml:ro" "$@"
}
failed=0

images="$(python3 - <<'PY'
import yaml
seen = []
for env in ("staging", "production"):
    documents = yaml.safe_load_all(open(f"deploy/clusters/{env}/idleo/release.yaml"))
    release = next(d for d in documents if d and d["kind"] == "HelmRelease")
    # The images the chart runs; a retired one a release still names (the postgres image, before ADR 0047) is not.
    digests = release["spec"]["values"]["images"]["digests"]
    for name in ("control-plane", "web", "scanner"):
        image = f"ghcr.io/gameboy-21/idleo-{name}@{digests[name]}"
        if image not in seen:
            seen.append(image)
print("\n".join(seen))
PY
)"
for image in $images; do
  echo "scan-deployed: $image"
  trivy "$trivy_image" image --quiet --severity CRITICAL,HIGH --ignore-unfixed --ignorefile /ignore.yaml \
    --exit-code 1 "$image" || failed=1
done

if [[ "${1:-}" == --cluster ]]; then
  [[ -s "${KUBECONFIG:-}" ]] || { echo "scan-deployed: --cluster needs KUBECONFIG" >&2; exit 2; }
  echo "scan-deployed: the cluster"
  # The kubeconfig's server is this machine's address for the cluster; Trivy runs beside it on the host's network. The
  # cluster's own images are named after its registry (k3d-idleo-registry:5050, plain HTTP on this machine), which
  # Trivy reaches as the nodes do.
  trivy -v "$KUBECONFIG:/kubeconfig:ro" -e KUBECONFIG=/kubeconfig --network host --add-host k3d-idleo-registry:127.0.0.1 \
    "$trivy_image" k8s --quiet --insecure \
    --include-namespaces idleo,flux-system,kyverno,cert-manager,cnpg-system,flagger-system,monitoring \
    --scanners vuln,misconfig,secret,rbac --severity CRITICAL,HIGH --ignore-unfixed --ignorefile /ignore.yaml \
    --report summary --exit-code 1 --timeout 30m || failed=1
fi
exit "$failed"
