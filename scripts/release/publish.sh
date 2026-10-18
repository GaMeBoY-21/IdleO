#!/usr/bin/env bash
# Publishes a release of the Windows app to the release feed (ADR 0034): the five programs built for Windows, zipped,
# the sealed Linux image, the bare setup program, and stable/latest.json signed with Ed25519.
#
#   scripts/release/publish.sh --version 0.2.0 --rollout 10        production: signs with the key at $IDLEO_RELEASE_KEY
#                                                                   and uploads to $IDLEO_RELEASE_S3_ENDPOINT (R2)
#   scripts/release/publish.sh --dev --version 0.2.1 --rollout 100 development: the development key and local MinIO
#   scripts/release/publish.sh --version 0.2.0 --rollout 50 --manifest-only
#                                                                   the same files, a new signed manifest: how a rollout
#                                                                   is raised, or stopped (--rollout 0)
#
# 1. builds the Windows programs with this version (scripts/build-windows.sh; --release-keys unless --dev);
# 2. Authenticode-signs them (scripts/release/sign-windows.sh), before anything is hashed;
# 3. zips the app, takes the distro tarball from dist/distro/ (scripts/build-distro.sh makes it);
# 4. writes the manifest and signs it; 5. uploads the files, then the manifest and its signature.
#
# Production needs IDLEO_RELEASE_KEY (the private key's path, which only idleo-release sign reads),
# IDLEO_RELEASE_S3_ENDPOINT, IDLEO_RELEASE_S3_ACCESS_KEY_ID and IDLEO_RELEASE_S3_SECRET_ACCESS_KEY, and the setup
# program's IDLEO_SETUP_* addresses. --dev uses private/release-dev/ (scripts/release/dev-key.sh) and the local
# stack's MinIO, at IDLEO_RELEASE_S3_ENDPOINT if set (./start.sh --lan sets the LAN address). IDLEO_TEST_FAIL_TRIAL=1
# is passed through to nothing: it is read by the installed agent at run time (development builds only).
set -euo pipefail
cd "$(dirname "$0")/../.."
root="$(pwd)"
version=""
rollout=""
dev=false
manifest_only=false
while (($# > 0)); do
  case "$1" in
    --version) version="$2"; shift ;;
    --rollout) rollout="$2"; shift ;;
    --dev) dev=true ;;
    --manifest-only) manifest_only=true ;;
    *) echo "publish: unknown option $1" >&2; exit 2 ;;
  esac
  shift
done
[[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "publish: --version must be X.Y.Z" >&2; exit 2; }
[[ "$rollout" =~ ^[0-9]+$ ]] && ((rollout <= 100)) || { echo "publish: --rollout must be 0 to 100" >&2; exit 2; }
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
  build_flags=()
else
  key="${IDLEO_RELEASE_KEY:?set IDLEO_RELEASE_KEY to the path of the private release key}"
  public="$root/agent/release-keys/release.pub"
  : "${IDLEO_RELEASE_S3_ENDPOINT:?set IDLEO_RELEASE_S3_ENDPOINT}"
  region=auto
  create=()
  build_flags=(--release-keys)
fi
bucket="${IDLEO_RELEASE_S3_BUCKET:-releases}"
release="$root/dist/release/$version"
distro_version="$(tr -d '[:space:]' <agent/distro/VERSION)"
tarball="$root/dist/distro/idleo-distro-$distro_version.tar.gz"
zip="$release/idleo-app-$version.zip"
setup="$release/IdleO-Setup.exe"
manifest="$root/dist/release/latest.json"
idleo_release() { (cd "$root/agent" && cargo run --quiet --locked -p release --bin idleo-release -- "$@"); }

if ! $manifest_only; then
  [[ -s "$tarball" ]] || { echo "publish: $tarball is missing: run scripts/build-distro.sh first" >&2; exit 1; }
  IDLEO_APP_VERSION="$version" "$root/scripts/build-windows.sh" ${build_flags[@]+"${build_flags[@]}"}
  "$root/scripts/release/sign-windows.sh" "$root/dist/windows"
  rm -rf "$release"
  mkdir -p "$release"
  (cd "$root/dist/windows" && zip -q -X -j "$zip" idleo-agent.exe idleo-hostd.exe idleo-tray.exe idleo-launcher.exe \
    IdleO-Setup.exe LICENSE-PlusJakartaSans.txt)
  cp "$root/dist/windows/IdleO-Setup.exe" "$setup"
fi
[[ -s "$zip" && -s "$setup" ]] || { echo "publish: no built files for $version in dist/release/; publish it without --manifest-only first" >&2; exit 1; }

idleo_release manifest --version "$version" --rollout "$rollout" --app "$zip" --distro "$tarball" \
  --distro-version "$distro_version" --setup "$setup" --out "$manifest"
idleo_release sign --key "$key" "$manifest"
idleo_release verify --public "$public" "$manifest"

uploads=()
if ! $manifest_only; then
  uploads+=("$zip=stable/$version/idleo-app-$version.zip" "$setup=stable/$version/IdleO-Setup.exe"
    "$tarball=stable/distro/idleo-distro-$distro_version.tar.gz")
fi
# The files first, then the manifest and its signature: the feed never names a file that isn't there.
uploads+=("$manifest=stable/latest.json" "$manifest.sig=stable/latest.json.sig")
idleo_release upload --endpoint "$IDLEO_RELEASE_S3_ENDPOINT" --bucket "$bucket" --region "$region" \
  ${create[@]+"${create[@]}"} "${uploads[@]}"
echo "publish: $version at $rollout% is the latest release; control planes read it within 5 minutes"
