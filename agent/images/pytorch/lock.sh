#!/usr/bin/env bash
# Resolves requirements.in into requirements.lock: every package pinned, with the hashes of every wheel that can be installed
# on the image's platform (Linux x86-64, glibc 2.28 or newer, Python 3.12), so the image can install with --require-hashes.
# PyTorch comes from its own wheel index for CUDA 12.8 (cu128), the first CUDA with Blackwell (RTX 50) support (PyPI's
# newest torch is built for CUDA 13, which needs a newer driver than we ask for; the cu128 index stops at 2.11.0, so that is pinned), and the rest from PyPI: torch alone
# has the "+cu128" version, so the pin picks it from that index and everything else comes from PyPI. Run it to move a version forward, then agent/images/pytorch/test.sh.
set -euo pipefail
cd "$(dirname "$0")"
uv pip compile requirements.in --generate-hashes --python-version 3.12 --python-platform x86_64-manylinux_2_28 \
  --default-index https://pypi.org/simple --index https://download.pytorch.org/whl/cu128 --index-strategy unsafe-best-match --no-header --output-file requirements.lock
echo "lock.sh: requirements.lock written ($(grep -c '^[a-z0-9]' requirements.lock) packages)"

# The development variant (scripts/images/publish.sh --dev, ./start.sh --real-runner): the same libraries with PyTorch's CPU
# wheel, for linux/arm64, which is what a Mac's development distribution runs (agent/distro/README.md). Never shipped.
uv pip compile requirements.dev.in --generate-hashes --python-version 3.12 --python-platform aarch64-manylinux_2_28 \
  --default-index https://pypi.org/simple --index https://download.pytorch.org/whl/cpu --index-strategy unsafe-best-match --no-header --output-file requirements.dev.lock
echo "lock.sh: requirements.dev.lock written ($(grep -c '^[a-z0-9]' requirements.dev.lock) packages)"
