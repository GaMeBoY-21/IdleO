#!/usr/bin/env bash
# Prove the wsl.conf lockdown took effect, in gpu-host-dev (Phase 0) or in the sealed image (agent/distro/).
# One file, run from either place; wsl.conf is agent/distro/wsl.conf.
#
# Run as root after copying agent/distro/wsl.conf to /etc/wsl.conf (the sealed image already has it), running
# 'wsl --terminate gpu-host-dev' from Windows, and starting it again:
#
#   wsl -d gpu-host-dev -u root
#   bash /root/agent/distro/verify-lockdown.sh   (in the sealed image: /usr/local/share/idleo/verify-lockdown.sh)
#
# Run it directly as root, not through sudo: sudo resets PATH, which would
# hide an appended Windows PATH. Each check prints PASS or FAIL with its
# evidence. The script exits 1 if any check fails.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# gpu-common.sh sits beside this script in the sealed image (/usr/local/share/idleo), and in phase0/wsl in the repository.
if [[ -f "$HERE/gpu-common.sh" ]]; then
  # shellcheck source-path=SCRIPTDIR source=gpu-common.sh
  . "$HERE/gpu-common.sh"
else
  # shellcheck source=../../phase0/wsl/gpu-common.sh
  . "$HERE/../../phase0/wsl/gpu-common.sh"
fi

FAILED=0
pass() { printf 'PASS  %s\n' "$1"; shift; evidence "$@"; }
fail() { printf 'FAIL  %s\n' "$1"; shift; evidence "$@"; FAILED=$((FAILED + 1)); }
evidence() { local line; for line in "$@"; do printf '      %s\n' "$line"; done; }
indent() { sed 's/^/      /'; }

kernel=$(cat /proc/version 2>/dev/null || echo "unreadable")
IN_WSL=0
[[ "$kernel" == *[Mm]icrosoft* ]] && IN_WSL=1
NOT_WSL="not a WSL kernel, so this can't be proven here: /proc/version is '${kernel:0:80}'"

echo "verify-lockdown: $(date -u +%Y-%m-%dT%H:%M:%SZ)"
echo

# --- 0. Where are we? -----------------------------------------------------------
if [[ $IN_WSL == 1 && "${WSL_DISTRO_NAME:-}" == "$GPU_DISTRO" ]]; then
  pass "running inside $GPU_DISTRO" "WSL_DISTRO_NAME=$WSL_DISTRO_NAME" "${kernel:0:80}"
elif [[ $IN_WSL == 1 ]]; then
  fail "running inside $GPU_DISTRO" "WSL_DISTRO_NAME is '${WSL_DISTRO_NAME:-unset}'. Start it with: wsl -d $GPU_DISTRO -u root"
else
  fail "running inside $GPU_DISTRO" "$NOT_WSL"
fi

# --- 1. No Windows drives -------------------------------------------------------
if [[ $IN_WSL == 0 ]]; then
  fail "no Windows drives mounted" "$NOT_WSL"
else
  drive_mounts=$(findmnt -rn -o TARGET,FSTYPE 2>/dev/null | awk '$1 ~ /^\/mnt\/[a-z]$/' || true)
  nonempty=""
  for d in /mnt/?; do
    [[ -d "$d" && -n "$(ls -A "$d" 2>/dev/null)" ]] && nonempty+="$d "
  done
  # The GPU driver store is a read-only 9p mount under /usr/lib/wsl, which WSL
  # needs for the GPU. Any other 9p or drvfs mount is a Windows folder.
  windows_mounts=$(findmnt -rn -t 9p,drvfs -o TARGET,FSTYPE,OPTIONS 2>/dev/null || true)
  allowed=$(awk '$1 ~ /^\/usr\/lib\/wsl\// && $3 ~ /(^|,)ro(,|$)/' <<<"$windows_mounts")
  other=$(awk '!($1 ~ /^\/usr\/lib\/wsl\// && $3 ~ /(^|,)ro(,|$)/)' <<<"$windows_mounts" | sed '/^$/d')
  ev=()
  [[ -n "$drive_mounts" ]] && ev+=("drive mounts under /mnt: $(tr '\n' ' ' <<<"$drive_mounts")")
  [[ -n "$nonempty" ]] && ev+=("non-empty drive folders: $nonempty")
  [[ -n "$other" ]] && ev+=("9p/drvfs mounts: $(tr '\n' ';' <<<"$other")")
  [[ -n "$allowed" ]] && ev+=("allowed, read-only GPU driver store: $(awk '{print $1}' <<<"$allowed" | tr '\n' ' ')")
  if [[ -z "$drive_mounts" && -z "$nonempty" && -z "$other" ]]; then
    pass "no Windows drives mounted" "nothing mounted at /mnt/<letter>; no 9p/drvfs mounts outside /usr/lib/wsl" ${ev[@]+"${ev[@]}"}
  else
    fail "no Windows drives mounted" "${ev[@]}"
  fi
