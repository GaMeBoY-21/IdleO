#!/usr/bin/env bash
# Builds the sealed Linux image the Windows installer will import into WSL2 (agent/distro/README.md): the image for
# linux/amd64 whatever machine this runs on (the laptops are x86-64, Nikki's Mac is not), its root filesystem as
# dist/distro/idleo-distro-<version>.tar.gz, the tarball's SHA-256, and a manifest of what is in it.
#
#   scripts/build-distro.sh              build, using Docker's layer cache
#   scripts/build-distro.sh --no-cache   build everything again (the reproducibility test does)
#   scripts/build-distro.sh --out DIR    write the tarball, its checksum and manifest to DIR [default: dist/distro]
#   scripts/build-distro.sh --dev        only the development stand-in's image (scripts/dev-distro.sh), for linux/arm64 and left
#                                        in Docker as idleo-distro-dev:<version>: no tarball. amd64 under Rosetta can't run a
#                                        container inside itself, so a Mac tests the sandbox in the same recipe built natively.
#
# The version is agent/distro/VERSION, the one place it is written, and the Ubuntu archive's moment agent/distro/SNAPSHOT.
# The image is also left in Docker as idleo-distro:<version>, which agent/distro/test.sh inspects.
set -euo pipefail

cd "$(dirname "$0")/.."
root="$(pwd)"
distro="$root/agent/distro"
version="$(tr -d '[:space:]' <"$distro/VERSION")"
[[ -n "$version" ]] || { echo "build-distro: agent/distro/VERSION is empty" >&2; exit 2; }
# The Ubuntu archive's moment the packages come from (agent/distro/SNAPSHOT; scripts/bump-distro-snapshot.sh moves it).
snapshot="$(tr -d '[:space:]' <"$distro/SNAPSHOT")"
[[ "$snapshot" =~ ^[0-9]{8}T[0-9]{6}Z$ ]] ||
  { echo "build-distro: agent/distro/SNAPSHOT must be a snapshot.ubuntu.com moment, like 20261020T000000Z" >&2; exit 2; }
cache=()
dev=false
out="$root/dist/distro"
while (($# > 0)); do
  case "$1" in
    --no-cache) cache=(--no-cache) ;;
    --dev) dev=true ;;
    --out) out="$2"; shift ;;
    *) echo "build-distro: unknown option $1" >&2; exit 2 ;;
  esac
  shift
done
command -v docker >/dev/null || { echo "build-distro: Docker isn't installed" >&2; exit 1; }
docker info >/dev/null 2>&1 || { echo "build-distro: Docker isn't running" >&2; exit 1; }

platform=linux/amd64
image="idleo-distro:$version"
if $dev; then
  platform="linux/$(uname -m | sed 's/x86_64/amd64/; s/aarch64/arm64/')"
  image="idleo-distro-dev:$version"
fi
tarball="idleo-distro-$version.tar.gz"
mkdir -p "$out"
context="$(mktemp -d)"
trap 'rm -rf "$context"; docker rm -f "idleo-distro-export-$$" >/dev/null 2>&1 || true' EXIT

# The build context is only what the image copies in, so nothing else in the repository can reach it.
cp "$distro/Dockerfile" "$distro/wsl.conf" "$distro/verify-lockdown.sh" "$distro/sandbox.nft" \
  "$distro/import-images.sh" "$distro/idleo-images.service" "$distro/idleo-sandbox.service" \
  "$distro/idleo-runner.service" "$context/"
cp "$root/phase0/wsl/gpu-common.sh" "$context/"
# The abuse blocklist the runner starts from, signed, with the release key that checks it: the runner uses a list only if its
# signature verifies (IDLEO_RELEASE_KEY signs a production image; otherwise the development key does).
"$root/scripts/release/sign-distro-blocklist.sh" "$context"
# idleo-runner is built inside the image from the agent workspace's own source (ADR 0035): the build context holds it and the
# protocol files its build needs, without build output.
mkdir -p "$context/agent" "$context/proto"
rsync -a --exclude target --exclude .git "$root/agent/" "$context/agent/"
rsync -a --exclude .git "$root/proto/" "$context/proto/"

started=$SECONDS
# The GPU-check image first, saved as an archive containerd imports (docker save keeps its name).
gpu_check="docker.io/idleo/gpu-check:local"
docker buildx build --platform "$platform" ${cache[@]+"${cache[@]}"} -f "$distro/gpu-check.Dockerfile" \
  --tag "$gpu_check" --load "$distro"
docker save "$gpu_check" -o "$context/gpu-check.tar"

docker buildx build --platform "$platform" ${cache[@]+"${cache[@]}"} --build-arg "IDLEO_DISTRO_VERSION=$version" \
  --build-arg "UBUNTU_SNAPSHOT=$snapshot" \
  --tag "$image" --load "$context"

if $dev; then
  echo "build-distro: $image built for $platform ($((SECONDS - started)) s)"
  exit 0
fi

# The root filesystem, as WSL imports it. `docker export` flattens the layers; gzip -n leaves the time out of its header.
docker create --platform linux/amd64 --name "idleo-distro-export-$$" "$image" /bin/true >/dev/null
docker export "idleo-distro-export-$$" | gzip -n -9 >"$out/$tarball"

sha="$(shasum -a 256 "$out/$tarball" | awk '{print $1}')"
printf '%s  %s\n' "$sha" "$tarball" >"$out/$tarball.sha256"

# What is in it: the base by digest, every package with its version, the size and the build date.
base="$(sed -n 's/^FROM \(.*\)$/\1/p' "$distro/Dockerfile" | head -1)"
packages="$(docker run --rm --platform linux/amd64 "$image" dpkg-query -W -f='${Package} ${Version}\n' | sort)"
printf '%s\n' "$packages" >"$out/idleo-distro-$version.packages.txt"
python3 - "$out/idleo-distro-$version.manifest.json" "$version" "$base" "$tarball" "$sha" "$(wc -c <"$out/$tarball" | tr -d ' ')" \
  "$out/idleo-distro-$version.packages.txt" <<'PY'
import json, sys, datetime
path, version, base, tarball, sha, size, packages = sys.argv[1:8]
pkgs = [dict(zip(("name", "version"), line.split(" ", 1))) for line in open(packages).read().splitlines() if line]
json.dump(
    {
        "version": version,
        "platform": "linux/amd64",
        "base": base,
        "tarball": tarball,
        "sha256": sha,
        "sizeBytes": int(size),
        "builtAt": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "packageCount": len(pkgs),
        "packages": pkgs,
    },
    open(path, "w"),
    indent=2,
)
PY

# TODO(signing): the one place the tarball is signed, once there is a code-signing certificate (the installer checks
# the signature before it imports anything). Until then it is checked only against its SHA-256 and the manifest.
sign_artifact() { :; }
sign_artifact "$out/$tarball"

printf 'build-distro: %s: %s MB, sha256 %s, %s packages, %s s\n' "$tarball" \
  "$(($(wc -c <"$out/$tarball") / 1000000))" "$sha" "$(wc -l <"$out/idleo-distro-$version.packages.txt" | tr -d ' ')" "$((SECONDS - started))"
