#!/usr/bin/env bash
# Checks the job image (agent/images/pytorch/README.md): it builds; Python, PyTorch, transformers, peft, trl and bitsandbytes
# import; it runs as 10001 with a read-only root and no capabilities; there is no compiler; the tiny CPU fine-tune passes
# (stopped by SIGTERM after a checkpoint, resumed from it, finished, with the adapter and the log); "Run your code" does the
# same with a sample project; and what's wrong with the inputs is said in one line. No model is downloaded: the test makes a
# few-layer random Llama and its tokenizer itself (tests/make_tiny_model.py).
#
#   agent/images/pytorch/test.sh          the image that ships: linux/amd64, CUDA 12.8 (several GB, a few minutes)
#   agent/images/pytorch/test.sh --dev    the development variant a Mac's stand-in runs: linux/arm64, CPU PyTorch
#   agent/images/pytorch/test.sh --no-build   test the image already built (idleo-pytorch:test, or :dev with --dev)
set -euo pipefail

cd "$(dirname "$0")/../../.."
root="$(pwd)"
here=agent/images/pytorch
dev=false
build=true
for arg in "$@"; do
  case "$arg" in
    --dev) dev=true ;;
    --no-build) build=false ;;
    *) echo "test.sh: unknown option $arg" >&2; exit 2 ;;
  esac
done
failed=0
pass() { printf 'PASS  %s\n' "$1"; }
fail() { printf 'FAIL  %s\n' "$1" >&2; shift; for line in "$@"; do printf '      %s\n' "$line" >&2; done; failed=$((failed + 1)); }

