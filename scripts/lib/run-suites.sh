#!/usr/bin/env bash
# Runs the suites it is given, quietly, and prints the summary: the part scripts/check-all.sh and
# scripts/check-changed.sh share. Not for running by hand.
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"
# shellcheck source=scripts/lib/suites.sh
. "$root/scripts/lib/suites.sh"
# shellcheck source=scripts/lib/quiet.sh
. "$root/scripts/lib/quiet.sh"
. "$root/scripts/lib/toolchains.sh"

quiet_log_dir
failed=0
passed=()
broken=()
results=()
started_all=$SECONDS
for suite in "$@"; do
  status=0
  quiet_run "$suite" suite_command "$root" "$suite" || status=$?
  if ((status == 0)); then
    passed+=("$suite")
    results+=("$(printf '%-14s PASS' "$suite")")
  else
    failed=1
    broken+=("$suite")
    results+=("$(printf '%-14s FAIL (exit %d)' "$suite" "$status")")
  fi
done

printf 'summary: %d passed, %d failed in %s (commit %s)\n' "${#passed[@]}" "${#broken[@]}" \
  "$(quiet_duration $((SECONDS - started_all)))" "$(git rev-parse --short HEAD)"
((${#broken[@]})) && printf 'failed: %s\n' "${broken[*]}"
[[ "${IDLEO_VERBOSE:-}" == 1 ]] || printf 'logs: %s\n' "$IDLEO_LOG_DIR"
if [[ -n "$(git status --porcelain)" ]]; then
  printf 'note: the working tree has uncommitted changes\n'
fi
# Last, so nothing above can push a failure out of sight: a report quotes these lines.
printf '%s\n' "${results[@]}"
exit "$failed"
