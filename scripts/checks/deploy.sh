#!/usr/bin/env bash
# The deployment's checks (ADR 0045, 0046; .github/workflows/deploy.yml runs this, a required check; so does
# scripts/check-all.sh):
#
#   1. no Secret under deploy/ is in plain text: each is a SOPS file, every value encrypted, for the age key .sops.yaml
#      names for its environment (and the check itself is shown to refuse a plain one);
#   2. each cluster's folders (deploy/clusters/<env>) built as Flux builds them (flux build, with the Mac's build
#      values standing in for local's), and checked by kubeconform;
#   3. staging and production pin the chart and every image by digest;
#   4. the chart rendered as each environment's HelmRelease sets it (and plain Helm's values-local.yaml), linted and
#      checked by kubeconform, strict, against Kubernetes' schemas and the custom resources' (the Gateway API's,
#      cert-manager's, Flux's and Flagger's);
#   5. the chart's unit tests (helm-unittest), which hold every pod to the security settings;
#   6. the policies each cluster admits pods by (ADR 0048): staging's and production's never allow the Mac's registry
#      or development's images (the check is shown to refuse a cluster that does), and kyverno test proves what they
#      admit and refuse, signatures included, against the real registries (scripts/deploy/policy-test.sh);
#   7. every finding the nightly scan accepts (scripts/images/trivyignore.yaml) has a reason, a scope and an expiry
#      within 60 days (and the check is shown to refuse one that hasn't).
#
# Each tool runs from its own image, pinned by digest, so a laptop and CI run the same ones and neither needs them
# installed.
set -euo pipefail
. "$(dirname "$0")/../lib/quiet.sh"
quiet_entry deploy "$@"
set -- ${QUIET_ARGS[@]+"${QUIET_ARGS[@]}"}
cd "$(dirname "$0")/../.."
root="$(pwd)"

# helm-unittest's image carries Helm itself (4.3.0), so it lints and renders too.
helm_image=helmunittest/helm-unittest:4.3.0-1.2.1@sha256:28ea86c3e0b07f501f0c2e9c08b9f41e240f4a4b571cb5106b36cf0f0607e989
kubeconform_image=ghcr.io/yannh/kubeconform:v0.8.0@sha256:faffaf43f95aa6425306e1ab8d6fcad72acb9049158f38e574c085ea1ec0f64e
# The Flux CLI of the Flux the clusters run (deploy/flux), for flux build.
flux_image=ghcr.io/fluxcd/flux-cli:v2.10.0@sha256:1a5178b1770052d6e88ab1c7c8434c5ac9bca8a8ab984dcaf6b9df31814ce9f2
# The schemas: Kubernetes' for k3s's release (deploy/k3s), and the custom resources' from the CRDs catalog at a pinned
# commit (the Gateway API's, cert-manager's, Flux's and Flagger's).
kubernetes_version=1.36.0
crd_schemas='https://raw.githubusercontent.com/datreeio/CRDs-catalog/3eb90d47aba8d61919d4419dd38301536e32a94b/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json'
# Downloaded schemas are kept between runs.
cache="$root/private/k8s/kubeconform-cache"
mkdir -p "$cache"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

# Flux's own CustomResourceDefinitions (deploy/flux) are its release's, not ours to check.
kubeconform() {
  docker run --rm -i -v "$cache:/cache" "$kubeconform_image" -strict -summary -cache /cache -skip CustomResourceDefinition \
    -kubernetes-version "$kubernetes_version" -schema-location default -schema-location "$crd_schemas" "$@"
}