if $dev; then
  image=idleo-pytorch:dev
  platform=linux/arm64
  build_args=(--build-arg BASE=ubuntu:24.04@sha256:68ee22004a9109b44973313b02ff7b1de250f1de0f5ecb628d73f579aaff33ac
    --build-arg LOCK=requirements.dev.lock --build-arg TORCH_INDEX=https://download.pytorch.org/whl/cpu)
else
  image=idleo-pytorch:test
  platform=linux/amd64
  build_args=()
fi
work="$root/private/image-test/$([[ $dev == true ]] && echo dev || echo test)"
rm -rf "$work"
mkdir -p "$work"
chmod 777 "$work"

if $build; then
  echo "pytorch image test: building $image for $platform"
  started=$SECONDS
  docker buildx build --platform "$platform" --provenance=false --load ${build_args[@]+"${build_args[@]}"} -t "$image" "$here" >"$work/build.log" 2>&1 \
    && pass "the image builds for $platform ($((SECONDS - started)) s)" || { fail "the image builds" "$(tail -5 "$work/build.log")"; exit 1; }
fi
printf 'size: %s\n' "$(docker image inspect --format '{{.Size}}' "$image" | awk '{printf "%.2f GB", $1/1e9}')"

# One container the way the runner starts one: not root, a read-only root, a tmpfs /tmp, no capabilities, /work writable.
job() {
  docker run --rm --platform "$platform" --user 10001:10001 --read-only --tmpfs /tmp:size=1g --cap-drop ALL \
    --security-opt no-new-privileges -v "$work:/work" -v "$root/$here/tests:/tests:ro" "$@"
}

imports="$(job "$image" python -c 'import sys, torch, transformers, peft, trl, bitsandbytes; print(sys.version_info[0], sys.version_info[1], torch.__version__, torch.version.cuda)' 2>&1 | tail -1)"
if [[ "$imports" == 3\ 12\ * ]]; then
  pass "python, torch, transformers, peft, trl and bitsandbytes import ($imports)"
else
  fail "the libraries import" "$imports"
fi
if ! $dev; then
  [[ "$imports" == *" 12.8" ]] && pass "PyTorch is built for CUDA 12.8" || fail "PyTorch is built for CUDA 12.8" "$imports"
fi

[[ "$(job "$image" id -u)" == 10001 ]] && pass "it runs as 10001" || fail "it runs as 10001"
if job "$image" sh -c 'touch /usr/x || touch /opt/x || touch /etc/x' >/dev/null 2>&1; then fail "the root filesystem is read-only"; else pass "the root filesystem is read-only"; fi
job "$image" sh -c 'touch /work/ok && touch /tmp/ok' >/dev/null 2>&1 && pass "/work and /tmp are writable" || fail "/work and /tmp are writable"
compilers="$(job "$image" sh -c 'for c in gcc cc g++ make cpp ld; do command -v "$c" || true; done' 2>&1)"
[[ -z "$compilers" ]] && pass "there is no compiler" || fail "there is no compiler" "$compilers"
job "$image" sh -c 'echo "$HOME $HF_HOME $PIP_CACHE_DIR"' | grep -q '^/work /work/.cache/huggingface /work/.cache/pip$' \
  && pass "HOME, the Hugging Face cache and pip's cache are under /work" || fail "the caches are under /work"

# The tiny fine-tune, on the CPU: no model is downloaded, the test makes its own.
job "$image" python /tests/make_tiny_model.py /work/tiny >"$work/tiny.log" 2>&1 && pass "a tiny model and its tokenizer are made without a download" \
  || { fail "a tiny model is made" "$(tail -3 "$work/tiny.log")"; exit 1; }
train_env=(-e SESSION_INPUT_MODEL_DIR=/work/tiny/model -e SESSION_INPUT_DATA_DIR=/work/tiny/data -e SESSION_PARAM_EPOCHS=3
  -e SESSION_OUTPUT_DIR=/work/outputs -e SESSION_CHECKPOINT_DIR=/work/checkpoints -e SESSION_CHECKPOINT_INTERVAL=2 -e SESSION_FAILURE_FILE=/work/failure.json -e IDLEO_TEST_STEP_DELAY=0.4)
name="idleo-image-test-$$"
trap 'docker rm -f "$name" >/dev/null 2>&1 || true' EXIT
docker run -d --name "$name" --platform "$platform" --user 10001:10001 --read-only --tmpfs /tmp:size=1g --cap-drop ALL \
  --security-opt no-new-privileges -v "$work:/work" "${train_env[@]}" "$image" /opt/idleo/finetune.py --test-cpu >/dev/null
for _ in $(seq 1 240); do
  [[ -f "$work/checkpoints/1/_DONE" ]] && break
  sleep 1
done
if [[ -f "$work/checkpoints/1/_DONE" ]]; then
  docker kill --signal TERM "$name" >/dev/null
  code="$(docker wait "$name")"
  docker logs "$name" >"$work/first.log" 2>&1
  [[ "$code" == 0 ]] && grep -q "asked to stop" "$work/first.log" && ls "$work/checkpoints"/*/_DONE >/dev/null 2>&1 \
    && pass "SIGTERM saves a checkpoint (a folder and its _DONE) and the job exits 0" \
    || fail "SIGTERM saves a checkpoint and exits" "exit code $code" "$(tail -5 "$work/first.log")"
  [[ ! -e "$work/outputs/adapter_model.safetensors" ]] && pass "a stopped job writes no adapter" || fail "a stopped job writes no adapter"
else
  docker logs "$name" 2>&1 | tail -8 >&2 || true
  fail "the job writes a checkpoint within 4 minutes"
fi
docker rm -f "$name" >/dev/null 2>&1 || true

last="$(ls -1 "$work/checkpoints" | sort -n | tail -1)"
if [[ -n "$last" ]]; then
  # A new attempt has a new scratch disk: its own folders, and the checkpoint to resume from.
  rm -rf "$work/resume" "$work/outputs" "$work/trainer" "$work/checkpoints-first"
  mv "$work/checkpoints" "$work/checkpoints-first"
  cp -R "$work/checkpoints-first/$last" "$work/resume"
  rm -f "$work/resume/_DONE"
  if job "${train_env[@]}" -e SESSION_RESUME_DIR=/work/resume "$image" /opt/idleo/finetune.py --test-cpu >"$work/second.log" 2>&1; then
    first_step="$(grep -m1 -oE '^epoch [0-9]+/[0-9]+ step [0-9]+' "$work/second.log" | grep -oE '[0-9]+$' || echo 0)"
    grep -q "resuming from" "$work/second.log" && [[ "$first_step" -gt 1 ]] \
      && pass "a resumed job carries on from its checkpoint (its first step is $first_step, not 1)" \
      || fail "a resumed job carries on from its checkpoint" "first step: $first_step" "$(tail -5 "$work/second.log")"
    [[ -s "$work/outputs/adapter_model.safetensors" && -s "$work/outputs/adapter_config.json" && -s "$work/outputs/training_log.jsonl" ]] \
      && pass "the finished job leaves adapter_model.safetensors, adapter_config.json and training_log.jsonl" \
      || fail "the finished job leaves its outputs" "$(ls "$work/outputs" 2>&1)"
    grep -qE '^epoch [0-9]+/3 step [0-9]+/[0-9]+ loss [0-9.]+ lr ' "$work/second.log" \
      && pass "its log lines are the ones the live screen reads (epoch, step, loss, learning rate)" \
      || fail "its log lines match the simulated job's" "$(head -12 "$work/second.log")"
  else
    fail "the resumed job finishes" "$(tail -8 "$work/second.log")"
  fi
fi

# What's wrong with the inputs is found at once, and said in one plain line.
mkdir -p "$work/empty-model"
job -e SESSION_INPUT_MODEL_DIR=/work/empty-model -e SESSION_INPUT_DATA_DIR=/work/tiny/data -e SESSION_FAILURE_FILE=/work/failure.json "$image" \
  /opt/idleo/finetune.py --test-cpu >"$work/bad.log" 2>&1 && code=0 || code=$?
if [[ "$code" == 2 ]] && grep -q "This folder has no config.json, so it isn't a Hugging Face model." "$work/bad.log" \
  && grep -q '"invalid_input"' "$work/failure.json"; then
  pass "a model folder without config.json fails at once with one plain line (exit 2, invalid_input)"
else
  fail "a bad model folder fails fast" "exit $code" "$(tail -3 "$work/bad.log")"
fi

# Run your code, with the sample project: copied, run, stopped by SIGTERM after a checkpoint, resumed.
rm -rf "$work/code-in" "$work/outputs" "$work/checkpoints" "$work/checkpoints-first" "$work/code" "$work/resume"
mkdir -p "$work/code-in" && cp -R "$root/$here/tests/project/." "$work/code-in/"
code_env=(-e SESSION_INPUT_CODE_DIR=/work/code-in -e SESSION_OUTPUT_DIR=/work/outputs -e SESSION_CHECKPOINT_DIR=/work/checkpoints
  -e SESSION_PARAM_STEPS=30 -e SESSION_PARAM_PAUSE=0.2 -e SESSION_PARAM_CHECKPOINT_EVERY=5 -e SESSION_FAILURE_FILE=/work/failure.json)
docker run -d --name "$name" --platform "$platform" --user 10001:10001 --read-only --tmpfs /tmp:size=1g --cap-drop ALL \
  --security-opt no-new-privileges -v "$work:/work" "${code_env[@]}" "$image" /opt/idleo/run.sh >/dev/null
for _ in $(seq 1 60); do [[ -f "$work/checkpoints/1/_DONE" ]] && break; sleep 1; done
docker kill --signal TERM "$name" >/dev/null
code="$(docker wait "$name")"
docker logs "$name" >"$work/run1.log" 2>&1
[[ "$code" == 0 ]] && grep -q "stopped after a checkpoint" "$work/run1.log" \
  && pass "Run your code passes SIGTERM to your command, which saves a checkpoint and exits" \
  || fail "Run your code passes SIGTERM on" "exit $code" "$(tail -4 "$work/run1.log")"
docker rm -f "$name" >/dev/null 2>&1 || true
last="$(ls -1 "$work/checkpoints" | sort -n | tail -1)"
# A new attempt has a new scratch disk: its own checkpoints folder, and the one to resume from.
mv "$work/checkpoints" "$work/checkpoints-first"
cp -R "$work/checkpoints-first/$last" "$work/resume"
rm -f "$work/resume/_DONE"
job "${code_env[@]}" -e SESSION_RESUME_DIR=/work/resume "$image" /opt/idleo/run.sh >"$work/run2.log" 2>&1 || true
python3 - "$work/outputs/result.json" <<'PY' && pass "Run your code resumes from the checkpoint and writes its outputs" || fail "Run your code resumes" "$(tail -4 "$work/run2.log")"
import json, sys
result = json.load(open(sys.argv[1]))
assert result["steps"] == 30 and result["resumed_from"] > 0, result
PY

if [[ "$failed" != 0 ]]; then
  echo "pytorch image test: $failed FAILED" >&2
  exit 1
fi
echo "pytorch image test: all passed"
