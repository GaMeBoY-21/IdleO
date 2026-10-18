#!/usr/bin/env bash
# Makes the development release key pair (ADR 0034) into private/release-dev/ (git-ignored), once: release.pub is
# baked into normal builds of the Windows app and given to the local control plane; release.key signs development
# releases (scripts/release/publish.sh --dev). Never a production key. Prints nothing secret.
set -euo pipefail
cd "$(dirname "$0")/../.."
root="$(pwd)"
# shellcheck source=scripts/lib/toolchains.sh
. "$root/scripts/lib/toolchains.sh"
out="$root/private/release-dev"
if [[ -s "$out/release.key" && -s "$out/release.pub" ]]; then
  echo "dev-key: the development release key is already in private/release-dev/; keeping it"
  exit 0
fi
mkdir -p "$out"
rm -f "$out/release.key" "$out/release.pub"
(cd agent && cargo run --quiet --locked -p release --bin idleo-release -- keygen --public "$out/release.pub" --private "$out/release.key")
echo "dev-key: normal builds of the Windows app now trust it (rebuild them), and so does the local stack (restart it)"
