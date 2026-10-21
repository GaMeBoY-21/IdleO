# 0036. Operations: telemetry, alerts, backups, real email and real-user speed

- **Status:** Accepted
- **Date:** 2026-10-20

## Context

Before anyone real uses IdleO, three things must hold (docs/prompts/ops-foundation.md): a failure reaches us within
minutes, not when a user complains; the database (every balance and every earning) can be restored to within minutes of a
loss; and our email lands in inboxes. The plan's observability section (docs/plan/technical-design.md "Observability and
operations") names the signals and the alerts; its data stores table promises point-in-time recovery with under 5 minutes
of possible loss and backups kept 30 days. Page speed was measured only in tests, never on real visits. All of it has to
be built and proved on a Mac, at no cost for the pilot, with the paid or hosted parts Nikki's to switch on later.

## Decision

**One pipeline: OpenTelemetry, through OTLP.** The control plane uses Spring Boot 4.1's own OpenTelemetry support
(`spring-boot-starter-opentelemetry`): Micrometer's OTLP registry for meters, Micrometer Tracing's OpenTelemetry bridge
for traces, and OpenTelemetry's Logback appender for logs, which Spring Boot leaves to the application
(`OpenTelemetryLogs` attaches it right after logging starts; the appender is published as `2.28.0-alpha`, the build on
the SDK Spring Boot manages, 1.62.0). Not the OpenTelemetry Java agent: Spring's support is already on the classpath, needs
no bytecode rewriting of a virtual-thread application at startup, and reaches the same endpoint. Every signal carries the
release, the environment and the instance. Meters go once a minute; every timer a percentile is read from has a few fixed
buckets, never Micrometer's 69, to stay inside Grafana Cloud's free 10,000 series. Console logs stay ECS JSON with the
trace id. No personal data or secret is ever logged: `LogScan` reads the whole test suite's log for email addresses,
tokens, keys, signed links and renters' file names, and the build fails on a finding.

**Business meters live in the module that owns the fact:** hosts (by state and online, links, self-tests, update results),
agentgateway (connected, heartbeat age), sessions (started, ended by state and reason, time to a GPU and to the first log
line, resumes), billing (paise by what caused it, account and direction, from `Ledger` itself; holds), notifications
(emails sent, failed, bounced, complained, suppressed). Counters move only once their transaction commits (`AfterCommit`).
Figures read from the database (hosts, update results, holds, event publications) are the same on every instance, and
dashboards take their max. The agent sends nothing new to anyone: its health arrives on its stream.

**Development:** one `grafana/otel-lgtm` container and an Alertmanager in compose.yaml's `observability` profile, which
`./start.sh` starts; alerts go to Mailpit. **Production:** any OTLP endpoint over HTTPS; Grafana Cloud's free tier by
default (docs/runbooks/monitoring.md, with its confirmed limits). The prod profile refuses to start without it.

**Alerts as Prometheus rule files** (`ops/alerts/`), the one format both development's Prometheus and Grafana Cloud's
ruler (`mimirtool`) take unchanged, each unit-tested with `promtool test rules`, each with a one-line summary and a section
of docs/runbooks/alerts.md. Rules read only the labels Grafana Cloud keeps (`job`, `instance`). An outside uptime check
(UptimeRobot's free plan) covers the server being gone, which our own alerts can't see.

**Readiness** checks the database, the sessions' object storage and the agents' CA. **Valkey is not in readiness**,
although the brief asked for it: CLAUDE.md's rule is that Valkey holds only what may expire and is never in readiness,
and the application already runs without it; its latency is a meter and a dashboard panel instead.

**Backups with WAL-G** in the PostgreSQL image we run (`ops/postgres/`: the tests' image pinned by digest, WAL-G 3.0.9 by
checksum): every WAL segment archived (at least once a minute), a full backup nightly, 30 days kept, to R2 (MinIO
locally). Encrypted with OpenPGP, WAL-G's built-in asymmetric choice: the server holds only the public half, so it can
write backups and never read them; the private half is offline. (WAL-G's libsodium option is symmetric: the server would
hold the key that reads every backup.) A new volume is initialised without archiving, so storage that isn't there yet
never holds the database's first start. The control plane watches the backups itself (`BackupWatch`: the newest full
backup by storage's own time, WAL waiting to be archived, archiving on) rather than trusting the job that makes them.
`ops/backup/restore.sh` restores into a new database, never over the live one; `verify-ledger.sh` checks the ledger's
rules before reopening; `drill.sh` proves both on a fresh stack, by per-table checksums at a moment T.

**Email stays SMTP**, Resend by default. Each email has a plain-text part, a `Message-ID` named by its notification (the
same on a retry), and `List-Unsubscribe` pointing at the person's choices for the kinds they can switch off. The
provider's bounce and complaint webhook (`POST /v1/email-events/resend`, behind `EmailEvents`) is verified by its Svix
signature, refused unsigned, and keeps only the address's SHA-256: a hard bounce stops all email to it and Account says
"We couldn't email you: check your address"; a complaint stops what can be switched off.

**Real-user speed**: the app and the website report Core Web Vitals and JavaScript errors with `web-vitals`, loaded after
the first paint, to `POST /v1/vitals` (no sign-in, `sendBeacon` as plain text, limited per address, size-capped): the
route, never the path's ids or its query; the metric; phone or laptop; the release; no user, no address stored. The
website sends nothing under Do Not Track or Global Privacy Control. Source maps are made by every build and never served:
the control plane reads them (`vitals.source-maps`) to name an error's file and line.

## Alternatives considered

- **A third-party error service** (Sentry and the like): a second pipeline, and visitors' errors sent to another company.
  The brief rules it out for now; errors are logs and meters in the one pipeline.
- **Grafana-managed alert rules** instead of Prometheus rules: their provisioning file isn't what Grafana Cloud's API
  takes, and they can't be unit-tested with `promtool`.
- **DigitalOcean's managed PostgreSQL** (the plan's choice, docs/plan/execution-plan.md "Budget and costs"): it gives
  point-in-time recovery itself, but no WAL archive we hold or can encrypt to our own key. Open question: whether
  production runs our own PostgreSQL with this image, or the managed one with its own backups plus a nightly logical copy to
  R2. The brief asked for WAL-G in our image; the deployment round decides where the database lives.
- **One-click unsubscribe (RFC 8058)**: needs a signed token per person and kind and an endpoint that changes choices
  without a sign-in. Our emails are transactional, and Gmail's and Yahoo's one-click rule is for bulk senders; a link to
  Account is honest and simple. Revisit if a provider asks for it.

## Consequences

- Grafana Cloud's free tier is 10,000 series, 50 GB of logs, 50 GB of traces, 14 days kept: a new meter must have bounded
  tags (codes, routes, versions), never an id or a path.
- `LogScan` will fail the build on a log line with personal data; the fix is never to log it, not to widen the scan.
- The restore drill takes about five minutes a run and needs Docker; check-all runs it once (`--no-drill`).
- Every public page now loads one small script (vitals), where before only Pricing and Earn loaded any.
- The database user needs `pg_monitor` for the backup watch; deployment.md says so.
- A new person's first notification may go out before their provider's verdict on their address; a bounce only stops
  what follows.
