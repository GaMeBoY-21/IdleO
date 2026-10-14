#!/usr/bin/env bash
# The control plane's checks: a clean build, so a warning in a file edited earlier can't hide behind an incremental
# compile (.github/workflows/control-plane.yml runs this; so does scripts/check-all.sh).
set -euo pipefail
cd "$(dirname "$0")/../../control-plane"
./mvnw -B clean verify
