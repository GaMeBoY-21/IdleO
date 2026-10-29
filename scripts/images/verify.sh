#!/usr/bin/env bash
# Checks one of the server's images as the cluster will before running it (ADR 0045; S4's policy does the same by
# itself): its cosign signature, its CycloneDX bill of materials and its build provenance were all made by this
# repository's images workflow (.github/workflows/images.yml) on main or a release tag, through GitHub's OIDC issuer.
# Nothing is trusted by name: a tag is resolved to its digest first, and the digest is what's checked.
#
#   scripts/images/verify.sh ghcr.io/gameboy-21/idleo-control-plane:<commit>
#   scripts/images/verify.sh ghcr.io/gameboy-21/idleo-web@sha256:…
#
# It needs cosign, gh (signed in) and Docker's buildx, and prints the digest it verified.
set -euo pipefail
log() { printf 'verify: %s\n' "$*"; }
fail() { printf 'verify: FAIL: %s\n' "$*" >&2; exit 1; }
[[ $# == 1 ]] || { echo "usage: $0 <image reference>" >&2; exit 2; }
for tool in cosign gh; do
  command -v "$tool" >/dev/null || fail "$tool isn't installed (macOS: brew install $tool)"
done

repository=GaMeBoY-21/IdleO
workflow="https://github.com/$repository/.github/workflows/images.yml"
identity="^${workflow//./\\.}@refs/(heads/main|tags/v.+)\$"
issuer=https://token.actions.githubusercontent.com
reference="$1"
name="${reference%@*}"
[[ "$reference" == *@sha256:* ]] || name="${reference%:*}"

digest="$(docker buildx imagetools inspect "$reference" --format '{{.Manifest.Digest}}' 2>/dev/null || true)"
[[ "$digest" == sha256:* ]] || fail "couldn't resolve $reference to a digest (does it exist, and can you read it?)"
pinned="$name@$digest"
log "checking $pinned"

cosign verify --certificate-identity-regexp "$identity" --certificate-oidc-issuer "$issuer" "$pinned" >/dev/null ||
  fail "$pinned has no signature from $workflow"
log "signature: made by $workflow"

cosign verify-attestation --type cyclonedx --certificate-identity-regexp "$identity" --certificate-oidc-issuer "$issuer" \
  "$pinned" >/dev/null || fail "$pinned has no CycloneDX bill of materials from $workflow"
log "bill of materials: CycloneDX, attested by the same workflow"

gh attestation verify "oci://$pinned" --repo "$repository" --signer-workflow "$repository/.github/workflows/images.yml" \
  >/dev/null || fail "$pinned has no build provenance from $workflow"
log "build provenance: SLSA, from the same workflow"
log "PASS: $pinned"
