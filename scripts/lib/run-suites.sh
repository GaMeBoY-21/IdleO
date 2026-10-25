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
started_all=$SECONDS
for suite in "$@"; do
  if quiet_run "$suite" suite_command "$root" "$suite"; then passed+=("$suite"); else failed=1; broken+=("$suite"); fi
done

printf 'summary: %d passed, %d failed in %s (commit %s)\n' "${#passed[@]}" "${#broken[@]}" \
  "$(quiet_duration $((SECONDS - started_all)))" "$(git rev-parse --short HEAD)"
((${#broken[@]})) && printf 'failed: %s\n' "${broken[*]}"
[[ "${IDLEO_VERBOSE:-}" == 1 ]] || printf 'logs: %s\n' "$IDLEO_LOG_DIR"
if [[ -n "$(git status --porcelain)" ]]; then
  printf 'note: the working tree has uncommitted changes\n'
fi
exit "$failed"
