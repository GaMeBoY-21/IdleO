#!/usr/bin/env bash
# How each install of the Windows app went, read from the local stack's database: the
# setup app's own measurements, which it sends as it goes (hosts.install_events). For each install, newest first: the
# minutes from opening the setup app to "listed", the clicks it took, each step's duration and how it went (with the
# words behind a failure, which setup scrubbed of the person and the machine before sending), under "facts" what a
# development build found out about the laptop (WSL, kernel, driver, memory, what the distribution can reach), and
# where it stopped.
# Nothing is copied from the laptop: run this on the Mac after the laptop test (docs/windows-laptop-test.md).
#
#   scripts/install-report.sh                  every install the local stack (./start.sh) has seen
#   scripts/install-report.sh <install id>     just that one
#   COMPOSE_PROJECT_NAME=idleo-e2e scripts/install-report.sh
#                                              the e2e stack's database instead
set -euo pipefail
cd "$(dirname "$0")/.."
root="$(pwd)"
install="${1:-}"
if [[ -n "$install" && ! "$install" =~ ^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$ ]]; then
  echo "install-report: $install isn't an install id (a UUID)" >&2
  exit 2
fi
project="${COMPOSE_PROJECT_NAME:-idleo-local}"
psql() {
  COMPOSE_PROJECT_NAME="$project" docker compose -f "$root/control-plane/compose.yaml" exec -T postgres \
    psql -U controlplane -d controlplane -X -q -tA -v ON_ERROR_STOP=1 -v install="$install" "$@"
}
psql -c 'select 1' >/dev/null 2>&1 ||
  { echo "install-report: the $project stack's database isn't running (./start.sh starts it)" >&2; exit 1; }

psql <<'SQL'
with installs as (
  select install_id,
         min(at) filter (where step = 'setup_opened') as opened_at,
         min(at) filter (where step = 'listed') as listed_at,
         count(*) filter (where step = 'click') as clicks,
         max(setup_version) as setup_version,
         max(windows_build) as windows_build,
         max(gpu_model) as gpu_model
  from hosts.install_events
  where :'install' = '' or install_id::text = :'install'
  group by install_id
),
steps as (
  -- Each step's end, with its duration, in the order the steps ran.
  select install_id,
         string_agg(format('    %-10s %8s  %s%s%s', step,
                           coalesce(round(duration_ms / 1000.0, 1)::text || ' s', ''),
                           outcome, coalesce(': ' || reason, ''),
                           coalesce(E'\n        why: ' || replace(detail, E'\n', E'\n             '), '')),
                    E'\n' order by at, received_at) as lines
  from hosts.install_events
  where step not in ('setup_opened', 'listed', 'click', 'facts') and outcome <> 'started'
  group by install_id
),
facts as (
  -- A development build's environment facts about the laptop, one "name: value" a line, in the order sent.
  select install_id,
         string_agg(replace(detail, E'\n', E'\n    '), E'\n    ' order by at, received_at) as lines
  from hosts.install_events
  where step = 'facts' and detail is not null
  group by install_id
),
clicks as (
  select install_id, string_agg(coalesce(reason, '?'), ', ' order by at) as buttons
  from hosts.install_events where step = 'click' group by install_id
),
last as (
  select distinct on (install_id) install_id, step, outcome, reason
  from hosts.install_events
  where step not in ('click', 'facts')
  order by install_id, at desc, received_at desc
)
select format(E'Install %s\n  setup %s, Windows build %s, %s\n  opened %s UTC\n  %s\n  clicks: %s%s\n%s\n%s  stopped at: %s\n',
              i.install_id,
              coalesce(i.setup_version, '?'), coalesce(i.windows_build::text, '?'), coalesce(i.gpu_model, 'no GPU named'),
              coalesce(to_char(i.opened_at at time zone 'UTC', 'YYYY-MM-DD HH24:MI:SS'), '(no setup_opened event)'),
              case when i.listed_at is not null and i.opened_at is not null
                   then format('listed after %s min', round(extract(epoch from i.listed_at - i.opened_at) / 60.0, 1))
                   else 'not listed' end,
              i.clicks, coalesce(' (' || c.buttons || ')', ''),
              coalesce(E'  steps:\n' || s.lines, '  steps: none reported'),
              coalesce(E'  facts:\n    ' || f.lines || E'\n', ''),
              case when i.listed_at is not null then 'listed'
                   else concat_ws(' ', l.step, l.outcome) || coalesce(': ' || l.reason, '') end)
from installs i
left join steps s using (install_id)
left join clicks c using (install_id)
left join facts f using (install_id)
left join last l using (install_id)
order by i.opened_at desc nulls last;
SQL
count="$(psql <<<"select count(distinct install_id) from hosts.install_events where :'install' = '' or install_id::text = :'install'")"
if [[ "$count" == 0 ]]; then
  echo "install-report: no installs yet${install:+ with id $install}"
fi
