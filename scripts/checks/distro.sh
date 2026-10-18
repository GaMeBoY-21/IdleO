#!/usr/bin/env bash
# The sealed Linux image's checks (agent/distro/test.sh): it builds for linux/amd64, is locked down, has what it should at
# the pinned versions, is under 400 MB and builds the same twice. It downloads a few hundred MB, so there is no CI job for
# it yet; scripts/check-all.sh runs it unless given --no-distro.
set -euo pipefail
cd "$(dirname "$0")/../.."
exec agent/distro/test.sh
