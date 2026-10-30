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
- All routes, with "Readiness and storage" showing `db` at 0: see [Database](#database). Storage's failures are 503s with their own alert: see [Storage unreachable](#storage-unreachable).
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

## Storage unreachable

**Means:** the control plane hasn't reached the sessions' object storage (R2) for 5 minutes. It isn't part of readiness, so instances keep serving: sessions run on (their agents hold their own storage grants until those expire, within the hour), leases renew and money settles. But nothing can be uploaded, no output downloaded and no log read; those calls answer `storage-unavailable` (503), and the app says so in plain words. Checkpoints and outputs written meanwhile are found once storage is back.

**Check first:** Cloudflare's status page for R2; Platform health, "Readiness and storage"; the control plane's log, `Object storage can't be reached`.

**Do:** an R2 outage: nothing to do but wait, and say so to renters if it lasts. Our side: the storage key (`SESSIONS_STORAGE_ACCESS_KEY_ID`, `_SECRET_ACCESS_KEY`) rotated or revoked, or the bucket renamed (docs/runbooks/object-storage.md). Once it answers, the alert resolves within two minutes.

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

**Do:** measure the route on Lighthouse's mobile profile (`E2E_LIGHTHOUSE_MEASURE=simulate scripts/e2e/agent-online.sh --web`, web/README.md "Measuring Lighthouse") and compare with the budgets in CLAUDE.md. Never loosen a budget without the product owner's say.

## Events failing permanently

**Means:** an event between modules (ADR 0015) failed at every try: its listener threw 8 times over about two hours (EventPublicationRetry), and it's now left alone instead of being retried forever. Whatever that listener does hasn't happened: a charge settled, a host's score kept, an email sent.

**Check first:** which listener and why:

```sql
select id, listener_id, event_type, publication_date, completion_attempts, serialized_event
from modulith.event_publication
where completion_date is null and completion_attempts >= 8;
```

Then Loki, `{service_name="control-plane"} |= "Invocation of listener"` with the listener's name, for the exception each try logged.

**Do:** fix the cause: a deployment, a dependency, or data the listener didn't expect, which may need a code fix. Then hand the event back to its retries by setting `completion_attempts = 0` on its row; it's tried again within the next minute. An event that should never have been made (its subject is gone) can be deleted, once you've checked that nothing it would have done is still owed.

## Retention stopped

**Means:** one retention rule (the alert's `rule`) hasn't run on any instance for a day and a half. Each runs once a day and deletes what the personal data map (docs/reference/personal-data-map.md) says is kept only for a while: uploads, checkpoints, outputs, logs, exports, the security log and the rest. Nothing is lost while it's stopped; things are kept longer than we promise.

**Check first:** Loki, `{service_name="control-plane"} |= "Retention rule"`: a failing rule logs `Retention rule <name> failed` with the error each day. `retention_failures_total` by rule counts them. If there's no line at all, no instance has been up for long enough, or scheduling is off.

**Do:** fix what the error says (most often storage unreachable, docs/runbooks/object-storage.md, or a database timeout on a large first run). The rule catches up by itself at its next run, or restart one instance to run every rule two minutes after it starts.

## Privacy request due

**Means:** a ticket on the topic "A privacy request" or "A grievance" isn't resolved, and its due date is within 7 days or has passed. The due date is `legal.support.response-due` (30 days, LEGAL(confirm)) after it arrived; the law allows no more than 90.

**Check first:** the admin console's Tickets, which lists open tickets by due date, overdue first, highlighted. `support_tickets_overdue` says how many are past due.

**Do:** answer it. A request to see or download one's data: the ticket's "Start an export" (the person gets it by email). A request to delete: tell them Account, Your data, Delete my account, or what stops it (the ticket shows where their deletion stands); never skip the wait unless a legal order says so. A correction we can't make in the app (an email address): make it, and say so. Then resolve the ticket with the answer.

## Image check backlog

**Means:** a renter's own image (ADR 0042) has waited more than 30 minutes for its check. Renters see "Checking your image" and can't book it until the check completes: a check that doesn't finish is never a pass. The scanner (scanner/, its own container) is down, stuck on one check, out of disk, or behind a burst of submissions.

**Check first:** `docker ps` and `docker logs scanner` on the server: is it running, and what does its last "a check ended" line say? `images_checks_waiting` and `images_checks_ended_total` by `outcome` on the Marketplace dashboard: many waiting and none ending is a scanner that isn't working; a few ending slowly is a busy one. `df -h` on the scanner's scratch volume (it refuses a check it hasn't room for, and says so).

**Do:**
- Scanner down or stuck: `docker restart scanner`. Its claims lapse after `images.claim-lasts` and the checks are claimed again; after `images.check-attempts` tries a check ends as not checked, and the renter is told to check it again.
- Out of disk: a check's folder is deleted when it ends, so a full disk is another process's or a check still running; delete what isn't the scanner's.
- Just behind: let it catch up, or run a second scanner (each claims its own checks); the server's room is in ADR 0042.

## Abuse stops

**Means:** more than 3 sessions in an hour, across the fleet, were stopped by their host's runner for running software no renter may run on a host's GPU (ADR 0038): a build on the blocklist by its hash, or a second look-up of a mining pool's domain. Each one suspended its renter's account until an admin has looked at it. Many at once means an attack (someone with several accounts), or a bad entry on the blocklist stopping honest renters.

**Check first:** the admin console's Abuse, open items, newest first. Which signals? One kind from many different renters and many different hosts points at an entry in `agent/distro/blocklist.json` (or a learned build) (a domain that is not only a pool's, say). The same renter again and again, or one host's sessions only, points at a person or a machine. `trust_abuse_stops_total` by `kind` is on the Marketplace dashboard.

**Do:**
- A bad entry: clear every item it stopped (that reinstates the account and refunds the session), then take the entry out and ship a new distribution (docs/runbooks/abuse.md, "Changing the blocklist"). Until the runner updates, the same stops go on happening on laptops that haven't.
- An attack: confirm the items, ban the accounts (a ban is lifted only by clearing its item), and look at the host if every stop was on one machine.
- Say what happened to anyone who wrote in: docs/runbooks/abuse.md has the words for a renter and for a host.

## Payment mismatch

**Means:** the payment reconciliation (ADR 0039) found Razorpay and our ledger disagreeing about a payment or a refund, and nobody has closed the item. It runs every night at 03:15 India time for the day before, and when an admin presses Run in the console. Kinds: a payment or refund Razorpay has and we don't (`missing_ours`), one we have and Razorpay doesn't (`missing_theirs`), a different amount (`amount`), a different state (`status`), a captured payment no top-up was credited for (`captured_not_credited`; the reconciliation has credited it already if the payment checks out), and a payment authorised but not captured (`authorized_not_captured`; never credited).

**Check first:** the admin console's Money, Reconciliation: each open item with both sides' amounts and states and Razorpay's id. `billing_payments_reconciliation_open` is the count. Then Razorpay's dashboard for that id.

**Do:** docs/runbooks/payments.md, "Reconciliation", says what each kind needs. Once put right, the next run marks it fixed by itself; an item that needs nothing (an authorised payment Razorpay will refund by itself) is accepted with a reason.

## Webhook signatures failing

**Means:** more than 5 Razorpay webhooks, or Checkout answers, in 15 minutes carried a signature that isn't Razorpay's under our secret. Nothing was credited for them: a payment is credited only when its signature checks out, or when we fetched it from Razorpay ourselves.

**Check first:** `billing_payments_signature_failures_total` by `source` (`webhook` or `checkout`). Webhooks only, all at once: the webhook secret was changed in Razorpay's dashboard and not in `RAZORPAY_WEBHOOK_SECRET`, or the other way round. A trickle from many places: someone sending forged ones.

**Do:** a rotation: docs/runbooks/payments.md, "Rotating the webhook secret" (the previous secret is accepted until the time you set). Forged ones: nothing is at risk; note the source addresses from the load balancer's log. Payments still arrive by Checkout and the reconciliation in the meantime.

## Payments failing

**Means:** more than 30% of the payments in the last hour failed, once at least 10 were tried. Failures are counted by method.

**Check first:** `billing_topups_failed_total` by `method`. One method only (UPI, say) points at Razorpay or the banks: Razorpay's status page. Every method: our side, the Checkout script not loading (the Credit page's policy) or orders refused (`payments-unavailable` in the API's errors).

**Do:** Razorpay's side: wait, and tell renters on the status page. Ours: docs/runbooks/payments.md, "Payments failing".

## Chargeback opened

**Means:** a renter's bank disputed one of our payments (Razorpay's `payment.dispute.created`). The renter's bought credit, up to the disputed amount, is frozen until it ends. Evidence must reach Razorpay by the date the dispute gives, or it's lost.

**Check first:** the admin console's Money, Chargebacks: the dispute, its due date, the payment, and the sessions its credit paid for, each with its metering and log.

**Do:** docs/runbooks/payments.md, "Chargebacks": evidence is submitted in Razorpay's dashboard, never from here. Lost, the account is suspended pending review.

## Payout mismatch

**Means:** the payout reconciliation (ADR 0040) found RazorpayX and our payouts disagreeing, and nobody has closed the item. It runs every night at 03:45 India time for the day before, and when an admin presses Run in the console. Kinds: a payout RazorpayX made that isn't ours (`missing_ours`: someone paid from RazorpayX's dashboard), one we sent that RazorpayX doesn't have (`missing_theirs`), a different amount (`amount`), and one that ended differently (`status`). A payout ours was only behind on (a lost webhook) is moved on by the reconciliation itself and isn't an item.

