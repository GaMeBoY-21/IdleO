#!/usr/bin/env bash
# The start of every session (CLAUDE.md, "Pushing to main"): how the last run of each long suite on main ended, and of the
# fast suites' last push. Prints one line per workflow and exits non-zero if any is red or has gone quiet for longer than its
# interval (30 hours for a nightly, 8 days for a weekly), so a red night is read first and fixed first.
#
#   scripts/nightly-status.sh
set -uo pipefail
cd "$(dirname "$0")/.."
now=$(date -u +%s)
bad=0
# workflow, how it runs (schedule or push), the longest quiet it may have, in hours
rows=(
  "e2e.yml schedule 30" "web.yml schedule 30" "k8s.yml schedule 30" "gitops.yml schedule 30"
  "security.yml schedule 30" "failover.yml schedule 30" "scan.yml schedule 30"
  "hostile.yml schedule 192" "budgets.yml schedule 192" "next-runner.yml schedule 192" "agent.yml schedule 192" "cli.yml schedule 192"
  "control-plane.yml push 100000" "codeql.yml push 100000" "gitleaks.yml push 100000" "deploy.yml push 100000" "proto.yml push 100000"
  "scanner.yml push 100000" "sdk.yml push 100000"
)
for row in "${rows[@]}"; do
  read -r workflow event hours <<<"$row"
  line="$(gh run list --workflow "$workflow" --branch main --event "$event" --status completed --limit 1 \
    --json conclusion,createdAt,url -q '.[0] | "\(.conclusion) \(.createdAt) \(.url)"' 2>/dev/null)"
  if [[ -z "$line" ]]; then
    printf '%-18s no completed %s run on main\n' "$workflow" "$event"
    [[ "$event" == schedule ]] && bad=1
    continue
  fi
  read -r conclusion created url <<<"$line"
  age=$(((now - $(date -u -j -f '%Y-%m-%dT%H:%M:%SZ' "$created" +%s 2>/dev/null || date -u -d "$created" +%s)) / 3600))
  mark=ok
  if [[ "$conclusion" != success ]]; then mark=RED; bad=1; fi
  if ((age > hours)); then mark="QUIET ${age}h"; bad=1; fi
  printf '%-18s %-9s %-6s %4sh ago  %s\n' "$workflow" "$conclusion" "$mark" "$age" "$url"
done
exit "$bad"
