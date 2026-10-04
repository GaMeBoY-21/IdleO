#!/usr/bin/env bash
# Kill-and-resume test: start a run, kill it hard once a checkpoint at or past
# the halfway step has been saved, start again with --resume, and check the
# step count continued instead of restarting.
#
#   ./resume-test.sh                 in the sandboxed container (GPU host)
#   ./resume-test.sh --smoke         in the container, tiny CPU run
#   ./resume-test.sh --local --smoke in phase0/qlora-test/.venv, no Docker
#
# Other arguments go to train.py. The test clears checkpoints, progress and
# results in the work folder; the model cache is kept.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK_DIR="${WORK_DIR:-$HERE/work}"
export WORK_DIR
CONTAINER_NAME="${CONTAINER_NAME:-phase0-qlora-resume}"
export CONTAINER_NAME
PYTHON="${PYTHON:-$HERE/.venv/bin/python}"
TIMEOUT_S="${TIMEOUT_S:-3600}"

LOCAL=0
ARGS=()
for arg in "$@"; do
  if [[ "$arg" == --local ]]; then LOCAL=1; else ARGS+=("$arg"); fi
done

die() { echo "resume-test: FAIL: $*" >&2; exit 1; }

json_field() {  # json_field FILE KEY
  "$JSON_PY" -c 'import json,sys; v=json.load(open(sys.argv[1])).get(sys.argv[2]); print("" if v is None else str(v).lower() if isinstance(v,bool) else v)' "$1" "$2"
}

RUN_PID=""
start_run() {  # start_run [extra train.py args...]
  if [[ $LOCAL == 1 ]]; then
    "$PYTHON" "$HERE/train.py" --work-dir "$WORK_DIR" ${ARGS[@]+"${ARGS[@]}"} "$@" &
  else
    "$HERE/run.sh" ${ARGS[@]+"${ARGS[@]}"} "$@" &
  fi
  RUN_PID=$!
}

kill_run() {
  if [[ $LOCAL == 1 ]]; then
    kill -9 "$RUN_PID" 2>/dev/null || true
  else
    docker kill "$CONTAINER_NAME" >/dev/null 2>&1 || true
  fi
  wait "$RUN_PID" 2>/dev/null || true
}

if [[ $LOCAL == 1 ]]; then
  [[ -x "$PYTHON" ]] || die "no venv at $PYTHON; see phase0/qlora-test/README.md"
  JSON_PY="$PYTHON"
else
  JSON_PY="$(command -v python3 || command -v python)" || die "python3 is needed to read the results"
  # Build once up front, so the timed runs below skip it.
  docker build -q -t "${IMAGE:-phase0-qlora:dev}" "$HERE" >/dev/null
  export SKIP_BUILD=1
fi

mkdir -p "$WORK_DIR"
rm -rf "$WORK_DIR/checkpoints" "$WORK_DIR/progress.json" "$WORK_DIR/results.json"

started=$(date +%s)
echo "== run 1: start, then kill after a checkpoint at or past halfway"
start_run
trap 'kill_run' EXIT
deadline=$(( $(date +%s) + TIMEOUT_S ))
while :; do
  kill -0 "$RUN_PID" 2>/dev/null || die "run 1 exited before it could be killed"
  if [[ -f "$WORK_DIR/progress.json" ]]; then
    max=$(json_field "$WORK_DIR/progress.json" max_steps)
    ckpt=$(json_field "$WORK_DIR/progress.json" last_checkpoint_step)
    if (( ckpt > 0 && ckpt * 2 >= max )); then break; fi
  fi
  (( $(date +%s) < deadline )) || die "no halfway checkpoint after ${TIMEOUT_S}s"
  sleep 0.2
done
kill_run
trap - EXIT
before_kill=$(json_field "$WORK_DIR/progress.json" step)
ckpt_at_kill=$(json_field "$WORK_DIR/progress.json" last_checkpoint_step)
[[ -f "$WORK_DIR/results.json" ]] && die "run 1 finished before the kill landed; raise --max-steps"
echo "killed at step $before_kill of $max (newest checkpoint: $ckpt_at_kill)"

echo "== run 2: --resume"
start_run --resume
wait "$RUN_PID" || die "run 2 exited with an error"
ended=$(date +%s)

R="$WORK_DIR/results.json"
[[ -f "$R" ]] || die "run 2 wrote no results.json"
resumed=$(json_field "$R" resumed)
from=$(json_field "$R" resumed_from_step)
first=$(json_field "$R" first_step_this_run)
final=$(json_field "$R" final_step)
planned=$(json_field "$R" max_steps)

echo
echo "steps before kill:  $before_kill"
echo "resumed from step:  $from (first step run after resume: $first)"
echo "final step:         $final of $planned planned"
echo "total wall time:    $(( ended - started ))s for both runs"

[[ "$resumed" == true ]] || die "run 2 did not resume"
(( from > 0 )) || die "run 2 started from step 0"
(( from == ckpt_at_kill )) || die "resumed from $from, but the newest checkpoint was $ckpt_at_kill"
(( first == from + 1 )) || die "first step after resume was $first, expected $(( from + 1 ))"
(( final == planned )) || die "final step $final, planned $planned"
echo "resume-test: PASS"
