#!/usr/bin/env bash
# Makes the production release key pair (ADR 0034): the public half goes to agent/release-keys/release.pub, which a
# release build bakes in and the control plane reads (IDLEO_RELEASE_PUBLIC_KEY); the private half only to the path you
# give, outside the repository. Nothing here, or anything we ship, reads the private key again: only
# `idleo-release sign --key <path>` does, when you publish.
#
#   scripts/release/new-release-key.sh /Volumes/USB/idleo-release.key
set -euo pipefail
cd "$(dirname "$0")/../.."
root="$(pwd)"
private="${1:-}"
[[ -n "$private" ]] || { echo "usage: scripts/release/new-release-key.sh <where the private key goes, outside the repository>" >&2; exit 2; }
case "$(cd "$(dirname "$private")" 2>/dev/null && pwd)/" in
  "$root"/*) echo "new-release-key: the private key must not go inside the repository" >&2; exit 2 ;;
esac
[[ -e "$private" ]] && { echo "new-release-key: $private exists; a key is never overwritten" >&2; exit 2; }
public="$root/agent/release-keys/release.pub"
if [[ -s "$public" ]]; then
  echo "new-release-key: agent/release-keys/release.pub already holds a key. Replacing it means every installed app" >&2
  echo "  refuses releases signed with the new one until it is updated by hand; empty the file first if you mean it." >&2
  exit 2
fi
# shellcheck source=scripts/lib/toolchains.sh
. "$root/scripts/lib/toolchains.sh"
(cd agent && cargo run --quiet --locked -p release --bin idleo-release -- keygen --public "$public" --private "$private")
echo
echo "Keep the private key offline (a password manager or a USB stick). Never commit it."
echo "Commit agent/release-keys/release.pub, and set IDLEO_RELEASE_PUBLIC_KEY to its contents in production."
