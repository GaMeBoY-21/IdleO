#!/usr/bin/env bash
# Operations' checks (ADR 0036; scripts/check-all.sh runs this): the alert rules are valid Prometheus rules and each
# fires when its tests say (promtool), Grafana Cloud's ruler accepts them (mimirtool), both alert routings are valid
# (amtool), the dashboards are valid JSON on the one data source variable and read only our own meters' labels, and
# the scripts parse. Every tool is the image development already runs, pinned.
set -euo pipefail
. "$(dirname "$0")/../lib/quiet.sh"
quiet_entry ops "$@"
set -- ${QUIET_ARGS[@]+"${QUIET_ARGS[@]}"}
cd "$(dirname "$0")/../.."
root="$(pwd)"
lgtm=grafana/otel-lgtm:0.35.0@sha256:2de1094c593c671cbfca878a28ffcd7e46ff5e32f2ce966c0cf33003f6cf4266
alertmanager=prom/alertmanager:v0.34.1@sha256:e9733bafb1bdef9b00e25a21f8f99dc26a22224bf16641ad754d1649f4c3357a
for image in "$lgtm" "$alertmanager"; do
  grep -qF "$image" control-plane/compose.yaml || { echo "ops: $image isn't compose.yaml's" >&2; exit 1; }
done

promtool() { docker run --rm -v "$root/ops/alerts:/alerts:ro" -w /alerts --entrypoint /otel-lgtm/prometheus/promtool "$lgtm" "$@"; }
rule_files=()
for file in ops/alerts/*.yaml; do rule_files+=("$(basename "$file")"); done
promtool check rules "${rule_files[@]}"
(cd ops/alerts/tests && for test in *.test.yaml; do
  docker run --rm -v "$root/ops/alerts:/alerts:ro" -w /alerts/tests --entrypoint /otel-lgtm/prometheus/promtool "$lgtm" test rules "$test"
done)
# Every rule has a summary and a runbook section that exists.
python3 - "$root" <<'PY'
import glob, re, sys, yaml
root = sys.argv[1]
anchors = {re.sub(r"[^a-z0-9 -]", "", line[3:].strip().lower()).replace(" ", "-")
           for line in open(f"{root}/docs/runbooks/alerts.md") if line.startswith("## ")}
problems = []
for path in sorted(glob.glob(f"{root}/ops/alerts/*.yaml")):
    for group in yaml.safe_load(open(path))["groups"]:
        for rule in group["rules"]:
            notes = rule.get("annotations", {})
            if not notes.get("summary"):
                problems.append(f"{rule['alert']} has no summary")
            anchor = notes.get("runbook_url", "").partition("#")[2]
            if anchor not in anchors:
                problems.append(f"{rule['alert']}'s runbook section #{anchor} isn't in docs/runbooks/alerts.md")
            if rule.get("labels", {}).get("severity") not in ("page", "digest"):
                problems.append(f"{rule['alert']} has no severity page or digest")
sys.exit("\n".join(problems) if problems else 0)
PY
scripts/ops/load-alerts.sh --check

docker run --rm -v "$root/ops/alertmanager:/config:ro" --entrypoint amtool "$alertmanager" check-config /config/development.yaml
# Production's routing with stand-in values: its ${…} are filled from the environment only when it's loaded.
sed -e 's/\${ALERTS_SMTP_HOST}/smtp.example.invalid/' -e 's/\${ALERTS_[A-Z_]*}/x@example.invalid/g' ops/alertmanager/production.yaml |
  docker run --rm -i --entrypoint sh "$alertmanager" -c 'cat > /tmp/am.yaml && amtool check-config /tmp/am.yaml'

python3 - "$root" <<'PY'
import glob, json, re, sys
problems = []
for path in sorted(glob.glob(f"{sys.argv[1]}/ops/grafana/dashboards/*.json")):
    board = json.load(open(path))
    for panel in board["panels"]:
        if panel["type"] == "row":
            continue
        if panel.get("datasource", {}).get("uid") != "${datasource}":
            problems.append(f"{path}: {panel['title']} doesn't use the datasource variable")
        for target in panel.get("targets", []):
            if 'job="control-plane"' not in target["expr"]:
                problems.append(f"{path}: {panel['title']} reads beyond the control plane's meters")
            if re.search(r"service_name|service_instance_id", target["expr"]):
                problems.append(f"{path}: {panel['title']} uses a label Grafana Cloud doesn't keep")
sys.exit("\n".join(problems) if problems else 0)
PY
for script in scripts/ops/*.sh ops/backup/*.sh ops/postgres/*.sh; do
  [[ -f "$script" ]] && bash -n "$script"
done
echo "ops: rules, routing, dashboards and scripts pass"
