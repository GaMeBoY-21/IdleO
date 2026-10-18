#!/usr/bin/env bash
# Checks the sealed Linux image (agent/distro/README.md): it builds; the wsl.conf inside is the repository's; no sshd,
# compiler or sudo; the `idleo` user exists and has no privileges; containerd and the NVIDIA container toolkit are at the
# versions the Dockerfile pins; the runner is the placeholder; the compressed tarball is under 400 MB; and a second build
# from the same inputs has the same package list. scripts/check-all.sh runs it (`--no-distro` leaves it out).
#
#   agent/distro/test.sh            everything, including a second build from scratch (a few minutes)
#   agent/distro/test.sh --quick    without the second build
set -euo pipefail

cd "$(dirname "$0")/../.."
root="$(pwd)"
quick=false
[[ "${1:-}" == "--quick" ]] && quick=true
failed=0
pass() { printf 'PASS  %s\n' "$1"; }
fail() { printf 'FAIL  %s\n' "$1" >&2; shift; for line in "$@"; do printf '      %s\n' "$line" >&2; done; failed=$((failed + 1)); }

version="$(tr -d '[:space:]' <agent/distro/VERSION)"
pinned() { sed -n "s/^ARG $1=\(.*\)\$/\1/p" agent/distro/Dockerfile | head -1; }
containerd_pin="$(pinned CONTAINERD_VERSION)"
toolkit_pin="$(pinned NVIDIA_TOOLKIT_VERSION)"
image="idleo-distro:$version"
out="$root/dist/distro"
tarball="$out/idleo-distro-$version.tar.gz"
in_image() { docker run --rm --platform linux/amd64 "$image" "$@"; }

echo "distro test: building $image"
"$root/scripts/build-distro.sh" >/dev/null && pass "the image builds, for linux/amd64" || { fail "the image builds"; exit 1; }
[[ "$(docker image inspect --format '{{.Architecture}}' "$image")" == amd64 ]] && pass "it is an amd64 image" || fail "it is an amd64 image"

# The lockdown inside is the repository's own file.
if diff <(in_image cat /etc/wsl.conf) agent/distro/wsl.conf >/dev/null; then
  pass "/etc/wsl.conf is agent/distro/wsl.conf"
else
  fail "/etc/wsl.conf is agent/distro/wsl.conf" "$(diff <(in_image cat /etc/wsl.conf) agent/distro/wsl.conf | head -5)"
fi

# Nothing a job could use to get out or build something.
present="$(in_image sh -c 'for c in sshd ssh gcc cc g++ make sudo su-exec; do command -v "$c" 2>/dev/null; done' || true)"
packages="$(in_image dpkg-query -W -f='${Package}\n' | grep -E '^(openssh-server|openssh-client|gcc|g\+\+|cpp|build-essential|make|sudo)(-[0-9]+)?(:|$)' || true)"
if [[ -z "$present" && -z "$packages" ]]; then
  pass "no sshd, compiler or sudo"
else
  fail "no sshd, compiler or sudo" "programs: ${present:-none}" "packages: ${packages:-none}"
fi

# The job user.
if in_image id idleo >/dev/null 2>&1; then
  groups="$(in_image id -nG idleo)"
  shell="$(in_image getent passwd idleo | cut -d: -f7)"
  if [[ "$groups" == idleo && "$shell" == /usr/sbin/nologin && "$(in_image id -u idleo)" != 0 ]]; then
    pass "the idleo user exists, is in no group but its own, and can't log in"
  else
    fail "the idleo user is unprivileged" "groups: $groups" "shell: $shell"
  fi
else
  fail "the idleo user exists"
fi

# The pinned versions.
[[ "$(in_image dpkg-query -W -f='${Version}' containerd)" == "$containerd_pin" ]] &&
  pass "containerd is $containerd_pin" || fail "containerd is $containerd_pin" "found $(in_image dpkg-query -W -f='${Version}' containerd)"
[[ "$(in_image dpkg-query -W -f='${Version}' nvidia-container-toolkit)" == "$toolkit_pin" ]] &&
  in_image nvidia-ctk --version 2>&1 | grep -q "${toolkit_pin%-*}" &&
  pass "the NVIDIA container toolkit is $toolkit_pin, and nvidia-ctk runs" ||
  fail "the NVIDIA container toolkit is $toolkit_pin" "found $(in_image nvidia-ctk --version 2>&1 | head -2 | tr '\n' ' ')"

