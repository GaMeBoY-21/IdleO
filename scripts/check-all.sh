#!/usr/bin/env bash
# Every workflow's checks, on this machine, the same steps CI runs (scripts/checks/*.sh, and the e2e script): run
# once, before a push. While working, scripts/check-changed.sh runs only the suites a change touches.
#
#   scripts/check-all.sh                 every suite, in order
#   scripts/check-all.sh web e2e         just these
#   scripts/check-all.sh --verbose       print each suite's whole output as it runs (the old behaviour)
#   scripts/check-all.sh --no-distro     every suite but the sealed Linux image's (it downloads a few hundred MB)
#   scripts/check-all.sh --no-images     every suite but the job environment image's (it downloads a few GB, and builds under emulation)
#   scripts/check-all.sh --no-drill      every suite but the restore drill (ops/backup/drill.sh, on the Mac's cluster: about 2 minutes once it's up)
#
# Suites and the files each answers for: scripts/lib/suites.sh. Output is quiet: one line per suite (name, PASS or FAIL,
# time); the whole output is in private/logs/<date-time>/<suite>.log, and a FAIL also prints that log's last 40 lines
# and its path. Read a log only when its suite fails. It keeps going after a failure, so one run shows everything, and
# exits non-zero if anything failed.
set -euo pipefail

cd "$(dirname "$0")/.."
root="$(pwd)"
# shellcheck source=scripts/lib/suites.sh
. "$root/scripts/lib/suites.sh"
# shellcheck source=scripts/lib/quiet.sh
. "$root/scripts/lib/quiet.sh"
suites=()
skip_distro=false
skip_images=false
skip_drill=false
for arg in "$@"; do
  case "$arg" in
    --verbose) IDLEO_VERBOSE=1 ;;
    --no-distro) skip_distro=true ;;
    --no-drill) skip_drill=true ;;
    --no-images) skip_images=true ;;
    *) suites+=("$arg") ;;
  esac
done
export IDLEO_VERBOSE
if ((${#suites[@]} == 0)); then
  # Every suite, in order; the image's only when it isn't turned off (CI minutes, or a machine without Docker's room).
  for suite in "${ALL_SUITES[@]}"; do
    if [[ "$suite" == distro ]] && $skip_distro; then continue; fi
    if [[ "$suite" == images ]] && $skip_images; then continue; fi
    if [[ "$suite" == drill ]] && $skip_drill; then continue; fi
    suites+=("$suite")
  done
fi

# shellcheck source=scripts/lib/toolchains.sh
. "$root/scripts/lib/toolchains.sh"

exec "$root/scripts/lib/run-suites.sh" "${suites[@]}"