fi

# --- 2. No interop ----------------------------------------------------------------
if [[ $IN_WSL == 0 ]]; then
  fail "Windows interop is off" "$NOT_WSL"
else
  ev=()
  interop_on=0
  shopt -s nullglob
  for f in /proc/sys/fs/binfmt_misc/WSLInterop*; do
    state=$(head -1 "$f" 2>/dev/null)
    ev+=("$f: ${state:-unreadable}")
    [[ "$state" == enabled ]] && interop_on=1
  done
  shopt -u nullglob
  [[ ${#ev[@]} -eq 0 ]] && ev+=("no WSLInterop entry in /proc/sys/fs/binfmt_misc")
  win_exe=/mnt/c/Windows/System32/cmd.exe
  if [[ -e "$win_exe" ]]; then
    if out=$(timeout 10 "$win_exe" /c ver 2>&1); then
      interop_on=1
      ev+=("$win_exe ran: $(tr -d '\r' <<<"$out" | sed '/^$/d' | head -1)")
    else
      ev+=("$win_exe exists but could not run: ${out:0:80}")
    fi
  else
    ev+=("$win_exe does not exist, so it can't be run")
  fi
  if [[ $interop_on == 0 ]]; then
    pass "Windows interop is off" "${ev[@]}"
  else
    fail "Windows interop is off" "${ev[@]}"
  fi
fi

# --- 3. Windows PATH not appended ---------------------------------------------------
if [[ $IN_WSL == 0 ]]; then
  fail "Windows PATH not appended" "$NOT_WSL"
else
  win_path=$(tr ':' '\n' <<<"$PATH" | grep -E '^/mnt/[a-z](/|$)' || true)
  if [[ -z "$win_path" ]]; then
    pass "Windows PATH not appended" "no /mnt/<letter> entries in PATH=$PATH"
  else
    fail "Windows PATH not appended" "PATH has Windows entries: $(tr '\n' ' ' <<<"$win_path")"
  fi
fi

# --- 4. systemd -----------------------------------------------------------------------
pid1=$(cat /proc/1/comm 2>/dev/null || echo unknown)
if [[ "$pid1" != systemd ]]; then
  fail "systemd is PID 1 and running" "PID 1 is '$pid1'"
elif ! command -v systemctl >/dev/null; then
  fail "systemd is PID 1 and running" "PID 1 is systemd, but systemctl is missing"
else
  state=$(timeout 60 systemctl is-system-running --wait 2>/dev/null || true)
  if [[ "$state" == running || "$state" == degraded ]]; then
    ev=("PID 1 is systemd" "systemctl is-system-running: $state")
    [[ "$state" == degraded ]] && ev+=("failed units: $(systemctl --failed --no-legend --plain 2>/dev/null | awk '{print $1}' | tr '\n' ' ')")
    pass "systemd is PID 1 and running" "${ev[@]}"
  else
    fail "systemd is PID 1 and running" "PID 1 is systemd" "systemctl is-system-running: ${state:-no answer within 60s}"
  fi
fi

# --- 5. The GPU still works -------------------------------------------------------------
if gpu=$(check_wsl_gpu_driver); then
  pass "GPU visible through the Windows driver" "$WSL_GPU_LIB/nvidia-smi -L: $gpu"
else
  fail "GPU visible through the Windows driver" "$gpu"
fi

if ! command -v docker >/dev/null; then
  fail "GPU works in the pinned CUDA container" "docker is not installed (run setup-docker-nvidia.sh)"
elif out=$(cuda_container_nvidia_smi -L 2>&1) && [[ "$out" == *GPU* ]]; then
  pass "GPU works in the pinned CUDA container" "image: $CUDA_IMAGE"
  indent <<<"$out"
else
  fail "GPU works in the pinned CUDA container" "image: $CUDA_IMAGE" "docker run --gpus all ... nvidia-smi -L failed:"
  indent <<<"${out:-no output}"
fi

echo
if [[ $FAILED -eq 0 ]]; then
  echo "verify-lockdown: all checks passed"
  exit 0
fi
echo "verify-lockdown: $FAILED check(s) FAILED. The sandbox is not locked down; do not run the QLoRA test yet."
exit 1