# 1. No plain Secret.
echo "deploy: every Secret under deploy/ is encrypted for its environment's key"
secrets_check() {
  python3 - "$@" <<'PY'
import glob, os, re, sys, yaml
root, top = sys.argv[1], sys.argv[2]
rules = yaml.safe_load(open(os.path.join(root, ".sops.yaml")))["creation_rules"] or []
problems = []
for path in sorted(glob.glob(os.path.join(top, "**", "*.y*ml"), recursive=True)):
    relative = os.path.relpath(path, root)
    try:
        documents = [d for d in yaml.safe_load_all(open(path)) if isinstance(d, dict)]
    except yaml.YAMLError:
        continue
    for document in documents:
        if document.get("kind") != "Secret":
            continue
        name = f"{relative} ({document.get('metadata', {}).get('name')})"
        rule = next((r for r in rules if re.search(r["path_regex"], relative)), None)
        if rule is None:
            problems.append(f"{name}: a Secret outside every environment's secrets/ folder (.sops.yaml)")
            continue
        recipients = [entry.get("recipient") for entry in (document.get("sops") or {}).get("age", [])]
        if not recipients:
            problems.append(f"{name}: not encrypted with SOPS")
            continue
        if recipients != [rule["age"]]:
            problems.append(f"{name}: encrypted for {recipients}, not this environment's key {rule['age']}")
        values = {**(document.get("data") or {}), **(document.get("stringData") or {})}
        plain = [key for key, value in values.items() if not str(value).startswith("ENC[")]
        if plain or not values:
            problems.append(f"{name}: values in plain text: {plain or 'none encrypted'}")
sys.exit("\n".join(problems) if problems else 0)
PY
}
secrets_check "$root" "$root/deploy"
# The check refuses a plain Secret, wherever it is.
mkdir -p "$work/refused/deploy/clusters/local/secrets/idleo"
cp "$root/.sops.yaml" "$work/refused/"
printf 'apiVersion: v1\nkind: Secret\nmetadata: {name: plain}\nstringData: {password: hunter2}\n' \
  >"$work/refused/deploy/clusters/local/secrets/idleo/plain.yaml"
if secrets_check "$work/refused" "$work/refused/deploy" 2>/dev/null; then
  echo "deploy: the Secret check let a plain Secret through" >&2
  exit 1
fi

# 2. Each cluster as Flux builds it, from a copy (flux build writes beside the kustomization), with the Mac's build
#    values made up for local's (up.sh --gitops makes the real ones).
tree="$work/tree"
mkdir -p "$tree/templates" "$tree/control-plane"
cp -R deploy "$tree/"
cp templates/development.yaml "$tree/templates/"
cp control-plane/local-sign-in.json "$tree/control-plane/"
zero_digest="sha256:$(printf '0%.0s' $(seq 1 64))"
python3 - "$tree/deploy/clusters/local" "$zero_digest" <<'PY'
import glob, sys
folder, digest = sys.argv[1:]
values = {"GIT_BRANCH": "main", "GIT_COMMIT": "c" * 40, "IMAGE_TAG": "check", "CHART_DIGEST": digest,
          "CONTROL_PLANE_DIGEST": digest, "WEB_DIGEST": digest, "SCANNER_DIGEST": digest}
for path in glob.glob(f"{folder}/**/*.yaml", recursive=True):
    text = open(path).read()
    for name, value in values.items():
        text = text.replace("${" + name + "}", value)
    open(path, "w").write(text)
PY
chmod -R a+rwX "$tree"
flux_build() {
  docker run --rm -v "$tree:/repo" -w /repo "$flux_image" build kustomization "$1" --path "$2" \
    --kustomization-file "$3" --dry-run
}
for environment in local staging production; do
  clusters="deploy/clusters/$environment"
  infrastructure_path="$clusters/infrastructure"
  [[ "$environment" == local ]] && infrastructure_path=deploy/infrastructure/base
  echo "deploy: $environment's cluster, as Flux builds it, and kubeconform"
  # flux build takes a file holding the Kustomization alone; sync.yaml has its GitRepository first.
  python3 - "$tree/$clusters/flux-system/sync.yaml" "$tree/$clusters/flux-system/root.yaml" <<'PY'
