#!/usr/bin/env bash
# The job environment image's checks (agent/images/pytorch/test.sh, ADR 0035): the image that ships, built for linux/amd64
# (under emulation on a Mac: slow), imports and runs as it should, and the development variant for this machine runs the tiny
# CPU fine-tune and a Run-your-code project, stopped by SIGTERM, resumed and finished. It downloads a few GB, so there is no
# CI job for it yet; scripts/check-all.sh runs it unless given --no-images.
set -euo pipefail
cd "$(dirname "$0")/../.."
agent/images/pytorch/test.sh --dev
exec agent/images/pytorch/test.sh
