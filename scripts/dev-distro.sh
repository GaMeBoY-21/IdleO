#!/usr/bin/env bash
# Runs our sealed Linux image (agent/distro/, ADR 0035) as a container on this Mac, as the stand-in for a laptop's WSL space:
# the same recipe, with systemd as PID 1 and inside it containerd, nerdctl, nftables, loop devices and idleo-runner, started by
# the same units as on the laptop. The agent reaches its runner through `docker exec -i <name> idleo-runner attach`
# (`idleo-agent run --container-runner docker:<name>`).
#
# It is the image built for linux/arm64 (`scripts/build-distro.sh --dev`), not amd64: Docker Desktop runs amd64 containers
# under Rosetta, where a container can't run a container (runc's seccomp filter and its read-only mounts need syscalls Rosetta
# doesn't translate, and systemd can't start a service), so the sandbox could not be exercised at all. The recipe, the runner
# and the rules are the same; the laptop day proves the amd64 build and the GPU. Jobs are CPU-only, and arm64 images.
#
# FOR TESTS AND LOCAL DEVELOPMENT ONLY. It is never shipped: the container is privileged (it must be, to run a container
# runtime, loop devices and firewall rules inside, as WSL does), and it carries the development marker
# (/etc/idleo/development), which lets a job run without a GPU and a registry be reached without TLS. A release
# distribution has neither.
#
#   scripts/dev-distro.sh up [--registry HOST:PORT]   start it (or leave it running), wait until the runner answers
#   scripts/dev-distro.sh down [--purge]              remove it; --purge also removes the image cache and the volumes
#   scripts/dev-distro.sh status                      is it up, and is the runner answering?
#   scripts/dev-distro.sh stop-distro                 stop the services inside, as the agent's idle shutdown stops the laptop's
#   scripts/dev-distro.sh start-distro                start them again, waiting until the runner answers (a cold start, for timing)
#   scripts/dev-distro.sh refresh-runner              rebuild just idleo-runner from the source here and put it in the running
#                                                     distribution, then restart it (a quick loop while changing it)
#   scripts/dev-distro.sh exec -- COMMAND...          run a command inside, as root
#
# The development registry (control-plane/compose.yaml's `registry`) is reached by its logical name, registry.idleo.test:5000,
# which the runner maps to the real address from the marker's `registry=` line; --registry says which (default
# host.docker.internal:5000, the registry compose publishes on this Mac). The marker also sends the runner's storage requests
# for `localhost` (the stack's address for object storage) to host.docker.internal, which is this Mac from inside.
set -euo pipefail

cd "$(dirname "$0")/.."
root="$(pwd)"
name="${IDLEO_DEV_DISTRO:-idleo-dev-distro}"
version="$(tr -d '[:space:]' <agent/distro/VERSION)"
image="idleo-distro-dev:$version"
registry="host.docker.internal:5000"
volumes=("$name-containerd:/var/lib/containerd" "$name-state:/var/lib/idleo" "$name-nerdctl:/var/lib/nerdctl" "$name-cni:/var/lib/cni")

die() { echo "dev-distro: $*" >&2; exit 1; }
inside() { docker exec "$name" "$@"; }
running() { [[ "$(docker inspect -f '{{.State.Running}}' "$name" 2>/dev/null || true)" == true ]]; }
# A hello over the real transport: a stale socket file isn't an answer.
answers() { inside bash -c 'printf "%s\n" "{\"v\":1,\"request\":{\"id\":1,\"req\":{\"type\":\"hello\"}}}" | timeout 4 /usr/local/bin/idleo-runner attach 2>/dev/null | head -1 | grep -q "\"hello\""' >/dev/null 2>&1; }

command -v docker >/dev/null || die "Docker isn't installed"
docker info >/dev/null 2>&1 || die "Docker isn't running"
cmd="${1:-}"
[[ -n "$cmd" ]] && shift

