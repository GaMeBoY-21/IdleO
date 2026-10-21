# Alerts

Every alert in `ops/alerts/` links to its section here: what it means, what to check first, and what to do. The rules are Prometheus rules, the same files in development (Prometheus in compose.yaml's observability container, which emails Mailpit) and in production (Grafana Cloud, which emails the on-call address); `docs/runbooks/monitoring.md` says how they're loaded. `severity: page` goes out at once and again every 4 hours while it lasts; `severity: digest` once a day.

Where to look, for every alert:

- **Grafana**: the Platform health, Marketplace and Fleet dashboards (`ops/grafana/dashboards/`). Locally http://localhost:3300.
- **Logs**: Grafana's Explore, Loki, `{service_name="control-plane"}`, filtered by level or `trace_id`. Every unexpected error is logged at ERROR with its trace id and the release (`service_version`).
- **Traces**: Grafana's Explore, Tempo; a log line's trace id opens its trace.
- **The server**: `docker ps`, `docker logs`, `df -h`, as `docs/runbooks/deployment.md` sets it up.

Before reopening after any restore of the database: `ops/backup/verify-ledger.sh` (docs/runbooks/backups.md).

## Control plane silent

**Means:** no meter from any control-plane instance for 3 minutes. Either every instance is down, or none can reach the OTLP endpoint. Agents carry on until their leases lapse (2 minutes for batch jobs), then checkpoint and stop; nobody is charged for minutes no lease covered.

**Check first:** the external uptime monitor (UptimeRobot, monitoring.md): if it also says `/actuator/health/liveness` is down, the control plane is down. If it says up, the problem is the path to Grafana Cloud: the control plane's log shows `Failed to export` lines.

**Do:**
- Down: on the server, `docker ps -a` and `docker logs --tail 200 <control plane>`. A start refused by `ProductionSettingsGuard` names the setting. Out of memory or disk: see [Disk](#disk). Restart it; it waits for readiness before the load balancer sends traffic.
- Up but silent: check `OTLP_ENDPOINT` and `OTLP_AUTHORIZATION` (an expired Grafana Cloud token is the usual cause), and Grafana Cloud's status page.

## API errors

**Means:** more than 2% of API requests (health probes not counted) answered with a 5xx for 5 minutes, with at least 10 requests in the window.

**Check first:** Platform health, "Server errors by route": one route or all of them? Then Loki, `{service_name="control-plane"} | json | log_level="ERROR"` (or `level="ERROR"`), for the exception.

**Do:**
- All routes, with "Readiness" showing `db` or `storage` at 0: it's that dependency; see [Database](#database), or R2's status page for storage.
- One route since a deployment: roll the deployment back, then fix.
- 503s with Hikari "waiting" above zero: the pool is exhausted; see [Database](#database).

## Slow API

**Means:** the 95th percentile of API requests (event streams and health probes not counted) above 1 s for 10 minutes.

**Check first:** Platform health, "Slowest routes, p95", and "Database pool" (waiting connections) and "CPU". A slow trace in Tempo shows where the time went.

**Do:** a database query: find it in the trace, check its plan (`EXPLAIN ANALYZE`) against the indexes in the migrations. CPU at the limit: one instance is too small; add one or a bigger droplet. Valkey slow: see Platform health, "Valkey command latency"; requests carry on without Valkey, but slowly if it hangs (its timeout is 1 s).

## Disk

**Means:** the server's disk is over 80% full. The database lives on the same disk; PostgreSQL stops accepting writes when it fills.

**Check first:** `df -h` and `du -sh /var/lib/docker/* | sort -h`. WAL piling up because archiving is failing shows as a large `pg_wal` (see [No backup](#no-backup)).

**Do:** `docker system prune` for old images and stopped containers; rotate container logs (Docker's `max-size`); if `pg_wal` is large, fix archiving first and the WAL clears itself. Otherwise resize the droplet's volume.

## Database

Two alerts share this section.

**DatabasePoolBusy means:** more than 80% of the control plane's connection pool in use for 5 minutes: requests are about to wait for a connection.

**DatabaseUnreachable means:** readiness's database check has failed for a minute on an instance; it takes no traffic until the database answers.

**Check first:** Platform health, "Database pool" and "Readiness". On the server: `docker logs --tail 100 <postgres>`, and `select state, count(*) from pg_stat_activity group by 1` for connections stuck `idle in transaction`.

**Do:**
- Unreachable: is the container running and the disk not full? Restart PostgreSQL if it crashed; if its data is damaged, restore (docs/runbooks/backups.md) and run `ops/backup/verify-ledger.sh` before reopening.
- Pool busy: a slow query or a burst. Find long-running queries (`select pid, now() - query_start, query from pg_stat_activity where state <> 'idle' order by 2 desc`), and end one with `pg_cancel_backend(pid)` only if it's safe. Raise the pool size only with the database's `max_connections` in mind.

## Certificates

**Means:** the agents' CA (root or intermediate, `hosts_ca_certificate_expiry_seconds`) or a certificate the control plane serves (`ssl_chain_expiry_seconds`, the agents' gRPC port) expires within 14 days. Once one expires, no agent can connect or enrol.

**Check first:** which one: the alert's `certificate` label (`root`, `intermediate`, or a bundle's certificate).

**Do:** follow docs/runbooks/certificate-authority.md: issue a new intermediate from the offline root, or a new server certificate, set the environment variables, and restart the control plane. Agents keep their own certificates (30 days, renewed after 20) and trust the root, so a new intermediate under the same root changes nothing for them.

## Sessions failing

**Means:** more than 20% of the sessions that ended in the last hour failed, with at least 5 ended.

**Check first:** Marketplace, "Failed and stopped, by reason". The reason is the code in `api/endings.json`. One code dominating says what kind: `start_timeout` or `image_pull_failed` is hosts or the image registry; `outputs_upload_failed` or `storage_failed` is storage; `job_failed` or `oom_killed` alone is usually renters' own code.

**Do:**
- Storage codes: R2's status, and readiness's `storage` component.
- One host behind most failures: `/admin/` → Hosts, suspend it (`suspend-host`, docs/runbooks/hosts.md) while you find out.
- All hosts, since an agent release: see [Releases](#releases).
- Renters' own failures: nothing to fix on our side; watch it doesn't come from one template.

## Email failing

**Means:** the mail provider refused more than 5 emails in 15 minutes. Messages stay in the app and the events are retried, so mail goes out once it works.

**Check first:** the control plane's log, `The mail server didn't take the email`, for the provider's reply; the provider's dashboard (Resend's Logs).

**Do:** an authentication error: `MAIL_USERNAME` and `MAIL_PASSWORD` (Resend's API key) were rotated or revoked. A sending limit: Resend's free plan stops at 100 emails a day (docs/runbooks/email.md); move to a paid plan. A domain error: the DNS records in docs/runbooks/email.md.

## Agents dropped

**Means:** more than half the connected agents disconnected within 5 minutes, from at least 4. One home connection dropping is that host's business; half the fleet at once is ours: the control plane, its network or the load balancer.

**Check first:** Fleet, "Agents connected" by instance (one instance's agents, or all?), and "Streams closed, by reason". The control plane's log around the drop.

**Do:** one instance: restart it; agents reconnect to another within seconds. All instances: the load balancer's TCP pass-through on 443, the server certificate (see [Certificates](#certificates)), or a deployment that changed the gRPC port. Running jobs keep their leases for 2 minutes; after that they checkpoint and stop, and resume when their host returns.

## Releases

**Means:** of the hosts that reported on an agent version, more than 10% rolled it back: the version fails its own self-test or its 5-minute trial on real machines.

**Check first:** Fleet, "Update results by version", and the admin console's update results for that version (`hosts.update_results.detail` says why each rolled back).

**Do:** stop the rollout at once: publish the manifest again with `--rollout 0` for that version (`scripts/release/publish.sh --manifest-only`, ADR 0034). Hosts that rolled back stay on the old version and are never offered that one again. Fix, then release a new version.

## No backup

**Means:** no full backup of the database has finished in 26 hours (one runs nightly), WAL archiving is more than 10 minutes behind, or the control plane can't read the backup bucket. Point-in-time recovery depends on all three.

**Check first:** Platform health, "Last full backup" and "WAL archiving behind". On the server: `systemctl status idleo-backup.timer` and `journalctl -u idleo-backup` for the nightly backup; `docker logs <postgres> | grep -i archive` for archiving, which logs each failed `wal-g wal-push`.

**Do:** follow docs/runbooks/backups.md, "When a backup fails": the usual causes are the storage key (rotated or without write access), the bucket's name, or a full disk. Once fixed, take a full backup by hand (`ops/backup/backup-now.sh`) and watch the alert clear.

## Browser errors

**Means:** more than 10 JavaScript errors in 15 minutes from one release of the app or the website: something it shipped breaks for visitors.

**Check first:** Loki, `{service_name="control-plane"} |= "JavaScript error"`: the message, the source file and line (resolved from the build's source maps) and the route. One route or all? Since the release in the alert?

**Do:** a new release: roll the web app or website back to the previous build, then fix. An old release still in someone's browser tab: it ends when they reload; nothing to do unless it's many.

## Slow page

**Means:** a route's 75th-percentile largest contentful paint from real visits has been above 2.5 s over a day, from at least 20 visits, on phones or laptops. A digest, not a page.

**Check first:** Platform health, "Real visitors": since a release, or always? TTFB high too means the server or the network; TTFB fine means the page itself.

**Do:** measure the route on Lighthouse's mobile profile (`E2E_LIGHTHOUSE_MEASURE=simulate scripts/e2e/agent-online.sh --web`, web/README.md "Measuring Lighthouse") and compare with the budgets in CLAUDE.md. Never loosen a budget without Nikki's say.
