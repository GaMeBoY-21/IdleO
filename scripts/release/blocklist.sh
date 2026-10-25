#!/usr/bin/env bash
# Publishes the abuse blocklist (ADR 0038) to the release feed: agent/distro/blocklist.json with a version of now and the
# builds admins confirmed as abuse, signed with the release key (the same key and the same `idleo-release sign` as the app's
# manifest, ADR 0034), as stable/blocklist.json and stable/blocklist.json.sig. Hosts' agents get it within minutes of the
# control plane's next read of the feed, with no new distribution.
#
#   scripts/release/blocklist.sh --dev                              development: the development key and local MinIO
#   scripts/release/blocklist.sh                                    production: the key at $IDLEO_RELEASE_KEY, R2
#   scripts/release/blocklist.sh --additions builds.json            also list the builds in builds.json, the control plane's
#                                                                   `export-blocklist-additions` (docs/runbooks/abuse.md)
#
# Production needs IDLEO_RELEASE_KEY (the private key's path, which only idleo-release sign reads),
# IDLEO_RELEASE_S3_ENDPOINT, IDLEO_RELEASE_S3_ACCESS_KEY_ID and IDLEO_RELEASE_S3_SECRET_ACCESS_KEY. To pull a wrong entry,
# take it out of agent/distro/blocklist.json (or leave the build out of the additions) and run this again: the new list is
# newer, so every agent takes it.
set -euo pipefail
cd "$(dirname "$0")/../.."
root="$(pwd)"
dev=false
additions=()
while (($# > 0)); do
  case "$1" in
    --dev) dev=true ;;
    --additions) additions=(--additions "$2"); shift ;;
    *) echo "blocklist: unknown option $1" >&2; exit 2 ;;
  esac
  shift
done
# shellcheck source=scripts/lib/toolchains.sh
. "$root/scripts/lib/toolchains.sh"
if $dev; then
  "$root/scripts/release/dev-key.sh"
  key="$root/private/release-dev/release.key"
  public="$root/private/release-dev/release.pub"
  export IDLEO_RELEASE_S3_ENDPOINT="${IDLEO_RELEASE_S3_ENDPOINT:-http://localhost:${LOCAL_STORAGE_PORT:-9100}}"
  export IDLEO_RELEASE_S3_ACCESS_KEY_ID="${IDLEO_RELEASE_S3_ACCESS_KEY_ID:-local-storage-root}"
  export IDLEO_RELEASE_S3_SECRET_ACCESS_KEY="${IDLEO_RELEASE_S3_SECRET_ACCESS_KEY:-local-only-not-a-secret}"
  region=us-east-1
  create=(--create-bucket)
else
  key="${IDLEO_RELEASE_KEY:?set IDLEO_RELEASE_KEY to the path of the private release key}"
  public="$root/agent/release-keys/release.pub"
  : "${IDLEO_RELEASE_S3_ENDPOINT:?set IDLEO_RELEASE_S3_ENDPOINT}"
  region=auto
  create=()
fi
bucket="${IDLEO_RELEASE_S3_BUCKET:-releases}"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
(cd agent && cargo build --release --locked --quiet -p release --bin idleo-release)
tool="$root/agent/target/release/idleo-release"
"$tool" blocklist --base "$root/agent/distro/blocklist.json" ${additions[@]+"${additions[@]}"} --out "$work/blocklist.json"
"$tool" sign --key "$key" "$work/blocklist.json"
"$tool" verify-blocklist --public "$public" "$work/blocklist.json"
"$tool" upload --endpoint "$IDLEO_RELEASE_S3_ENDPOINT" --bucket "$bucket" --region "$region" ${create[@]+"${create[@]}"} \
  "$work/blocklist.json=stable/blocklist.json" "$work/blocklist.json.sig=stable/blocklist.json.sig"
echo "blocklist: published stable/blocklist.json to $IDLEO_RELEASE_S3_ENDPOINT"