**Check first:** the admin console's Payouts, Reconciliation: each open item with both sides' amounts and statuses and RazorpayX's id. `payouts_reconciliation_open` is the count. Then RazorpayX's dashboard for that payout.

**Do:** docs/runbooks/payouts.md, "Reconciliation", says what each kind needs. Never fix a payout by hand in the database: the ledger posting is what the money check reads. Once put right, the next run marks it fixed; an item that needs nothing (a test payout made from the dashboard) is accepted with a reason.

## Payouts failing

**Means:** more than 20% of the payouts that ended in the last 6 hours failed, were rejected or cancelled, once at least 5 ended. Each failed payout's money is already back in its host's earnings, and the next run tries again; nobody has lost money, but hosts are waiting.

**Check first:** `payouts_status_total` by `status`, and the console's Payouts filtered to failed: RazorpayX's reason on each (`status_reason`, from its status details). One reason everywhere (`imps_not_allowed`, a bank down) points at RazorpayX or the banks; `insufficient` or account errors point at our RazorpayX account.

**Do:** docs/runbooks/payouts.md, "When payouts fail". Pay one host again from the console (Retry) once the cause is fixed; the rest are paid by the next run.

## Payout balance low

**Means:** the day before payday, RazorpayX's available balance was less than the next run will pay. Payouts would queue at RazorpayX (`queue_if_low_balance`) until the account is funded, and hosts would wait.

**Check first:** the console's Payouts, the balance line: what's due, what's there, and when it was checked. RazorpayX's dashboard for the balance itself.

**Do:** fund the RazorpayX account from the company's current account before 10:00 India time on Monday (docs/runbooks/payouts.md, "Funding payouts"). The alert clears at the next check that finds enough, or once payday has passed.

## Payout reversed

**Means:** a host's bank sent a payout back after it left (`payout.reversed`). The money is in their earnings again, their UPI ID is marked suspect, and no payout goes to it until they check a UPI ID again; they were emailed.

**Check first:** the console's Payouts filtered to reversed: the host, RazorpayX's reason. Several at once on one bank points at the bank, not the hosts.

**Do:** nothing for one: the host checks their UPI ID and the next run pays them. Several: docs/runbooks/payouts.md, "Reversals".
