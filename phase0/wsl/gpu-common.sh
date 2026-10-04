# shellcheck shell=bash
# Shared by setup-docker-nvidia.sh and verify-lockdown.sh. Source it; don't run it.

GPU_DISTRO="gpu-host-dev"
WSL_GPU_LIB="/usr/lib/wsl/lib"
CUDA_IMAGE="nvidia/cuda:12.8.1-base-ubuntu24.04@sha256:133c78a0575303be34164d0b90137a042172bdf60696af01a3c424ab402d86e2"

# check_wsl_gpu_driver: the GPU driver must come only from Windows, through
# /usr/lib/wsl/lib. A Linux NVIDIA driver installed inside WSL breaks GPU
# access. On success prints the first GPU from nvidia-smi -L and returns 0;
# on failure prints the reason and returns 1.
check_wsl_gpu_driver() {
  local linux_driver stray gpus
  if [[ ! -e "$WSL_GPU_LIB/libcuda.so.1" ]]; then
    echo "$WSL_GPU_LIB/libcuda.so.1 is missing. Install or update the NVIDIA driver on Windows (not in Linux), then run 'wsl --terminate $GPU_DISTRO' and try again."
    return 1
  fi
  linux_driver=$(dpkg-query -W -f='${db:Status-Abbrev} ${Package}\n' 2>/dev/null \
    | awk '$1 ~ /^.i/ {print $2}' \
    | grep -E '^(nvidia-driver-|nvidia-dkms-|nvidia-kernel-|nvidia-utils-|libnvidia-compute-|libnvidia-gl-|cuda-drivers)' || true)
  if [[ -n "$linux_driver" ]]; then
    echo "a Linux NVIDIA driver is installed inside WSL ($(echo "$linux_driver" | tr '\n' ' ')). It breaks GPU access. Remove it with 'apt-get purge' and use only the Windows driver."
    return 1
  fi
  stray=$(ldconfig -p 2>/dev/null | awk '/libcuda\.so/ {print $NF}' | grep -v "^$WSL_GPU_LIB/" || true)
  if [[ -n "$stray" ]]; then
    echo "found libcuda outside $WSL_GPU_LIB: $stray. A Linux driver install is shadowing the Windows one; remove it."
    return 1
  fi
  if ! gpus=$("$WSL_GPU_LIB/nvidia-smi" -L 2>&1) || [[ "$gpus" != *GPU* ]]; then
    echo "nvidia-smi from the Windows driver does not list a GPU: ${gpus:-no output}. Update the NVIDIA driver on Windows."
    return 1
  fi
  echo "${gpus%%$'\n'*}"
}

# cuda_container_nvidia_smi [args]: run nvidia-smi in the pinned CUDA image
# with every GPU passed through.
cuda_container_nvidia_smi() {
  docker run --rm --gpus all "$CUDA_IMAGE" nvidia-smi "$@"
}
