#!/usr/bin/env bash
# Loads ops/alerts (the rules) and ops/alertmanager/production.yaml (where they go) into Grafana Cloud, with Grafana's
# mimirtool, pinned (ADR 0036, docs/runbooks/monitoring.md "Alerts in Grafana Cloud"). The same rule files development's
# Prometheus reads; checked first (promtool's unit tests run in scripts/checks/ops.sh).
#
#   scripts/ops/load-alerts.sh           check, then load the rules and the routing
#   scripts/ops/load-alerts.sh --check   only check them, against nothing
#
# Reads, never from a file in the repo: MIMIR_ADDRESS (the stack's Prometheus URL, https://prometheus-….grafana.net),
# MIMIR_TENANT_ID and MIMIR_API_USER (its instance id), MIMIR_API_KEY (an access policy token with rules:write and
# alerts:write), and for the routing ALERTS_TO, ALERTS_FROM, ALERTS_SMTP_HOST, ALERTS_SMTP_USERNAME, ALERTS_SMTP_PASSWORD.
set -euo pipefail

cd "$(dirname "$0")/../.."
root="$(pwd)"
mimirtool=grafana/mimirtool:3.1.7@sha256:d971de869b68b3d9d0b92ba40fb7e35fb6e6126816827aefc8925fbb5519e118
check_only=false
[[ "${1:-}" == --check ]] && check_only=true

rules=()
for file in "$root"/ops/alerts/*.yaml; do rules+=("/alerts/$(basename "$file")"); done
run() {
  docker run --rm -i -v "$root/ops/alerts:/alerts:ro" \
    -e MIMIR_ADDRESS -e MIMIR_TENANT_ID -e MIMIR_API_USER -e MIMIR_API_KEY "$mimirtool" "$@"
}

# Rendered in memory: the routing holds the SMTP password.
render() {
  python3 - "$root/ops/alertmanager/production.yaml" <<'PY'
import os, re, sys
text = open(sys.argv[1]).read()
missing = sorted({name for name in re.findall(r"\$\{(\w+)\}", text) if not os.environ.get(name)})
if missing:
    sys.exit("load-alerts: set " + ", ".join(missing))
print(re.sub(r"\$\{(\w+)\}", lambda m: os.environ[m.group(1)], text), end="")
PY
}

run rules check "${rules[@]}"
if $check_only; then
  echo "load-alerts: the rules pass mimirtool's checks"
  exit 0
fi
for name in MIMIR_ADDRESS MIMIR_TENANT_ID MIMIR_API_USER MIMIR_API_KEY; do
  [[ -n "${!name:-}" ]] || { echo "load-alerts: set $name (docs/runbooks/monitoring.md)" >&2; exit 2; }
done
config="$(render)"
run rules sync "${rules[@]}"
printf '%s' "$config" | docker run --rm -i -e MIMIR_ADDRESS -e MIMIR_TENANT_ID -e MIMIR_API_USER -e MIMIR_API_KEY \
  --entrypoint sh "$mimirtool" -c 'cat > /tmp/alertmanager.yaml && mimirtool alertmanager load /tmp/alertmanager.yaml'
echo "load-alerts: rules and routing loaded"
