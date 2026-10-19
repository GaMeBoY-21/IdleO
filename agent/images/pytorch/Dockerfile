# The job environment (ADR 0035, agent/images/pytorch/README.md): what a renter's job runs in. NVIDIA's CUDA 12.8.1 runtime
# with cuDNN on Ubuntu 24.04, Python 3.12, PyTorch for CUDA 12.8 and the common AI libraries, all from requirements.lock
# (every package pinned, every wheel's hash checked), and our job scripts in /opt/idleo/. No compiler, no model.
#
# Always built for linux/amd64 (the laptops are x86-64): scripts/images/publish.sh builds, pushes and prints the digest the
# catalog pins. The base is pinned by digest (nvidia/cuda:12.8.1-cudnn-runtime-ubuntu24.04, the multi-architecture index,
# 2026-10-19) and Python by version.
#
# The development variant (`--build-arg BASE=ubuntu:24.04@... --build-arg LOCK=requirements.dev.lock --build-arg TORCH_INDEX=https://download.pytorch.org/whl/cpu`, linux/arm64, CPU-only
# PyTorch) is what a Mac's development distribution runs (scripts/images/publish.sh --dev); it is the same recipe and scripts.

ARG BASE=nvidia/cuda:12.8.1-cudnn-runtime-ubuntu24.04@sha256:ac55d124da4882b497f732d8dfd9a702d5447a5f29d08d56da6f64f0a1eb34bc
ARG PYTHON_VERSION=3.12.3-1ubuntu0.17
# Where the lock's torch comes from: it has PyTorch's own "+cu128" build, which PyPI doesn't carry (the hashes are checked either way).
ARG TORCH_INDEX=https://download.pytorch.org/whl/cu128

# The libraries are installed in a stage of their own, so nothing a build needs stays in the image.
FROM ${BASE} AS libraries
ARG PYTHON_VERSION
ARG LOCK=requirements.lock
ARG TORCH_INDEX
ENV DEBIAN_FRONTEND=noninteractive
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends ca-certificates "python3.12=${PYTHON_VERSION}" "python3.12-venv=${PYTHON_VERSION}"; \
    rm -rf /var/lib/apt/lists/*
COPY ${LOCK} /tmp/requirements.lock
# Wheels only (nothing is compiled here, and nothing could be: there's no compiler), and only exactly the bytes in the lock.
RUN python3.12 -m venv /opt/idleo/venv \
    && /opt/idleo/venv/bin/pip install --no-cache-dir --only-binary=:all: --require-hashes --extra-index-url "${TORCH_INDEX}" -r /tmp/requirements.lock

FROM ${BASE}
ARG PYTHON_VERSION
ENV DEBIAN_FRONTEND=noninteractive
# python3.12-venv is what lets "Run your code" make its own environment for a renter's requirements.txt.
RUN set -eux; \
    apt-get update; \
    apt-get install -y --no-install-recommends ca-certificates "python3.12=${PYTHON_VERSION}" "python3.12-venv=${PYTHON_VERSION}"; \
    rm -rf /var/lib/apt/lists/* /var/cache/apt/* /var/log/apt /var/log/dpkg.log /usr/share/doc /usr/share/man; \
    groupadd --gid 10001 job; \
    useradd --uid 10001 --gid 10001 --no-create-home --home-dir /work --shell /usr/sbin/nologin job
COPY --from=libraries /opt/idleo/venv /opt/idleo/venv
COPY finetune.py run.sh failure.sh /opt/idleo/
RUN chmod 0755 /opt/idleo/finetune.py /opt/idleo/run.sh

# Everything a job writes goes under /work, the one writable place (the root is read-only): its home, the Hugging Face and
# pip caches, PyTorch's and Triton's.
ENV HOME=/work \
    XDG_CACHE_HOME=/work/.cache \
    HF_HOME=/work/.cache/huggingface \
    PIP_CACHE_DIR=/work/.cache/pip \
    TORCH_HOME=/work/.cache/torch \
    TRITON_CACHE_DIR=/work/.cache/triton \
    PYTHONDONTWRITEBYTECODE=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    TOKENIZERS_PARALLELISM=false \
    PATH=/opt/idleo/venv/bin:/usr/local/nvidia/bin:/usr/local/cuda/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
USER 10001:10001
WORKDIR /work
