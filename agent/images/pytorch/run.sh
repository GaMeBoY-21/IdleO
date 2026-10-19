#!/bin/bash
# Run your code (the run-code service, docs/site "Writing a job for IdleO"): your folder is staged at SESSION_INPUT_CODE_DIR,
# read-only. It's copied to /work/code, where it can write; if it has a requirements.txt, that's installed into
# /work/.venv first (the network allows HTTPS); then your command runs there, python main.py unless the Command parameter says
# otherwise. The job contract applies to it: SESSION_OUTPUT_DIR, SESSION_CHECKPOINT_DIR, SESSION_RESUME_DIR, SIGTERM.
#
# This is the container's first process, so it passes SIGTERM on to your command: a process that's PID 1 in its own container
# otherwise ignores it.
# shellcheck source=/dev/null
. /opt/idleo/failure.sh

code="${SESSION_INPUT_CODE_DIR:-}"
[ -n "$code" ] && [ -d "$code" ] || idleo_invalid_input "No code folder was given, so there is nothing to run."
command="${SESSION_PARAM_COMMAND:-python main.py}"

mkdir -p /work/code "${SESSION_OUTPUT_DIR:-/work/outputs}" "${SESSION_CHECKPOINT_DIR:-/work/checkpoints}"
cp -R --no-preserve=mode,ownership "$code"/. /work/code/ || idleo_invalid_input "Your folder couldn't be copied."
cd /work/code || exit 1

if [ -f requirements.txt ]; then
  echo "idleo: installing requirements.txt"
  # Your environment can see everything the image carries, and whatever you install wins.
  python3.12 -m venv /work/.venv || exit 1
  printf '%s\n' /opt/idleo/venv/lib/python3.12/site-packages >/work/.venv/lib/python3.12/site-packages/idleo-image.pth
  if ! /work/.venv/bin/pip install --no-input -r requirements.txt; then
    idleo_invalid_input "requirements.txt couldn't be installed; the lines above say which package."
  fi
  export PATH="/work/.venv/bin:$PATH"
fi

echo "\$ $command"
bash -c "$command" &
child=$!
stopping=false
trap 'stopping=true; kill -TERM "$child" 2>/dev/null' TERM INT
status=0
while true; do
  wait "$child"
  status=$?
  # A trapped signal ends `wait` early with status above 128 while the command is still running; wait for it to really end.
  if kill -0 "$child" 2>/dev/null; then continue; fi
  break
done
exit "$status"
