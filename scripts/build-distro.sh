#!/usr/bin/env bash
# Builds the sealed Linux image the Windows installer will import into WSL2 (agent/distro/README.md): the image for
# linux/amd64 whatever machine this runs on (the laptops are x86-64, Nikki's Mac is not), its root filesystem as
# dist/distro/idleo-distro-<version>.tar.gz, the tarball's SHA-256, and a manifest of what is in it.
#
#   scripts/build-distro.sh              build, using Docker's layer cache
#   scripts/build-distro.sh --no-cache   build everything again (the reproducibility test does)
#   scripts/build-distro.sh --out DIR    write the tarball, its checksum and manifest to DIR [default: dist/distro]
#
# The version is agent/distro/VERSION, the one place it is written. The image is also left in Docker as
# idleo-distro:<version>, which agent/distro/test.sh inspects.
set -euo pipefail

cd "$(dirname "$0")/.."
root="$(pwd)"
distro="$root/agent/distro"
version="$(tr -d '[:space:]' <"$distro/VERSION")"
[[ -n "$version" ]] || { echo "build-distro: agent/distro/VERSION is empty" >&2; exit 2; }
cache=()
out="$root/dist/distro"
while (($# > 0)); do
  case "$1" in
    --no-cache) cache=(--no-cache) ;;
    --out) out="$2"; shift ;;
    *) echo "build-distro: unknown option $1" >&2; exit 2 ;;
  esac
  shift
done
command -v docker >/dev/null || { echo "build-distro: Docker isn't installed" >&2; exit 1; }
docker info >/dev/null 2>&1 || { echo "build-distro: Docker isn't running" >&2; exit 1; }

image="idleo-distro:$version"
tarball="idleo-distro-$version.tar.gz"
mkdir -p "$out"
context="$(mktemp -d)"
trap 'rm -rf "$context"; docker rm -f "idleo-distro-export-$$" >/dev/null 2>&1 || true' EXIT

# The build context is only what the image copies in, so nothing else in the repository can reach it.
cp "$distro/Dockerfile" "$distro/wsl.conf" "$distro/verify-lockdown.sh" "$distro/runner-placeholder.sh" "$context/"
cp "$root/phase0/wsl/gpu-common.sh" "$context/"

started=$SECONDS
docker buildx build --platform linux/amd64 ${cache[@]+"${cache[@]}"} --build-arg "IDLEO_DISTRO_VERSION=$version" \
  --tag "$image" --load "$context"

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
