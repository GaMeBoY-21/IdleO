#!/usr/bin/env bash
# ./start.sh, as a person would use it, without a screen: start everything, wait until it says it's ready, book one
# simulated session through the API as nikki@example.com and see it complete, then ./start.sh --stop from "another
# terminal", and check nothing it started is left (processes, containers, ports). CI runs it after the e2e
# (.github/workflows/e2e.yml); so does scripts/check-all.sh's e2e suite.
set -euo pipefail

cd "$(dirname "$0")/../.."
root="$(pwd)"
out="$root/logs"
mkdir -p "$out"
fail() { printf 'start-check: FAIL: %s\n' "$*" >&2; exit 1; }
api=http://localhost:8080
sign_in=http://localhost:8099/idleo-local

started=$SECONDS
./start.sh --no-browser >"$out/start-check.log" 2>&1 &
start_pid=$!
cleanup() {
  ./start.sh --stop >/dev/null 2>&1 || true
  kill "$start_pid" 2>/dev/null || true
}
trap cleanup EXIT

deadline=$((SECONDS + ${START_CHECK_TIMEOUT:-1200}))
until grep -q "^start: ready" "$out/start-check.log"; do
  kill -0 "$start_pid" 2>/dev/null || fail "./start.sh stopped before it was ready: $(tail -5 "$out/start-check.log")"
  ((SECONDS < deadline)) || fail "./start.sh wasn't ready in time: $(tail -5 "$out/start-check.log")"
  sleep 2
done
ready_in=$((SECONDS - started))
for line in "Web app" "Public site" "Mailpit" "Admin console" "nikki-low@example.com" "logs/"; do
  grep -q "$line" "$out/start-check.log" || fail "the ready block doesn't mention $line"
done

token="$(curl -sf -X POST "$sign_in/token" -d grant_type=client_credentials -d client_id=nikki \
  -d client_secret=unused -d scope=openid | python3 -c 'import json, sys; print(json.load(sys.stdin)["access_token"])')"
[[ -n "$token" ]] || fail "no token for nikki"
session="$(curl -sf -X POST "$api/v1/sessions" -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: start-check-$(date +%s)" \
  -d '{"templateId": "cuda-smoke-test", "maxPricePaisePerMin": 20, "maxSpendPaise": 1000}' |
  python3 -c 'import json, sys; print(json.load(sys.stdin)["id"])')" || fail "booking a session failed"
deadline=$((SECONDS + 180))
until [[ "$(curl -sf "$api/v1/sessions/$session" -H "Authorization: Bearer $token" |
  python3 -c 'import json, sys; s = json.load(sys.stdin); print(s["state"] if s["settled"] else "")')" == COMPLETED ]]; do
  ((SECONDS < deadline)) || fail "the simulated session $session didn't complete in 3 minutes"
  sleep 2
done

stop_started=$SECONDS
./start.sh --stop >>"$out/start-check.log" 2>&1
deadline=$((SECONDS + 60))
while kill -0 "$start_pid" 2>/dev/null; do
  ((SECONDS < deadline)) || fail "./start.sh was still running a minute after --stop"
  sleep 1
done
trap - EXIT

leftovers=""
[[ -z "$(docker ps -q --filter label=com.docker.compose.project=idleo-local)" ]] || leftovers+=" containers"
pgrep -f "private/local/agent-[0-9].toml" >/dev/null && leftovers+=" agents"
pgrep -f "dist-website-local" >/dev/null && leftovers+=" website"
for port in 8080 9443 3000 3002; do
  (exec 3<>"/dev/tcp/127.0.0.1/$port") 2>/dev/null && leftovers+=" port-$port"
done
[[ -z "$leftovers" ]] || fail "still running after ./start.sh --stop:$leftovers"
printf 'start-check: ./start.sh ready in %s s; session %s booked and completed; --stop left nothing running (%s s): PASS\n' \
  "$ready_in" "$session" $((SECONDS - stop_started))