case "$cmd" in
  up)
    while (($# > 0)); do
      case "$1" in
        --registry) registry="$2"; shift ;;
        *) die "unknown option $1" ;;
      esac
      shift
    done
    docker image inspect "$image" >/dev/null 2>&1 || die "$image isn't built: run scripts/build-distro.sh --dev first"
    if ! running; then
      docker rm -f "$name" >/dev/null 2>&1 || true
      mounts=()
      for volume in "${volumes[@]}"; do mounts+=(-v "$volume"); done
      docker run -d --name "$name" --privileged -e container=docker --tmpfs /run --tmpfs /run/lock \
        --add-host host.docker.internal:host-gateway --stop-signal SIGRTMIN+3 \
        "${mounts[@]}" --entrypoint /lib/systemd/systemd "$image" >/dev/null
    fi
    # The development marker, with where the logical registry name points; only root in the distribution can write it.
    inside bash -c "mkdir -p /etc/idleo && printf 'registry=%s\nlocalhost=host.docker.internal\n' '$registry' >/etc/idleo/development"
    started=$SECONDS
    for _ in $(seq 1 180); do
      answers && break
      sleep 1
    done
    answers || { docker logs --tail 40 "$name" >&2 || true; die "the runner didn't come up in 3 minutes"; }
    echo "dev-distro: $name is up (runner answering after $((SECONDS - started)) s); agent: --container-runner docker:$name"
    ;;
  down)
    docker rm -f "$name" >/dev/null 2>&1 || true
    if [[ "${1:-}" == "--purge" ]]; then
      for volume in "${volumes[@]}"; do docker volume rm "${volume%%:*}" >/dev/null 2>&1 || true; done
    fi
    echo "dev-distro: $name removed"
    ;;
  status)
    running || { echo "dev-distro: $name is not running"; exit 1; }
    answers && echo "dev-distro: $name is up and its runner answers" || { echo "dev-distro: $name is up but its runner doesn't answer"; exit 1; }
    ;;
  stop-distro)
    running || die "$name isn't running"
    inside systemctl stop idleo-runner.service containerd.service
    answers && die "the runner is still answering"
    echo "dev-distro: the services inside $name are stopped"
    ;;
  start-distro)
    running || die "$name isn't running"
    started=$SECONDS
    inside systemctl start idleo-runner.service
    for _ in $(seq 1 600); do answers && break; sleep 0.2; done
    answers || die "the runner didn't come back"
    echo "dev-distro: the services inside $name are up after $((SECONDS - started)) s"
    ;;
  refresh-runner)
    running || die "$name isn't running"
    context="$(mktemp -d)"
    trap 'rm -rf "$context"' EXIT
    mkdir -p "$context/agent" "$context/proto"
    rsync -a --exclude target --exclude .git "$root/agent/" "$context/agent/"
    rsync -a --exclude .git "$root/proto/" "$context/proto/"
    cp agent/distro/Dockerfile "$context/Dockerfile"
    docker buildx build --progress=plain --target runner-binary --output "type=local,dest=$context/out" "$context" \
      >"$context/build.log" 2>&1 || { tail -40 "$context/build.log" >&2; die "the runner didn't build"; }
    docker cp "$context/out/idleo-runner" "$name:/usr/local/bin/idleo-runner.new"
    inside bash -c 'systemctl stop idleo-runner.service; mv /usr/local/bin/idleo-runner.new /usr/local/bin/idleo-runner; systemctl start idleo-runner.service'
    for _ in $(seq 1 300); do answers && break; sleep 0.2; done
    answers || die "the runner didn't come back"
    echo "dev-distro: idleo-runner replaced and answering"
    ;;
  exec)
    [[ "${1:-}" == "--" ]] && shift
    running || die "$name isn't running"
    exec docker exec -i "$name" "$@"
    ;;
  *)
    sed -n '2,/^set -euo/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//' >&2
    exit 2
    ;;
esac
