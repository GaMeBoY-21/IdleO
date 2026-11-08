#!/usr/bin/env bash
# Operations' checks (ADR 0036; scripts/check-all.sh runs this): the alert rules are valid Prometheus rules and each
# fires when its tests say (promtool), each converts into Grafana Cloud's alerting and its loader changes nothing on a
# second run (ADR 0054), development's routing is valid (amtool), the dashboards are valid JSON on the one data source variable and read only our own meters' labels, and
# the scripts parse. Every tool is the image development already runs, pinned.
set -euo pipefail
. "$(dirname "$0")/../lib/quiet.sh"
quiet_entry ops "$@"
set -- ${QUIET_ARGS[@]+"${QUIET_ARGS[@]}"}
cd "$(dirname "$0")/../.."
root="$(pwd)"
lgtm=grafana/otel-lgtm:0.39.0@sha256:4c8056c9c05fcb3c8fd93800e85334bfd79b4d0271b47a54a121a64af41fe1fe
alertmanager=prom/alertmanager:v0.34.2@sha256:39d8bb3966c694a124428a3a88fbcffd40bd069129ecd47c059f8a3f17c55f3b
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
# Every rule converts into Grafana Cloud's alerting, and the loader is idempotent against a fake Grafana (ADR 0054).
ops/grafana/apply-rules.sh --check
python3 "$root/ops/grafana/rules-test.py"

# Every long suite reports through the one reporter, runs on a schedule, and has both alerts, with a window of its interval
# plus a margin: weekly plus a day, nightly plus a few hours (ADR 0050).
python3 "$root/scripts/ops/report-suite-test.py"
python3 - "$root" <<'PY'
import re, sys, yaml
root = sys.argv[1]
SUITES = {"next-runner": "next-runner", "hostile": "hostile", "failover": "failover", "k8s": "k8s", "gitops": "gitops", "security": "security",
          "e2e": "e2e", "web-browser": "web", "scan": "scan"}
rules = [rule for name in ("long-suites", "security")
         for group in yaml.safe_load(open(f"{root}/ops/alerts/{name}.yaml"))["groups"] for rule in group["rules"]]
problems = []
for suite, workflow in SUITES.items():
    text = open(f"{root}/.github/workflows/{workflow}.yml").read()
    if not re.search(rf"uses: \./\.github/actions/report-suite\n\s+with:\n\s+suite: {suite}\n\s+status: \$\{{\{{ job\.status \}}\}}", text):
        problems.append(f"{workflow}.yml doesn't report {suite} through .github/actions/report-suite")
    cron = re.search(r'schedule:\n(?:\s*#.*\n)*\s+- cron: "([^"]+)"', text)
    if not cron:
        problems.append(f"{workflow}.yml has no schedule, so {suite} can go silent")
        continue
    window = "30h" if cron[1].split()[-1] == "*" else "8d"
    for kind, function in (("failing", "last_over_time"), ("missing", "absent_over_time")):
        wanted = f'{function}(idleo_suite_passed{{suite="{suite}"}}[{window}])'
        if not any(rule["expr"].startswith(wanted) and rule["labels"]["severity"] == "digest" for rule in rules):
            problems.append(f"no {kind} alert for {suite}: {wanted}")
sys.exit("\n".join(problems) if problems else 0)
PY

docker run --rm -v "$root/ops/alertmanager:/config:ro" --entrypoint amtool "$alertmanager" check-config /config/development.yaml

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
            # The control plane's meters, or the database's (CloudNativePG's, remote-written by the cluster's
            # Prometheus, ADR 0047).
            if 'job="control-plane"' not in target["expr"] and 'job="database"' not in target["expr"]:
                problems.append(f"{path}: {panel['title']} reads beyond the control plane's and the database's meters")
            if re.search(r"service_name|service_instance_id", target["expr"]):
                problems.append(f"{path}: {panel['title']} uses a label Grafana Cloud doesn't keep")
sys.exit("\n".join(problems) if problems else 0)
PY
for script in scripts/ops/*.sh ops/backup/*.sh ops/postgres/*.sh; do
  [[ -f "$script" ]] && bash -n "$script"
done
# WAL-G's download: a wrong file is tried again and then refused, saying what came back; the right one is kept and used
# without asking again.
wal_g_check="$(mktemp -d)"
trap 'rm -rf "$wal_g_check"' EXIT
mkdir -p "$wal_g_check/served"
printf 'not wal-g' >"$wal_g_check/served/wal-g-pg-24.04-amd64"
right="$(shasum -a 256 "$wal_g_check/served/wal-g-pg-24.04-amd64" | cut -d' ' -f1)"
fetch() {
  WAL_G_BASE="file://$wal_g_check/served" WAL_G_ARCH=amd64 WAL_G_TRIES=2 WAL_G_CACHE="$wal_g_check/cache" \
    WAL_G_OUT="$wal_g_check/out" WAL_G_PINS="$wal_g_check/pins" ops/postgres/fetch-wal-g.sh
}
echo "0000000000000000000000000000000000000000000000000000000000000000  wal-g-amd64" >"$wal_g_check/pins"
if refused="$(fetch 2>&1)"; then
  echo "ops: fetch-wal-g accepted a file that isn't the pinned one"; exit 1
fi
[[ "$refused" == *"FAILED"*"$right"* ]] || { echo "ops: fetch-wal-g's refusal doesn't say what came back: $refused"; exit 1; }
echo "$right  wal-g-amd64" >"$wal_g_check/pins"
fetch >/dev/null
rm "$wal_g_check/served/wal-g-pg-24.04-amd64"
fetch >/dev/null || { echo "ops: fetch-wal-g asked again for a file it had kept"; exit 1; }
[[ "$(cat "$wal_g_check/out/wal-g-amd64")" == "not wal-g" ]] || { echo "ops: fetch-wal-g didn't put the file in the build context"; exit 1; }
grep -q "sha256sum --check --strict" ops/postgres/Dockerfile || { echo "ops: the Dockerfile no longer checks WAL-G's hash"; exit 1; }
echo "ops: rules, routing, dashboards and scripts pass"
