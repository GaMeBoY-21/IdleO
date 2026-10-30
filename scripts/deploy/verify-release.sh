#!/usr/bin/env bash
# Checks an environment's release before a pull request pins it (ADR 0046): every image as scripts/images/verify.sh
# checks it (its signature, bill of materials and build provenance, all from this repository's images workflow on main)
# and the chart's signature from the same workflow, the one the cluster's Flux checks again before installing it.
#
#   scripts/deploy/verify-release.sh staging|production
set -euo pipefail
cd "$(dirname "$0")/../.."
environment="${1:?usage: $0 staging|production}"
release="$(scripts/deploy/release.py resolve "$(scripts/deploy/release.py commit "$environment")")"
pinned="$(sed -n 's/^    digest: //p; s/^        \([a-z-]*\): \(sha256:.*\)$/\1 \2/p' "deploy/clusters/$environment/idleo/release.yaml")"
# What the file pins is what GHCR holds for that commit.
python3 - "$release" "$pinned" <<'PY'
import json, sys
release, pinned = json.loads(sys.argv[1]), sys.argv[2].splitlines()
expected = [release["chart"]["digest"]] + [f"{name} {digest}" for name, digest in release["images"].items()]
if sorted(pinned) != sorted(expected):
    sys.exit(f"verify: the release file pins {pinned}, not what GHCR holds for {release['commit']}: {expected}")
PY
for image in control-plane web scanner; do
  digest="$(python3 -c 'import json, sys; print(json.loads(sys.argv[1])["images"][sys.argv[2]])' "$release" "$image")"
  scripts/images/verify.sh "ghcr.io/gameboy-21/idleo-$image@$digest"
done
chart="$(python3 -c 'import json, sys; print(json.loads(sys.argv[1])["chart"]["digest"])' "$release")"
cosign verify "ghcr.io/gameboy-21/charts/idleo@$chart" \
  --certificate-identity-regexp '^https://github\.com/GaMeBoY-21/IdleO/\.github/workflows/images\.yml@refs/heads/main$' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com >/dev/null
echo "verify: PASS: the chart ghcr.io/gameboy-21/charts/idleo@$chart, signed by the images workflow on main"
