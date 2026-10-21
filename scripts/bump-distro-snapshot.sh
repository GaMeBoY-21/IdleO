#!/usr/bin/env bash
# Moves the sealed Linux image's Ubuntu archive forward (agent/distro/SNAPSHOT), so a month's security updates arrive as a
# deliberate rebuild, never by accident. A snapshot lists one version of each package, so the Ubuntu packages the
# Dockerfile pins by version (systemd, containerd, runc, nftables) move to that snapshot's; NVIDIA's toolkit has no
# snapshots and keeps its pin. The image's version (agent/distro/VERSION) goes up by one patch release.
#
#   scripts/bump-distro-snapshot.sh                    to today, 00:00 UTC
#   scripts/bump-distro-snapshot.sh 20261115T000000Z   to that moment
#
# Then build and test it: scripts/build-distro.sh, agent/distro/test.sh, and the hostile tests on the new image
# (scripts/build-distro.sh --dev, scripts/dev-distro.sh up, agent/runner/tests/hostile/run.sh). Commit the three files.
set -euo pipefail
cd "$(dirname "$0")/.."
distro=agent/distro
snapshot="${1:-$(date -u +%Y%m%dT000000Z)}"
[[ "$snapshot" =~ ^[0-9]{8}T[0-9]{6}Z$ ]] || { echo "bump-distro-snapshot: a moment like 20261115T000000Z" >&2; exit 2; }
current="$(tr -d '[:space:]' <"$distro/SNAPSHOT")"
[[ "$snapshot" > "$current" ]] || { echo "bump-distro-snapshot: $snapshot isn't after the current $current" >&2; exit 2; }
base="$(sed -n 's/^FROM \(ubuntu:[^ ]*\).*$/\1/p' "$distro/Dockerfile" | tail -1)"

# What the new snapshot holds for each pinned Ubuntu package, asked of apt itself in the image's own base.
# Each pin as ARG=package.
pins="SYSTEMD_VERSION=systemd CONTAINERD_VERSION=containerd RUNC_VERSION=runc NFTABLES_VERSION=nftables"
versions="$(docker run --rm --platform linux/amd64 "$base" bash -c "
  set -e
  echo 'APT::Snapshot \"$snapshot\";' > /etc/apt/apt.conf.d/50snapshot
  apt-get update -qq >/dev/null
  for package in systemd containerd runc nftables; do
    echo \"\$package \$(apt-cache policy \$package | sed -n 's/^  Candidate: //p')\"
  done")"
for pin in $pins; do
  arg="${pin%%=*}"
  package="${pin#*=}"
  version="$(awk -v p="$package" '$1 == p {print $2}' <<<"$versions")"
  [[ -n "$version" && "$version" != "(none)" ]] || { echo "bump-distro-snapshot: no $package in $snapshot" >&2; exit 1; }
  sed -i.orig "s/^ARG $arg=.*/ARG $arg=$version/" "$distro/Dockerfile"
done
rm -f "$distro/Dockerfile.orig"
printf '%s\n' "$snapshot" >"$distro/SNAPSHOT"
version="$(tr -d '[:space:]' <"$distro/VERSION")"
next="${version%.*}.$((${version##*.} + 1))"
printf '%s\n' "$next" >"$distro/VERSION"
echo "bump-distro-snapshot: $current -> $snapshot, image $version -> $next; pinned:"
sed -n 's/^ARG \(SYSTEMD\|CONTAINERD\|RUNC\|NFTABLES\)_VERSION=/  \1 /p' "$distro/Dockerfile"
echo "Now: scripts/build-distro.sh && agent/distro/test.sh, then the hostile tests (agent/distro/README.md)."