import sys, yaml
root = next(d for d in yaml.safe_load_all(open(sys.argv[1])) if d and d["kind"] == "Kustomization")
yaml.safe_dump(root, open(sys.argv[2], "w"))
PY
  {
    flux_build flux-system "$clusters" "$clusters/flux-system/root.yaml"
    echo ---
    flux_build infrastructure "$infrastructure_path" "$clusters/infrastructure.yaml"
    echo ---
    flux_build policies "$(sed -n 's|^  path: \./||p' "$tree/$clusters/policies.yaml")" "$clusters/policies.yaml" |
      tee "$work/$environment-policies.yaml"
    echo ---
    flux_build idleo "$clusters/idleo" "$clusters/idleo.yaml" | tee "$work/$environment-idleo.yaml"
  } | kubeconform
done

# 3 and 4. The release each environment pins, and the chart rendered with its values.
helm_run() {
  docker run --rm --entrypoint helm -v "$root/deploy/helm/idleo:/apps:ro" -v "$work:/work:ro" -w /apps "$helm_image" "$@"
}
for environment in staging production; do
  python3 - "$work/$environment-idleo.yaml" "$work/values-$environment.yaml" "$environment" <<'PY'
import re, sys, yaml
built, out, environment = sys.argv[1:]
documents = [d for d in yaml.safe_load_all(open(built)) if d]
find = lambda kind, name: next(d for d in documents if d["kind"] == kind and d["metadata"]["name"] == name)
release = find("HelmRelease", "idleo")
chart = find("OCIRepository", "idleo-chart")
digest = re.compile(r"^sha256:[0-9a-f]{64}$")
problems = []
if not digest.match(str(chart["spec"].get("ref", {}).get("digest", ""))):
    problems.append("the chart isn't pinned by digest (OCIRepository idleo-chart's ref.digest)")
values = yaml.safe_load(find("ConfigMap", "idleo-values")["data"]["values.yaml"]) or {}
def merge(into, more):
    for key, value in more.items():
        if isinstance(value, dict) and isinstance(into.get(key), dict):
            merge(into[key], value)
        else:
            into[key] = value
merge(values, release["spec"].get("values") or {})
images = values.get("images", {})
if not re.match(r"^[0-9a-f]{40}$", str(images.get("tag", ""))):
    problems.append(f"images.tag isn't a commit: {images.get('tag')}")
for name in ("control-plane", "web", "scanner"):
    if not digest.match(str((images.get("digests") or {}).get(name, ""))):
        problems.append(f"the {name} image isn't pinned by digest")
if problems:
    sys.exit("\n".join(f"{environment}: {p}" for p in problems))
yaml.safe_dump(values, open(out, "w"))
PY
done
python3 - "$root/deploy/helm/idleo/values-local.yaml" "$work/values-local-gitops.yaml" "$zero_digest" <<'PY'
import sys, yaml
values = yaml.safe_load(open(sys.argv[1]))
values["canary"] = {"enabled": True}
values["images"].update({"tag": "check", "digests": {name: sys.argv[3] for name in ("control-plane", "web", "scanner")}})
yaml.safe_dump(values, open(sys.argv[2], "w"))
PY
# Helm's image runs as a user of its own, which must be able to read them.
chmod a+rx "$work"
chmod a+r "$work"/values-*.yaml
for values in values-local.yaml /work/values-local-gitops.yaml /work/values-staging.yaml /work/values-production.yaml; do
  echo "deploy: the chart with $(basename "$values"): lint and kubeconform"
  # Plain Helm's values name no release; up.sh gives the tag.
  tag=()
  [[ "$values" == values-local.yaml ]] && tag=(--set images.tag=check)
  helm_run lint --strict . -f "$values" ${tag[@]+"${tag[@]}"}
  helm_run template idleo . --namespace idleo -f "$values" ${tag[@]+"${tag[@]}"} | tee "$work/rendered.yaml" | kubeconform
  # Two objects of one kind and name would be refused at install (Helm's post-render), which kubeconform can't see.
  python3 - "$work/rendered.yaml" <<'PY'
