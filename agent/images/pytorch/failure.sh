# Sourced by the job scripts: how a job tells the renter, in one plain line, that what they gave it is wrong. The runner reads
# $SESSION_FAILURE_FILE when the job exits non-zero and, for the code invalid_input, ends the session with that line instead of
# a bare exit code.
idleo_invalid_input() {
  echo "idleo: $1" >&2
  printf '{"code":"invalid_input","message":"%s"}\n' "$(printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g' | tr -d '\n' | cut -c1-200)" \
    >"${SESSION_FAILURE_FILE:-/work/idleo-failure.json}" 2>/dev/null || true
  exit 2
}
