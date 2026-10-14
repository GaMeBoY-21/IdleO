#!/usr/bin/env bash
# The agent protocol's checks: lint, format and build; the breaking-change check against main when there is one
# (.github/workflows/proto.yml runs this; so does scripts/check-all.sh).
set -euo pipefail
cd "$(dirname "$0")/../../proto"
buf lint
test -z "$(buf format -d)" || { buf format -d; echo "proto: buf format would change files (buf format -w)"; exit 1; }
buf build
if git rev-parse --verify -q main >/dev/null && [[ "$(git rev-parse HEAD)" != "$(git rev-parse main)" ]]; then
  buf breaking --against '../.git#branch=main,subdir=proto'
fi
