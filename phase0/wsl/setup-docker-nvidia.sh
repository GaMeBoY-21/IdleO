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

DISTRO="gpu-host-dev"
CUDA_IMAGE="nvidia/cuda:12.8.1-base-ubuntu24.04@sha256:133c78a0575303be34164d0b90137a042172bdf60696af01a3c424ab402d86e2"

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
[[ -e /usr/lib/wsl/lib/libcuda.so.1 ]] \
  || die "/usr/lib/wsl/lib/libcuda.so.1 is missing. Install or update the NVIDIA driver on Windows (not in Linux), then run 'wsl --terminate $DISTRO' and try again."
linux_driver=$(dpkg-query -W -f='${db:Status-Abbrev} ${Package}\n' 2>/dev/null \
  | awk '$1 ~ /^.i/ {print $2}' \
  | grep -E '^(nvidia-driver-|nvidia-dkms-|nvidia-kernel-|nvidia-utils-|libnvidia-compute-|libnvidia-gl-|cuda-drivers)' || true)
if [[ -n "$linux_driver" ]]; then
  die "a Linux NVIDIA driver is installed inside WSL ($(echo "$linux_driver" | tr '\n' ' ')). It breaks GPU access. Remove it with 'apt-get purge' and use only the Windows driver."
fi
stray=$(ldconfig -p | awk '/libcuda\.so/ {print $NF}' | grep -v '^/usr/lib/wsl/' || true)
[[ -z "$stray" ]] || die "found libcuda outside /usr/lib/wsl/lib: $stray. A Linux driver install is shadowing the Windows one; remove it."
/usr/lib/wsl/lib/nvidia-smi -L >/dev/null || die "nvidia-smi from the Windows driver does not run. Update the NVIDIA driver on Windows."
ok "$(/usr/lib/wsl/lib/nvidia-smi -L | head -1)"

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
if ! docker run --rm --gpus all "$CUDA_IMAGE" nvidia-smi; then
  die "the container could not see the GPU. Check the Windows driver version and 'docker info | grep -i runtime'."
fi
echo
echo "Docker Engine and the NVIDIA Container Toolkit work in $DISTRO."
echo "Next: phase0/README.md, the QLoRA run and resume test."
