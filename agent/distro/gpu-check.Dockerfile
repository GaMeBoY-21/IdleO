# The GPU-check image (agent/distro/README.md): the self-test's "tiny CUDA job" until the real runner brings a CUDA
# kernel. It holds nothing of its own: the NVIDIA container toolkit injects nvidia-smi and the driver's libraries when
# containerd runs it with the GPU, and it prints the GPU's name and memory. From the same pinned Ubuntu base as the
# distribution, so nvidia-smi finds the glibc it needs. Baked into the distribution at /opt/idleo/images/gpu-check.tar.

# ubuntu:24.04 (the multi-architecture index), 2026-09-30: the same digest as the distribution's Dockerfile.
FROM ubuntu:24.04@sha256:68ee22004a9109b44973313b02ff7b1de250f1de0f5ecb628d73f579aaff33ac
LABEL org.opencontainers.image.title="idleo-gpu-check"
ENTRYPOINT ["nvidia-smi", "--query-gpu=name,memory.total", "--format=csv,noheader"]
