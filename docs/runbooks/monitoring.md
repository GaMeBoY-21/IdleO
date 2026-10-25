# Monitoring

How we know something broke before a user tells us (ADR 0036): the control plane's metrics, traces and logs, four dashboards (platform health, fleet, marketplace and money), alert rules with a runbook section each (docs/runbooks/alerts.md), and a check from outside for when the whole server is gone.

## What sends what

- **The control plane** sends metrics, traces and logs through **OTLP** (OpenTelemetry's protocol), with Spring Boot's own OpenTelemetry support: Micrometer's OTLP registry for meters, Micrometer Tracing's OpenTelemetry bridge for traces, and OpenTelemetry's Logback appender for logs. Every signal names the release (`IDLEO_RELEASE`), the environment (`IDLEO_ENVIRONMENT`) and the instance (`IDLEO_INSTANCE`, else the host name). Meters go once a minute; a tenth of traces in production.
- **Logs** are also printed to the console as ECS JSON with the trace id, for `docker logs`. Never personal data or a secret: no email, token, key, signed link or renter's file name. A test reads the whole test suite's log for them (`LogScan`) and the build fails on a finding.
- **The agents send nothing new to anyone.** Their health reaches us on the stream they already have (heartbeats, self-tests, update results) and the control plane turns it into meters.
- **The web app and the website** send Core Web Vitals and JavaScript errors from real visits to `POST /v1/vitals` on the control plane, which turns them into meters and logs (below). No third-party error service.
- **Only health is served over HTTP** (`/actuator/health/liveness` and `/readiness`); nothing else of the actuator is reachable from outside.

What's measured, by the module that owns the fact:

| Module | Meters |
| --- | --- |
| Every request | `http.server.requests` by route template, method and status; `grpc.server` by method |
| Platform | database pool (`hikaricp.*`), JVM memory and GC, CPU, disk, Valkey latency (`lettuce`), errors logged (`logback.events`), event publications failed and stuck, readiness by component (`health.ready`), certificates' expiry (`hosts.ca.certificate.expiry`, `ssl.chain.expiry`) |
| hosts | `hosts` by state and online; `hosts.links` started, approved, denied; `agents.self-tests`, `agents.self-test.failures` by check; `agents.updates`, `agents.update.results` by version and outcome |
| agentgateway | `agents.connected`, `agents.heartbeat.age.max`, streams closed and Hellos refused by reason |
| sessions | `sessions.started`, `sessions.resumed`, `sessions.ended` by state and reason (the codes of `api/endings.json`), `sessions.time-to-gpu`, `sessions.time-to-first-log` |
| billing | `billing.ledger.paise` by what caused it, account and direction (charged, given back, granted); `billing.ledger.transactions`; `billing.reserved.paise` |
| notifications | `notifications.emails` sent, failed, bounced, complained |
| Backups | `backups.full.age`, `backups.wal.lag`, `backups.readable` (docs/runbooks/backups.md) |
| Browsers | `browser.vitals.lcp`, `.inp`, `.cls`, `.ttfb` by app, route and phone or laptop; `browser.errors` by app, route and release |

## In development

`./start.sh` runs it all: compose.yaml's `observability` service (one `grafana/otel-lgtm` container: Grafana, Prometheus, Loki, Tempo and an OpenTelemetry collector, pinned by digest) and `alertmanager`, which sends alerts to Mailpit.

- Grafana: http://localhost:3300, no sign-in. The four dashboards are in the IdleO folder. Explore has the logs (Loki) and traces (Tempo).
- Prometheus: http://localhost:9190 (its alerts page shows each rule's state).
- Alerts: Mailpit, http://localhost:8025, from `alerts@idleo.test`.

The dashboards are `ops/grafana/dashboards/*.json`, loaded at start; a change made in Grafana's editor is lost on restart, so change the JSON. The rules are `ops/alerts/*.yaml`, Prometheus's own format, each tested by `promtool test rules` against `ops/alerts/tests/` (`scripts/checks/ops.sh`, in `scripts/check-all.sh`).

## In production: Grafana Cloud's free tier

Any OTLP endpoint works; Grafana Cloud is the default because its free tier covers the pilot. What it includes, from [grafana.com/pricing](https://grafana.com/pricing/) (read 2026-10-20):

| | Free tier |
| --- | --- |
| Metrics | 10,000 active series, 14 days kept |
| Logs | 50 GB ingested a month, 14 days kept |
| Traces | 50 GB ingested a month, 14 days kept |
| Users | 3 |
| Incident response (IRM) | 3 users |

Grafana bills metrics as the larger of active series and data points a minute over 1 a series ([how metrics are billed](https://grafana.com/docs/grafana-cloud/cost-management-and-billing/understand-your-invoice/metrics-invoice/)), which is why the control plane sends once a minute (`management.otlp.metrics.export.step: 1m`). Every timer an alert or dashboard reads a percentile from has a few fixed buckets, never Micrometer's 69 (`management.metrics.distribution.slo`). Measured on the local stack with two agents and a little traffic, 2026-10-21: 674 series from one instance (1,204 with Micrometer's default buckets); routes add series as they're used, a few hundred more in production. The pilot's two instances stay well inside 10,000. Logs at INFO and a tenth of traces are a few hundred MB a month.

### Setting it up (Nikki, once)

1. **Make the account** at grafana.com and a stack in the region nearest India it offers. Note the stack's name.
2. **OTLP:** in the stack's page, OpenTelemetry → Configure. It gives the OTLP endpoint (`https://otlp-gateway-<zone>.grafana.net/otlp`), an instance id, and makes a token. Set on the control plane's server:
   - `OTLP_ENDPOINT` = that endpoint, with no `/v1/...` after it;
   - `OTLP_AUTHORIZATION` = `Basic ` followed by the base64 of `<instance id>:<token>` (`printf '%s:%s' "$ID" "$TOKEN" | base64`).
   The prod profile refuses to start without an HTTPS endpoint (`ProductionSettingsGuard`). Within two minutes of a start, Explore → Metrics shows `jvm_memory_used_bytes{job="control-plane"}`.
3. **Dashboards:** Dashboards → New → Import, upload each of `ops/grafana/dashboards/*.json`, and choose the stack's Prometheus (`grafanacloud-<stack>-prom`) for the `datasource` variable.
4. **Alerts:** `scripts/ops/load-alerts.sh` loads the same rule files into the stack's Prometheus (Mimir) and the routing, `ops/alertmanager/production.yaml`, into its Alertmanager, with Grafana's `mimirtool`. It reads, from your shell only:
   - `MIMIR_ADDRESS`: the stack's Prometheus URL, without `/api/prom` (stack page → Prometheus → Details);
   - `MIMIR_TENANT_ID` and `MIMIR_API_USER`: the Prometheus instance id, from the same page;
   - `MIMIR_API_KEY`: an access policy token with `rules:read`, `rules:write`, `alerts:read` and `alerts:write`;
   - `ALERTS_TO`: where alerts go (your email); `ALERTS_FROM`: an address on our sending domain, for example `alerts@mail.<domain>`;
   - `ALERTS_SMTP_HOST`, `ALERTS_SMTP_USERNAME`, `ALERTS_SMTP_PASSWORD`: the email provider's SMTP relay, the same as the product's (docs/runbooks/email.md: Resend's are `smtp.resend.com`, `resend` and an API key).
   Run it again whenever `ops/alerts/` or the routing changes. Then Alerting → Alert rules lists the 14 rules under the stack's Prometheus.
5. **Phone:** alerts are email; turn on notifications for that address in your phone's mail app, or forward it to one. The external uptime check below pushes to its app too, for when everything is down.
6. **Test it:** stop the control plane on the server for 5 minutes. `ControlPlaneSilent` should email you within about 3 to 4 minutes (measured locally: see "What it takes to hear" below), and the uptime check within 5. Start it again; both say resolved.

## From outside: the uptime check

Our own monitoring can't say the server is gone if the server is what's gone. An outside service checks it.

**UptimeRobot**, free plan, from [uptimerobot.com/pricing](https://uptimerobot.com/pricing/) and its [terms](https://uptimerobot.com/terms) (read 2026-10-20): 50 monitors, checked every 5 minutes, email alerts, commercial use allowed ("UptimeRobot is available for any use, including commercial and business use", under its fair-use policy). Its [mobile app](https://uptimerobot.com/mobile-app/) can be used on every plan, the free one included; the page doesn't say plainly that push alerts are on the free plan, so confirm with the test below. SMS and voice calls are paid credits.

1. Sign up at uptimerobot.com with your email.
2. Add three HTTP(S) monitors, every 5 minutes:
   - the API: `https://api.<domain>/actuator/health/liveness`, keyword monitor for `"UP"`;
   - the website: `https://<domain>/`;
   - the web app: `https://app.<domain>/`.
3. Alert contacts: your email, and the UptimeRobot app on your phone (install it, sign in, allow notifications).
4. Test: pause the API's monitor's target (stop the control plane for 6 minutes) and check both the email and the phone's push arrive.

## What it takes to hear

Measured locally by the alert drill (`scripts/ops/alert-drill.sh`), 2026-10-21, from the failure to the email in the inbox:

| Failure | Alert | Time |
| --- | --- | --- |
| The control plane dies (killed outright) | ControlPlaneSilent | 188 s (3.1 min) |
| WAL archiving stops | NoBackup | 174 s with a 1-minute test window; production's 10-minute window makes it about 12 minutes |

Most of the first is the alert's own 3-minute window (meters come once a minute, so a late batch never fires it); the rest is evaluation (15 s) and the wait before an alert email goes (5 s here, 30 s in production's routing). Grafana Cloud evaluates every minute, so expect up to a minute more there. The outside uptime check (every 5 minutes) is the other path to you.

## When monitoring itself breaks

- **No data in Grafana Cloud, the control plane up:** its log says why an export failed. The usual cause is an expired or revoked token in `OTLP_AUTHORIZATION`.
- **Over the free tier:** Grafana Cloud's usage page names the metric. A new meter with an unbounded tag (an id, a path) is a bug: tags are codes, routes and versions only.
- **The rules changed but Grafana Cloud's didn't:** run `scripts/ops/load-alerts.sh` again.
