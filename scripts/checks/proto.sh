#!/usr/bin/env bash
# The agent protocol's checks: lint, format and build; the breaking-change check against main when there is one
# (.github/workflows/proto.yml runs this; so does scripts/check-all.sh).
set -euo pipefail
cd "$(dirname "$0")/../../proto"
buf lint
test -z "$(buf format -d)" || { buf format -d; echo "proto: buf format would change files (buf format -w)"; exit 1; }
buf build
# Against what's published (origin/main) when this checkout has one, else the local main: on main before a push,
# that's what the push would change.
base=main
git rev-parse --verify -q origin/main >/dev/null && base=origin/main
if git rev-parse --verify -q "$base" >/dev/null && [[ "$(git rev-parse HEAD)" != "$(git rev-parse "$base")" ]]; then
  buf breaking --against "../.git#ref=$base,subdir=proto"
fi
