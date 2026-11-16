#!/usr/bin/env bash
# The control plane's checks: its migrations held to expand and contract (migrations.sh), and a clean build, so a
# warning in a file edited earlier can't hide behind an incremental compile (.github/workflows/control-plane.yml runs
# this; so does scripts/check-all.sh).
set -euo pipefail
. "$(dirname "$0")/../lib/quiet.sh"
quiet_entry control-plane "$@"
set -- ${QUIET_ARGS[@]+"${QUIET_ARGS[@]}"}
# Expand and contract first: a migration both releases can't run with fails in a second, not after the build.
"$(dirname "$0")/migrations.sh"
cd "$(dirname "$0")/../../control-plane"
# --fast (scripts/check-changed.sh): a clean compile of the sources and tests (so a warning can't hide), the formatting, and
# the tests of the classes that changed (a changed class's own tests, by name, and a changed test); no full verify.
if [[ -n "${IDLEO_FAST:-}" ]]; then
  tests="$(printf '%s\n' "${IDLEO_CHANGED_FILES:-}" | python3 -c '
import glob, os, sys
names = set()
for line in sys.stdin:
    path = line.strip()
    if not path.startswith("control-plane/src/") or not path.endswith(".java"):
        continue
    stem = os.path.basename(path)[:-5]
    if "/src/test/" in path:
        names.add(stem)
    else:
        for test in glob.glob("src/test/**/*.java", recursive=True):
            base = os.path.basename(test)[:-5]
            if base.startswith(stem) and base.endswith(("Test", "Tests")):
                names.add(base)
print(",".join(sorted(names)))
')"
  if [[ -n "$tests" ]]; then
    ./mvnw -B -q clean spotless:check test -Dtest="$tests" -Dsurefire.failIfNoSpecifiedTests=false
  else
    ./mvnw -B -q clean spotless:check test-compile
  fi
  exit 0
fi
./mvnw -B clean verify
