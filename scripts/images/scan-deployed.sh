#!/usr/bin/env bash
# Scans what is deployed, every night (ADR 0048; .github/workflows/scan.yml), with Trivy as the images workflow runs it:
#
#   1. each image staging and production pin (deploy/clusters/<env>/idleo/release.yaml), by its exact digest;
#   2. with --cluster, the cluster the current KUBECONFIG names (the workflow's is a k3d cluster built from main under
#      Flux, as production runs): every vendor image its pods run that staging and production may run too (a
#      repository on deploy/policies/base's allowed list, so not the Mac's stand-ins or its own builds), by digest; and
#      the namespaces we own, for their manifests, RBAC and secrets (their images are the list above).
#
# A CRITICAL or HIGH vulnerability with a fix fails it, as at build time, but a fix published since counts; so does a
# CRITICAL or HIGH secret in an image, or misconfiguration, RBAC or secret finding in the cluster. The accepted ones are scripts/images/trivyignore.yaml,
# each with its reason, scope and an expiry of at most 60 days, after which it fails; nothing on our own images is.
# It prints one line per image, and writes the findings' count to scan-findings.txt for the alert (scan.yml).
#
#   scripts/images/scan-deployed.sh             the pinned images
#   scripts/images/scan-deployed.sh --cluster   and the cluster
set -euo pipefail
. "$(dirname "$0")/../lib/quiet.sh"
quiet_entry scan-deployed "$@"
set -- ${QUIET_ARGS[@]+"${QUIET_ARGS[@]}"}
# Docker mounts a file only by its absolute path: the kubeconfig as the caller named it, from where they ran this.
[[ -z "${KUBECONFIG:-}" || "$KUBECONFIG" == /* ]] || KUBECONFIG="$PWD/$KUBECONFIG"
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

# An accepted finding whose entry has expired fails, whether or not the finding is still there: it's looked at again.
expired="$(python3 - <<'PY'
import datetime, yaml
entries = yaml.safe_load(open("scripts/images/trivyignore.yaml")) or {}
today = datetime.date.today()
for kind, found in entries.items():
    for entry in found or []:
        if entry["expired_at"] <= today:
            print(f"{entry['id']} ({kind}, {entry['expired_at']})")
PY
)"
if [[ -n "$expired" ]]; then
  echo "scan-deployed: FAIL scripts/images/trivyignore.yaml: expired: $(paste -sd, - <<<"$expired" | sed 's/,/, /g')"
  failed=1
fi

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
namespaces=idleo,flux-system,kyverno,cert-manager,cnpg-system,flagger-system,monitoring
if [[ "${1:-}" == --cluster ]]; then
  [[ -s "${KUBECONFIG:-}" ]] || { echo "scan-deployed: --cluster needs KUBECONFIG" >&2; exit 2; }
  # The vendors' images the cluster runs, as staging and production run them: every repository on the base allowed
  # list but ours (whose digests are release.yaml's, above).
  images+=$'\n'"$(kubectl get pods -A -o json | python3 -c '
import json, sys, yaml
allowed = yaml.safe_load(open("deploy/policies/base/images-pinned.yaml").read().split("---")[-1])
repositories = {r.strip() for r in allowed["data"]["repositories"].splitlines() if r.strip() and not r.strip().startswith("#")}
namespaces = set(sys.argv[1].split(","))
seen = set()
for pod in json.load(sys.stdin)["items"]:
    if pod["metadata"]["namespace"] not in namespaces:
        continue
    spec = pod["spec"]
    for container in spec.get("containers", []) + spec.get("initContainers", []):
        image = container["image"]
        name = image.split("@")[0]
        repository = name.rsplit(":", 1)[0] if ":" in name.rsplit("/", 1)[-1] else name
        if repository in repositories and not repository.startswith("ghcr.io/gameboy-21/") and "@sha256:" in image:
            seen.add(image)
print("\n".join(sorted(seen)))' "$namespaces")"
fi

findings=0
for image in $(sort -u <<<"$images"); do
  # Ours are scanned as built (.github/workflows/images.yml), with nothing accepted.
  ignore=(--ignorefile /ignore.yaml)
  [[ "$image" == ghcr.io/gameboy-21/* ]] && ignore=()
  report="$(trivy "$trivy_image" image --quiet --scanners vuln,secret --severity CRITICAL,HIGH --ignore-unfixed \
    ${ignore[@]+"${ignore[@]}"} --format json "$image")" || { echo "scan-deployed: FAIL $image: Trivy couldn't scan it" >&2; failed=1; continue; }
  count="$(python3 -c 'import json, sys; print(sum(len(r.get("Vulnerabilities") or []) + len(r.get("Secrets") or []) for r in json.load(sys.stdin).get("Results", [])))' <<<"$report")"
  findings=$((findings + count))
  if ((count > 0)); then
    echo "scan-deployed: FAIL $image: $count CRITICAL or HIGH (fixable vulnerabilities and secrets)"
    python3 -c '
import json, sys
for r in json.load(sys.stdin).get("Results", []):
    for v in r.get("Vulnerabilities") or []:
        print("  %s %s %s %s -> %s (%s)" % (v["Severity"], v["VulnerabilityID"], r["Target"], v["PkgName"], v.get("FixedVersion"), v.get("InstalledVersion")))
    for f in r.get("Secrets") or []:
        print("  %s %s %s: %s" % (f["Severity"], f["RuleID"], r["Target"], f["Title"]))' <<<"$report"
    failed=1
  else
    echo "scan-deployed: PASS $image"
  fi
done

if [[ "${1:-}" == --cluster ]]; then
  echo "scan-deployed: the cluster's manifests, RBAC and secrets"
  # The kubeconfig's server is this machine's address for the cluster; Trivy runs beside it on the host's network. Its
  # images were scanned above, by digest (--skip-images).
  report="$(trivy -v "$KUBECONFIG:/kubeconfig:ro" -e KUBECONFIG=/kubeconfig --network host "$trivy_image" k8s --quiet \
    --include-namespaces "$namespaces" --scanners misconfig,secret,rbac --skip-images --severity CRITICAL,HIGH \
    --ignorefile /ignore.yaml --format json --report all --timeout 30m)" || failed=1
  count="$(python3 -c '
import json, sys
n = 0
for resource in json.load(sys.stdin).get("Resources", []) or []:
    for r in resource.get("Results", []) or []:
        for m in r.get("Misconfigurations", []) or []:
            if m.get("Status") == "FAIL":
                n += 1
                print("  %s %s %s/%s: %s" % (m["Severity"], m["ID"], resource.get("Kind"), resource.get("Name"), m["Title"]), file=sys.stderr)
        for f in r.get("Secrets", []) or []:
            n += 1
            print("  %s %s %s/%s: %s" % (f["Severity"], f["RuleID"], resource.get("Kind"), resource.get("Name"), f["Title"]), file=sys.stderr)
print(n)' <<<"$report")"
  findings=$((findings + count))
  ((count == 0)) || failed=1
  echo "scan-deployed: $count CRITICAL or HIGH findings in the cluster's manifests, RBAC and secrets"
fi
echo "$findings" >"$root/private/scan-findings.txt"
exit "$failed"
