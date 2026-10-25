#!/usr/bin/env bash
# Puts the abuse blocklist the sealed Linux image starts from (agent/distro/blocklist.json), its signature and the release key
# that checks it into DIR, which the image copies in (ADR 0038, ADR 0041). The runner uses a list only if its signature
# verifies against that key, so the image's own copy is signed the way the published one is (`idleo-release sign`, ADR 0034):
#
#   scripts/release/sign-distro-blocklist.sh DIR     development: the development key (made once, private/release-dev/)
#   IDLEO_RELEASE_KEY=/path/to/key scripts/release/sign-distro-blocklist.sh DIR
#                                                    production: signed by that key; the image carries agent/release-keys/release.pub
#
# DIR gets blocklist.json, blocklist.json.sig and release.pub. The signature is deterministic, so a second build of the same
# list is byte for byte the same.
set -euo pipefail
# Made absolute first: this script changes directory, and toolchains.sh reuses common variable names.
into="$(cd "$(dirname "${1:?usage: sign-distro-blocklist.sh DIR}")" && pwd)/$(basename "$1")"
cd "$(dirname "$0")/../.."
root="$(pwd)"
# shellcheck source=scripts/lib/toolchains.sh
. "$root/scripts/lib/toolchains.sh"
if [[ -n "${IDLEO_RELEASE_KEY:-}" ]]; then
  key="$IDLEO_RELEASE_KEY"
  public="$root/agent/release-keys/release.pub"
  [[ -s "$public" ]] || { echo "sign-distro-blocklist: agent/release-keys/release.pub is empty" >&2; exit 1; }
else
  "$root/scripts/release/dev-key.sh" >/dev/null
  key="$root/private/release-dev/release.key"
  public="$root/private/release-dev/release.pub"
fi
(cd agent && cargo build --release --locked --quiet -p release --bin idleo-release)
tool="$root/agent/target/release/idleo-release"
mkdir -p "$into"
cp agent/distro/blocklist.json "$into/blocklist.json"
cp "$public" "$into/release.pub"
"$tool" sign --key "$key" "$into/blocklist.json"
"$tool" verify-blocklist --public "$into/release.pub" "$into/blocklist.json"
