#!/usr/bin/env bash
# Build the QLoRA test image and run it with the renter sandbox flags from
# technical design lines 479-489. Extra arguments go to train.py, for example:
#   ./run.sh               full 4-bit QLoRA run on the GPU
#   ./run.sh --resume      continue from the newest checkpoint
#   ./run.sh --smoke       tiny CPU run, same code path
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
IMAGE="${IMAGE:-phase0-qlora:dev}"
WORK_DIR="${WORK_DIR:-$HERE/work}"
CONTAINER_NAME="${CONTAINER_NAME:-phase0-qlora}"
NETWORK="${NETWORK:-phase0-qlora}"
MEMORY="${MEMORY:-12g}"
CPUS="${CPUS:-6}"
PIDS="${PIDS:-512}"
GPUS="${GPUS:-device=0}"

die() { echo "run.sh: $*" >&2; exit 1; }

command -v docker >/dev/null || die "docker is not installed. Run phase0/wsl/setup-docker-nvidia.sh first."
docker info >/dev/null 2>&1 || die "cannot talk to the Docker daemon. Is it running, and are you root or in the docker group?"

if [[ "${SKIP_BUILD:-0}" != 1 ]]; then
  docker build -t "$IMAGE" "$HERE"
fi

# The container runs as uid 10001 and can write only to /work and /tmp. On
# Linux the bind-mounted folder must be owned by that uid; Docker Desktop on a
# Mac maps ownership itself.
mkdir -p "$WORK_DIR"
if [[ "$(uname -s)" == Linux && "$(stat -c %u "$WORK_DIR")" != 10001 ]]; then
  chown 10001:10001 "$WORK_DIR" 2>/dev/null \
    || die "$WORK_DIR must be owned by uid 10001. Run: sudo chown 10001:10001 '$WORK_DIR'"
fi

# Own bridge network from the session pool (technical design line 491). The
# DOCKER-USER firewall rules are not applied in Phase 0.
if ! docker network inspect "$NETWORK" >/dev/null 2>&1; then
  docker network create --driver bridge --subnet 172.30.0.0/24 "$NETWORK" >/dev/null
fi

GPU_FLAGS=(--gpus "$GPUS")
for arg in "$@"; do
  [[ "$arg" == --smoke ]] && GPU_FLAGS=()
done

exec docker run --rm \
  --name "$CONTAINER_NAME" \
  ${GPU_FLAGS[@]+"${GPU_FLAGS[@]}"} \
  --user 10001:10001 \
  --read-only --tmpfs /tmp:size=1g \
  --mount "type=bind,src=$WORK_DIR,dst=/work" \
  --cap-drop ALL --security-opt no-new-privileges \
  --pids-limit "$PIDS" --memory "$MEMORY" --cpus "$CPUS" \
  --network "$NETWORK" \
  "$IMAGE" "$@"
