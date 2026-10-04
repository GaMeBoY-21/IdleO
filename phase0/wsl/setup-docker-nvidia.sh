#!/usr/bin/env bash
# Phase 0: install Docker Engine (not Docker Desktop) and the NVIDIA Container
# Toolkit inside the gpu-host-dev WSL2 distribution, then prove a container can
# see the GPU. Safe to run again.
#
# From Windows:  wsl -d gpu-host-dev -u root -- bash /mnt/c/.../setup-docker-nvidia.sh
#
# Never installs a Linux NVIDIA driver: in WSL the driver comes from Windows
# and appears under /usr/lib/wsl/lib. A driver installed inside breaks GPU
# access, so this script refuses to run if it finds one.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source-path=SCRIPTDIR source=gpu-common.sh
. "$HERE/gpu-common.sh"
DISTRO="$GPU_DISTRO"

step() { printf '==> %s\n' "$*"; }
ok() { printf '    ok: %s\n' "$*"; }
die() { printf '    FAILED: %s\n' "$*" >&2; exit 1; }

# --- Where are we? ------------------------------------------------------------
step "Checking where this runs"
grep -qi microsoft /proc/version 2>/dev/null || die "this is not WSL. Run it inside the $DISTRO distribution."
[[ "${WSL_DISTRO_NAME:-}" == "$DISTRO" ]] \
  || die "this is the '${WSL_DISTRO_NAME:-unknown}' distribution. Run it only inside $DISTRO, so no other distribution is changed."
if [[ $EUID -ne 0 ]]; then
  command -v sudo >/dev/null || die "run as root: wsl -d $DISTRO -u root -- bash $0"
  exec sudo -E bash "$0" "$@"
fi
# shellcheck source=/dev/null
. /etc/os-release
[[ "${ID:-}" == ubuntu ]] || die "expected Ubuntu, found ${PRETTY_NAME:-unknown}."
ok "$PRETTY_NAME in $DISTRO, as root"

# --- The GPU must come from the Windows driver --------------------------------
step "Checking the GPU driver comes from Windows"
gpu=$(check_wsl_gpu_driver) || die "$gpu"
ok "$gpu"

# --- systemd -------------------------------------------------------------------
step "Checking systemd"
if ! grep -Eq '^[[:space:]]*systemd[[:space:]]*=[[:space:]]*true' /etc/wsl.conf 2>/dev/null; then
  if [[ -f /etc/wsl.conf ]] && grep -Eq '^[[:space:]]*\[boot\]' /etc/wsl.conf; then
    sed -i -E '/^[[:space:]]*\[boot\]/a systemd = true' /etc/wsl.conf
  else
    printf '\n[boot]\nsystemd = true\n' >> /etc/wsl.conf
  fi
  ok "turned on systemd in /etc/wsl.conf"
fi
if [[ "$(ps -p 1 -o comm=)" != systemd ]]; then
  echo
  echo "systemd is configured but not running yet. From Windows, run:"
  echo "    wsl --terminate $DISTRO"
  echo "then run this script again."
  exit 2
fi
ok "systemd is running"

# --- Docker Engine from Docker's apt repository --------------------------------
step "Installing Docker Engine"
docker_path=$(command -v docker || true)
if [[ "$docker_path" == /mnt/* ]]; then
  die "'docker' resolves to $docker_path, which is Docker Desktop's WSL integration. Turn off WSL integration for $DISTRO in Docker Desktop's settings."
fi
export DEBIAN_FRONTEND=noninteractive
for pkg in docker.io docker-doc docker-compose docker-compose-v2 podman-docker containerd runc; do
  if dpkg-query -W -f='${db:Status-Abbrev}' "$pkg" 2>/dev/null | grep -q '^.i'; then
    echo "    removing conflicting package $pkg"
    apt-get remove -y "$pkg" >/dev/null
  fi
done
apt-get update -qq
apt-get install -y -qq ca-certificates curl gnupg >/dev/null
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc \
  || die "could not download Docker's signing key. Check the internet connection."
chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu ${UBUNTU_CODENAME:-$VERSION_CODENAME} stable" \
  > /etc/apt/sources.list.d/docker.list
apt-get update -qq
apt-get install -y -qq docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin >/dev/null \
  || die "apt could not install Docker Engine."
systemctl enable --now docker >/dev/null 2>&1 || die "could not start the docker service. See: journalctl -u docker"
ok "$(docker --version)"

# --- NVIDIA Container Toolkit from NVIDIA's apt repository ----------------------
step "Installing the NVIDIA Container Toolkit"
curl -fsSL https://nvidia.github.io/libnvidia-container/gpgkey \
  | gpg --batch --yes --dearmor -o /usr/share/keyrings/nvidia-container-toolkit-keyring.gpg \
  || die "could not download NVIDIA's signing key."
curl -fsSL https://nvidia.github.io/libnvidia-container/stable/deb/nvidia-container-toolkit.list \
  | sed 's#deb https://#deb [signed-by=/usr/share/keyrings/nvidia-container-toolkit-keyring.gpg] https://#g' \
  > /etc/apt/sources.list.d/nvidia-container-toolkit.list \
  || die "could not download NVIDIA's repository list."
apt-get update -qq
apt-get install -y -qq nvidia-container-toolkit >/dev/null || die "apt could not install nvidia-container-toolkit."
nvidia-ctk runtime configure --runtime=docker >/dev/null || die "nvidia-ctk could not configure Docker."
systemctl restart docker || die "docker did not restart after configuring the NVIDIA runtime."
ok "$(nvidia-ctk --version | head -1)"

# --- Prove it -------------------------------------------------------------------
step "Running nvidia-smi in a CUDA container"
# shellcheck disable=SC2119  # no arguments on purpose: the full nvidia-smi table
if ! cuda_container_nvidia_smi; then
  die "the container could not see the GPU. Check the Windows driver version and 'docker info | grep -i runtime'."
fi
echo
echo "Docker Engine and the NVIDIA Container Toolkit work in $DISTRO."
echo "Next: phase0/README.md, the QLoRA run and resume test."
