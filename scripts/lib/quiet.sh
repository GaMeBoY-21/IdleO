#!/usr/bin/env bash
# Quiet output for the check scripts: one line per suite, the full output in a log. Source it; don't run it.
#
#   quiet_entry NAME "$@"    at the top of a suite's script, after `set -euo pipefail`: unless the output is wanted
#                            whole, it runs the script again with its output in the log, prints one line (NAME, PASS
#                            or FAIL, how long) and exits with the script's status; on a FAIL it also prints the log's
#                            last 40 lines and its path. Afterwards QUIET_ARGS holds the arguments without --verbose.
#   quiet_run NAME CMD…      the same for a command (scripts/check-all.sh runs each suite through it).
#
# --verbose (or IDLEO_VERBOSE=1, or CI being set, where the whole log is wanted) prints everything as it happens, as
# these scripts always did. Logs go to
# private/logs/<date-time>/NAME.log (IDLEO_LOG_DIR, which scripts/check-all.sh sets once so a whole run's logs sit
# together). A script already inside quiet_run (IDLEO_QUIET_ACTIVE) just runs, so nothing is wrapped twice.

[[ -n "${CI:-}" ]] && IDLEO_VERBOSE=1
quiet_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

quiet_log_dir() {
  if [[ -z "${IDLEO_LOG_DIR:-}" ]]; then
    IDLEO_LOG_DIR="$quiet_root/private/logs/$(date +%Y%m%d-%H%M%S)"
    export IDLEO_LOG_DIR
  fi
  mkdir -p "$IDLEO_LOG_DIR"
}

quiet_duration() { printf '%dm%02ds' $(($1 / 60)) $(($1 % 60)); }

# quiet_run NAME CMD…: prints "NAME PASS|FAIL 1m23s"; returns the command's status.
quiet_run() {
  local name="$1" began=$SECONDS status=0 took
  shift
  if [[ "${IDLEO_VERBOSE:-}" == 1 ]]; then
    printf '== %s\n' "$name"
    IDLEO_QUIET_ACTIVE=1 "$@" || status=$?
  else
    quiet_log_dir
    local log="$IDLEO_LOG_DIR/$name.log"
    IDLEO_QUIET_ACTIVE=1 "$@" >"$log" 2>&1 || status=$?
  fi
  took=$((SECONDS - began))
  if ((status == 0)); then
    printf '%-14s PASS  %s\n' "$name" "$(quiet_duration "$took")"
  else
    printf '%-14s FAIL  %s\n' "$name" "$(quiet_duration "$took")"
    if [[ "${IDLEO_VERBOSE:-}" != 1 ]]; then
      tail -n 40 "$log"
      printf '  full log: %s\n' "$log"
    fi
  fi
  return "$status"
}

# quiet_entry NAME "$@"
QUIET_ARGS=()
quiet_entry() {
  local name="$1" arg
  shift
  QUIET_ARGS=()
  for arg in "$@"; do
    if [[ "$arg" == --verbose ]]; then IDLEO_VERBOSE=1; else QUIET_ARGS+=("$arg"); fi
  done
  export IDLEO_VERBOSE
  [[ -n "${IDLEO_QUIET_ACTIVE:-}" || "${IDLEO_VERBOSE:-}" == 1 ]] && return 0
  local status=0
  quiet_run "$name" "$0" ${QUIET_ARGS[@]+"${QUIET_ARGS[@]}"} || status=$?
  exit "$status"
}
