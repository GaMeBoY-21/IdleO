# 0054. Alerts run in Grafana Cloud's own alerting

- **Status:** Accepted
- **Date:** 2026-11-06

## Context

Metrics reach Grafana Cloud (ADR 0036), but no rule had been loaded, so a red or silent suite (ADR 0050) emailed nobody. The loader we had, `scripts/ops/load-alerts.sh`, put the rule files into the stack's Prometheus ruler and the routing into its Alertmanager with `mimirtool`. It needed an access policy token with four scopes, three ids, and an SMTP relay with its password in the shell. That is more setup than one person does from a phone-sized checklist, and it never ran.

Grafana Cloud's own alerting reads the same Prometheus data source, sends email itself, and has a provisioning API that a service account token can use.

## Decision

**`ops/grafana/apply-rules.sh` loads `ops/alerts` into the stack's own alerting.** It needs `GRAFANA_URL`, `GRAFANA_TOKEN` (a token of the service account `alert-rules`, with the Admin role, made for the run and deleted after it) and `ALERTS_TO`, all read from the shell of the person who runs it. Nothing is stored in the repo or in CI.

- **The rule files stay the one source.** Development's Prometheus reads them unchanged, `promtool` tests them, and the script converts each rule as it loads. The query is the rule's expression at one instant. The condition holds for every series the query returns, as a Prometheus rule fires for every series. No data is OK, and a query error fires. `{{ $value }}` becomes `{{ $values.A.Value }}`, so `humanize` still reads the number.
- **Pages and tickets are apart.** Pages go in the folder *IdleO pages* and tickets (`severity: digest`) in *IdleO tickets*, each under its group's name.
- **One contact point and the routing.** The email contact point is *IdleO email*. Pages are sent at once, and again every 4 hours while they last. Tickets go in one email a day, as ADR 0036 set.
- **Idempotent.** Every object has a fixed id and is replaced whole. Our rules that are no longer in the files are deleted, and a second run makes or deletes nothing. `--test` sends one test alert through the contact point's email integration, on Grafana's receiver test endpoint (`/apis/notifications.alerting.grafana.app/v1beta1/namespaces/<namespace>/receivers/-/test`; the legacy `receivers/test` was removed in Grafana 13 and answers 410).
- **Production's rules are muted until go-live.** Every rule has an `env` label: `production` for the running service, `ci` for a suite CI runs (those read `idleo_suite_passed`, and the conversion refuses a rule whose label and expression disagree). Before there is a server, every production rule would fire. While `PRE_LAUNCH` is on, the default, production's routes go through an always-on mute timing, *idleo-pre-launch*, and CI's stay live. A mute timing rather than a silence, because a silence expires and isn't replaced by a second run. Turning `PRE_LAUNCH` off is a step of going live (S5; docs/runbooks/deploying.md, "Going live").
- **It is checked.** `scripts/checks/ops.sh` converts every rule (`--check`) and runs the loader twice against a fake Grafana (`ops/grafana/rules-test.py`).

`scripts/ops/load-alerts.sh` and `ops/alertmanager/production.yaml` are removed. Development keeps its Prometheus and Alertmanager (`ops/alertmanager/development.yaml`).

## Alternatives considered

- **Keeping mimirtool and the stack's Alertmanager.** It takes the files as they are, but needs the SMTP relay's password and four token scopes in one shell. It also never ran in two weeks.
- **Two loaders, one per kind of alert.** That is two implementations of one rule, and the two would drift.
- **Rules written in Grafana's browser editor.** They wouldn't be reviewed, tested or in git.

## Consequences

- One token and one address are all it takes to get alerts, and Grafana Cloud's own mail sends them.
- Grafana's evaluation differs from Prometheus's in small ways. `for` carries over. A rule key the conversion can't carry, such as `keep_firing_for`, is refused by `--check` rather than dropped. A rule whose query errors fires as `DatasourceError` instead of staying quiet, which is louder than before and on purpose.
- The provisioned rules can't be edited in the browser. A change is a change to `ops/alerts` and another run.
- `mimirtool`'s own check of the rules went with it. `promtool` still checks every rule's syntax and tests it, and the conversion check and the fake Grafana check the rest.
- The Editor role wasn't enough on our stack: `alert-rules` is Admin. Its token is made for each run and deleted after it, so no lasting token with that role exists.