import collections, sys, yaml
names = collections.Counter((d["kind"], d["metadata"]["name"]) for d in yaml.safe_load_all(open(sys.argv[1])) if d)
twice = [f"{kind} {name}" for (kind, name), count in names.items() if count > 1]
sys.exit(f"the chart renders these twice: {', '.join(twice)}" if twice else 0)
PY
done

# 5. The chart's unit tests, on a copy: helm-unittest writes a snapshot folder into the chart's tests, even with no
#    snapshot to keep.
echo "deploy: helm-unittest"
chart_copy="$work/chart"
cp -R "$root/deploy/helm/idleo/." "$chart_copy/"
chmod -R a+rwX "$chart_copy"
docker run --rm -v "$chart_copy:/apps" "$helm_image" .

# 6. The policies. The signature policies are written from one list (scripts/deploy/signed-policies.py), and staging
#    and production admit only what production may run: no Mac registry, no development list.
scripts/deploy/signed-policies.py --check
echo "deploy: staging's and production's policies hold no local exception, and kyverno test"
local_exception_check() {
  python3 - "$@" <<'PY'
import sys, yaml
problems = []
for path in sys.argv[1:]:
    text = open(path).read()
    if "k3d-idleo-registry" in text:
        problems.append(f"{path}: names the Mac's registry (k3d-idleo-registry)")
    for document in yaml.safe_load_all(text):
        if document and document.get("kind") == "ConfigMap" and "development" in (document.get("data") or {}):
            problems.append(f"{path}: ConfigMap {document['metadata']['name']} has the development list")
    if "idleo-images-signed" not in text:
        problems.append(f"{path}: doesn't check our images' signatures (idleo-images-signed)")
sys.exit("\n".join(problems) if problems else 0)
PY
}
local_exception_check "$work/staging-policies.yaml" "$work/production-policies.yaml"
# The check refuses the Mac's policies, which have the exception.
if local_exception_check "$work/local-policies.yaml" 2>/dev/null; then
  echo "deploy: the policy check let the local exception through" >&2
  exit 1
fi
for environment in staging production; do
  scripts/deploy/policy-test.sh "$work/$environment-policies.yaml"
done

# 7. The findings the nightly scan accepts (scripts/images/trivyignore.yaml): each has a reason, is scoped to a binary,
#    package or resource, and expires within 60 days; none is on our own images (they're scanned without the file).
echo "deploy: the accepted scan findings have a reason, a scope and an expiry within 60 days"
ignore_check() {
  python3 - "$1" <<'PY'
import datetime, sys, yaml
entries = yaml.safe_load(open(sys.argv[1])) or {}
latest = datetime.date.today() + datetime.timedelta(days=60)
problems = [f"{kind}: not a kind Trivy reads" for kind in entries if kind not in ("vulnerabilities", "secrets", "misconfigurations")]
for kind, found in entries.items():
    for entry in found or []:
        name = f"{kind} {entry.get('id', '(no id)')}"
        if not str(entry.get("statement", "")).strip():
            problems.append(f"{name}: no statement (why it's accepted, or its tracking issue)")
        if not (entry.get("paths") or entry.get("purls")):
            problems.append(f"{name}: no paths or purls, so it would apply to every image")
        expiry = entry.get("expired_at")
        if not isinstance(expiry, datetime.date) or expiry > latest:
            problems.append(f"{name}: expired_at must be a date no later than {latest}, not {expiry}")
sys.exit("\n".join(problems) if problems else 0)
PY
}
ignore_check scripts/images/trivyignore.yaml
# The check refuses an entry with no scope, no reason and a year's expiry.
printf 'vulnerabilities:\n  - id: CVE-2026-0001\n    expired_at: %s\n' "$(($(date +%Y) + 1))-01-01" >"$work/ignore.yaml"
if ignore_check "$work/ignore.yaml" 2>/dev/null; then
  echo "deploy: the ignore check let an unscoped, unexplained, year-long entry through" >&2
  exit 1
fi
