#!/usr/bin/env bash
# Runs the 16 hostile tests (ADR 0035, §7, and ADR 0038) against the development distribution and prints a table. A single FAIL, or a
# row that never reached its verdict (table.py), exits non-zero.
#
#   agent/runner/tests/hostile/run.sh            use the running distribution and registry (start them if they aren't)
#   agent/runner/tests/hostile/run.sh --fresh    remove the distribution first, so it starts cold
#   agent/runner/tests/hostile/run.sh --host ID  the same 16, through the real path on a linked host: the stack, the agent, the
#                                                job runner in its Linux distribution (the Windows laptop on laptop day), by
#                                                booking the development-only hostile-test template as the key's owner
#                                                (IDLEO_API_KEY, IDLEO_API_URL; ./start.sh --lan --real-runner publishes the
#                                                template's image) and reading each session back; two it can't prove from there
#                                                are N/A with the reason. A single FAIL stops the laptop day.
#
# It needs Docker, the sealed image (scripts/build-distro.sh) and the internet (HTTPS to a public address is the positive
# control that makes a "blocked" mean something, and the fair-use tests flood a public speed-test address: speed.cloudflare.com).
set -euo pipefail
. "$(dirname "$0")/../../../../scripts/lib/quiet.sh"
quiet_entry hostile "$@"
set -- ${QUIET_ARGS[@]+"${QUIET_ARGS[@]}"}

cd "$(dirname "$0")/../../../.."
root="$(pwd)"
source "$root/scripts/lib/toolchains.sh"
fresh=false
[[ "${1:-}" == "--fresh" ]] && fresh=true
if [[ "${1:-}" == "--host" ]]; then
  [[ -n "${2:-}" ]] || { echo "hostile: --host needs the host's id" >&2; exit 2; }
  [[ -x "$root/cli/target/release/idleo" ]] || (cd "$root/cli" && cargo build --release --locked --quiet)
  exec python3 "$root/agent/runner/tests/hostile/laptop.py" --host "$2" "${@:3}"
fi
out="$root/private/logs"
mkdir -p "$out"
log="$out/hostile-tests.log"

registry_digest="registry:2@sha256:a3d8aaa63ed8681a604f1dea0aa03f100d5895b6a58ace528858a7b332415373"
if ! curl -fsS http://127.0.0.1:5000/v2/ >/dev/null 2>&1; then
  docker rm -f idleo-dev-registry >/dev/null 2>&1 || true
  docker run -d --name idleo-dev-registry -p 127.0.0.1:5000:5000 "$registry_digest" >/dev/null
  for _ in $(seq 1 30); do curl -fsS http://127.0.0.1:5000/v2/ >/dev/null 2>&1 && break; sleep 1; done
fi
# The distribution reaches our registry at its address on Docker's bridge, which they share. Not through
# host.docker.internal, as compose's registry on a Mac is: on Linux that name is the bridge's gateway, and a port published
# on 127.0.0.1 refuses there.
distro_args=()
address="$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' idleo-dev-registry 2>/dev/null || true)"
[[ -n "$address" ]] && distro_args=(--registry "$address:5000")

$fresh && "$root/scripts/dev-distro.sh" down >/dev/null
"$root/scripts/dev-distro.sh" up ${distro_args[@]+"${distro_args[@]}"}
# Seven minutes of attacks that never ran otherwise: say so now.
registry="$("$root/scripts/dev-distro.sh" exec -- sed -n 's/^registry=//p' /etc/idleo/development)"
"$root/scripts/dev-distro.sh" exec -- timeout 5 bash -c "exec 3<>/dev/tcp/${registry%:*}/${registry##*:}" ||
  { echo "hostile: the distribution can't reach its registry at $registry, so no attack could run" >&2; exit 1; }

echo "hostile: building the hostile image"
docker buildx build --provenance=false --load -t localhost:5000/idleo/hostile:test agent/runner/tests/hostile >/dev/null
digest="$(docker push localhost:5000/idleo/hostile:test 2>&1 | sed -n 's/.*digest: \(sha256:[0-9a-f]\{64\}\).*/\1/p' | tail -1)"
[[ -n "$digest" ]] || { echo "hostile: the push gave no digest" >&2; exit 1; }
export IDLEO_HOSTILE_IMAGE="registry.idleo.test:5000/idleo/hostile@$digest"
echo "hostile: image $IDLEO_HOSTILE_IMAGE"

set +e
(cd agent && cargo test -p runner --test hostile -- --ignored --test-threads=1 --nocapture) >"$log" 2>&1
status=$?
set -e

rows_held=0
python3 "$root/agent/runner/tests/hostile/table.py" "$log" || rows_held=$?
echo
grep -E 'TIMING' -A4 "$log" | cut -c1-400 || true
# The tests that are not attacks (fair use of the owner's internet, what a session writes outside its encrypted disk), with their numbers.
grep -oE '(FAIR|WATCH|RULES-CHECK|LEFTOVERS-CHECK) .*' "$log" | cut -c1-400 || true
if [[ "$status" != 0 || "$rows_held" != 0 ]]; then
  grep -E 'panicked|assertion|FAIL' -A6 "$log" | head -60 >&2 || true
  exit 1
fi
