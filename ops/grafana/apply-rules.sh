#!/usr/bin/env bash
# Loads the alert rules in ops/alerts into the Grafana Cloud stack's own alerting, with one email contact point and the
# routing: pages at once and again every 4 hours, tickets (severity digest) in one email a day. Idempotent: run it again
# after any change to ops/alerts (ADR 0054, docs/runbooks/monitoring.md "Setting it up").
#
#   ops/grafana/apply-rules.sh           load the folders, the contact point, the routing and every rule
#   ops/grafana/apply-rules.sh --test    the same, then send one test alert to the contact point
#   ops/grafana/apply-rules.sh --check   only convert and check the rules, against nothing
#
# Reads, from the shell only and never from a file in the repo: GRAFANA_URL (the stack, https://<stack>.grafana.net),
# GRAFANA_TOKEN (a token of the alert-rules service account, Admin role, made for this run and deleted after it) and
# ALERTS_TO (the address alerts go to). GRAFANA_PROMETHEUS_UID picks the Prometheus data source when the stack has more
# than one. PRE_LAUNCH (on unless it says off): while on, the env=production rules are muted, since there is no server
# yet, and the CI suites' rules stay live; turning it off is a step of going live (docs/runbooks/deploying.md).
set -euo pipefail
cd "$(dirname "$0")/../.."
exec python3 ops/grafana/rules.py "$@"
