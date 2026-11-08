#!/usr/bin/env bash
# Puts WAL-G for Docker's architecture in ops/postgres/wal-g/ (gitignored), where the Dockerfile copies it from and
# checks it again. Its hash is pinned in wal-g.sha256. A download that fails or comes back with the wrong bytes is tried
# again, and the file is kept in a cache by its hash (.github/actions/wal-g restores that cache in CI), so a bad answer
# from GitHub's CDN never fails a run by chance. After the last try it stops and says what came back.
#
# Docker's own ADD --checksum did this until 2026-11: it has no retry, and it fetched both architectures.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
version=v3.0.9
base="${WAL_G_BASE:-https://github.com/wal-g/wal-g/releases/download/$version}"
tries="${WAL_G_TRIES:-5}"
pins="${WAL_G_PINS:-$here/wal-g.sha256}"
cache="${WAL_G_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/idleo/wal-g}"
out="${WAL_G_OUT:-$here/wal-g}"
arch="${WAL_G_ARCH:-$(docker version --format '{{.Server.Arch}}')}"

case "$arch" in
  amd64) asset=wal-g-pg-24.04-amd64 ;;
  arm64) asset=wal-g-pg-24.04-aarch64 ;;
  *) echo "fetch-wal-g: no WAL-G is pinned for $arch" >&2; exit 1 ;;
esac
name="wal-g-$arch"
want="$(awk -v name="$name" '$2 == name { print $1 }' "$pins")"
[[ -n "$want" ]] || { echo "fetch-wal-g: $pins has no hash for $name" >&2; exit 1; }

hash_of() { shasum -a 256 "$1" | cut -d' ' -f1; }

mkdir -p "$cache" "$out"
kept="$cache/$want"
if [[ ! -f "$kept" || "$(hash_of "$kept")" != "$want" ]]; then
  rm -f "$kept"
  partial="$kept.partial"
  got="nothing"
  for ((try = 1; try <= tries; try++)); do
    if curl --fail --silent --show-error --location --max-time 300 --output "$partial" "$base/$asset"; then
      got="$(hash_of "$partial") ($(wc -c <"$partial" | tr -d ' ') bytes)"
      if [[ "$(hash_of "$partial")" == "$want" ]]; then
        mv "$partial" "$kept"
        break
      fi
      echo "fetch-wal-g: try $try of $tries: $asset came back as $got, expected $want" >&2
    else
      echo "fetch-wal-g: try $try of $tries: couldn't download $base/$asset" >&2
    fi
    rm -f "$partial"
    ((try < tries)) && sleep "$((try * try))"
  done
  if [[ ! -f "$kept" ]]; then
    echo "fetch-wal-g: FAILED: $asset is not the pinned file after $tries tries: expected $want, last got $got" >&2
    exit 1
  fi
fi
# The build context holds only this one file.
rm -f "$out"/wal-g-*
cp "$kept" "$out/$name"
chmod 0755 "$out/$name"
echo "fetch-wal-g: $name is the pinned $want"
