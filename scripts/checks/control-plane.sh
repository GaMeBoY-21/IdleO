#!/usr/bin/env bash
# The control plane's checks: a clean build, so a warning in a file edited earlier can't hide behind an incremental
# compile (.github/workflows/control-plane.yml runs this; so does scripts/check-all.sh).
set -euo pipefail
. "$(dirname "$0")/../lib/quiet.sh"
quiet_entry control-plane "$@"
set -- ${QUIET_ARGS[@]+"${QUIET_ARGS[@]}"}
cd "$(dirname "$0")/../../control-plane"
./mvnw -B clean verify