# The runner is the placeholder, and says so.
runner="$(in_image /usr/local/bin/idleo-runner 2>&1 || true)"
[[ "$runner" == *"not installed yet"* ]] && pass "the runner is the placeholder, which says it isn't installed" ||
  fail "the runner is the placeholder" "$runner"

# The proof script is there, executable, and is the repository's file.
if diff <(in_image cat /usr/local/share/idleo/verify-lockdown.sh) agent/distro/verify-lockdown.sh >/dev/null &&
  in_image test -x /usr/local/share/idleo/verify-lockdown.sh; then
  pass "verify-lockdown.sh is in the image, executable, and is agent/distro/verify-lockdown.sh"
else
  fail "verify-lockdown.sh is in the image"
fi
[[ "$(in_image cat /etc/idleo-distro-version)" == "$version" ]] && pass "the image says its version, $version" || fail "the image says its version"

# The GPU-check image is baked in, named as the self-test runs it, and imported by a service enabled at boot.
# containerd names an imported image from the archive's index.json annotation.
if in_image sh -c 'tar -xOf /opt/idleo/images/gpu-check.tar index.json' | grep -q '"io.containerd.image.name":"docker.io/idleo/gpu-check:local"'; then
  pass "the GPU-check image is in the image, as docker.io/idleo/gpu-check:local"
else
  fail "the GPU-check image is in the image" "$(in_image sh -c 'tar -tf /opt/idleo/images/gpu-check.tar | head -5' 2>&1)"
fi
entrypoint="$(docker image inspect --format '{{json .Config.Entrypoint}}' docker.io/idleo/gpu-check:local)"
[[ "$entrypoint" == '["nvidia-smi","--query-gpu=name,memory.total","--format=csv,noheader"]' ]] &&
  pass "the GPU-check image runs nvidia-smi's name and memory query" || fail "the GPU-check image's entrypoint" "$entrypoint"
[[ "$(docker image inspect --format '{{.Architecture}}' docker.io/idleo/gpu-check:local)" == amd64 ]] &&
  pass "the GPU-check image is amd64" || fail "the GPU-check image is amd64"
if in_image test -L /etc/systemd/system/multi-user.target.wants/idleo-images.service &&
  in_image test -x /usr/local/libexec/idleo-import-images; then
  pass "idleo-images.service imports it at boot"
else
  fail "idleo-images.service imports it at boot"
fi

# The size the installer will download.
bytes="$(wc -c <"$tarball" | tr -d ' ')"
if ((bytes < 400 * 1000 * 1000)); then
  pass "the compressed tarball is $((bytes / 1000000)) MB, under 400 MB"
else
  fail "the compressed tarball is under 400 MB" "it is $((bytes / 1000000)) MB"
fi
(cd "$out" && shasum -a 256 -c "idleo-distro-$version.tar.gz.sha256" >/dev/null) && pass "its SHA-256 file matches" || fail "its SHA-256 file matches"

# Reproducible, as far as the package list goes: byte-for-byte reproducibility isn't asked for yet (agent/distro/README.md).
if $quick; then
  echo "SKIP  a second build from scratch (--quick)"
else
  second="$(mktemp -d)"
  trap 'rm -rf "$second"' EXIT
  "$root/scripts/build-distro.sh" --no-cache --out "$second" >/dev/null
  if diff "$out/idleo-distro-$version.packages.txt" "$second/idleo-distro-$version.packages.txt" >/dev/null; then
    pass "two builds from the same inputs have the same package list ($(wc -l <"$second/idleo-distro-$version.packages.txt" | tr -d ' ') packages)"
  else
    fail "two builds from the same inputs have the same package list" "$(diff "$out/idleo-distro-$version.packages.txt" "$second/idleo-distro-$version.packages.txt" | head -6)"
  fi
fi

if ((failed > 0)); then
  echo "distro test: $failed check(s) failed" >&2
  exit 1
fi
echo "distro test: all checks passed"
