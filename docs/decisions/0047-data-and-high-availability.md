# 0047. Data and high availability

- **Status:** Accepted
- **Date:** 2026-10-31

## Context

Until now the cluster ran one PostgreSQL pod (ADR 0045's StatefulSet, `ops/postgres/`'s image with WAL-G) and one control plane. When either died, IdleO was down until it came back: no bookings, no commands to agents, no minutes metered. The plan was always more (ADR 0045, "What comes later"): CloudNativePG for the database, and the control plane as several copies. The code was written for several from the start (ADR 0011, ADR 0015: presence and hints in Valkey, the command queue in Postgres, sweeps that claim rows), but nothing had ever run two.

Two things are wanted. The database should run with a standby that takes over by itself, and backups that restore to any second. The control plane should run as two copies, and losing one should cost renters and hosts nothing: no lost booking, no lost command, no double charge. Both must be proved, not assumed.

One honest limit shapes everything below: the server is one 8 GB node. Two of anything on one node protects against a crashed process, a bad pod, a rolling update or a corrupt data directory. It doesn't protect against the node itself dying.

## Decision

**CloudNativePG runs the database.** The operator (1.30.1) and its Barman Cloud plugin (v0.15.1) are in `deploy/infrastructure/base/cloudnative-pg.yaml`, managed by Flux like the rest of the infrastructure. Their charts are pinned by digest and their signatures checked against CloudNativePG's release workflow, and their images are pinned by digest. Plain Helm (`scripts/k8s/up.sh`) installs the same charts with the same values, read from that file. The chart's `templates/database.yaml` holds a `Cluster`, `idleo-database`, of two instances on PostgreSQL 18.6 (CloudNativePG's minimal image, pinned): a primary and a streaming standby.

- **Failover.** The operator watches the primary, and when it fails it promotes the standby and moves the `idleo-database-rw` Service to it. It then rebuilds the old primary as the new standby. Commits wait for the standby while it's there (`synchronous: {method: any, number: 1, dataDurability: preferred}`), so a failover loses no committed write. With the standby gone, the primary carries on alone rather than refuse writes, and DatabaseStandbyBehind pages. A changed setting or image restarts the standby first, then switches over (`primaryUpdateMethod: switchover`).
- **Users, as S1 made them** (ADR 0045). The initdb bootstrap makes the database with `idleo_migration` as its owner. The managed roles keep `idleo_migration`, `idleo_application` (no login) and `idleo_app` (a member of `idleo_application` and `pg_monitor`), with passwords from two `kubernetes.io/basic-auth` Secrets, SOPS files like every other. The migration Job, the control plane and operator commands all reach the primary through `idleo-database-rw`. No superuser logs in over the network (`enableSuperuserAccess: false`). `ApplicationRoleTests` can now run against any database (`roles.url`), and `scripts/k8s/smoke.sh` runs it against the cluster's, through a port-forward: it passes.
- **No read replicas.** Every read goes to the primary. The standby is for failover only. A read path would mean deciding, query by query, what may be seconds stale, and at today's load the primary isn't the bottleneck. Add one when a measured read load says so.
- **A network policy** lets only the control plane, the migration Job and operator commands reach port 5432, besides the cluster's own instances. It lets the operator reach the instance manager, and the in-cluster Prometheus reach the meters.

**Backups through the Barman Cloud plugin**, CloudNativePG's current method (the in-tree `barmanObjectStore` is deprecated). An `ObjectStore`, `idleo-backups`, archives every WAL segment, gzip-compressed, at least every 30 seconds while anything is written (`archive_timeout = 30s`, ADR 0036's promise kept). A `ScheduledBackup` takes a base backup daily at 03:00 India time, and one at once when a cluster is new. Both are kept 30 days. In staging and production they go to R2. Flux substitutes the bucket and its address from the Secret `r2-backups`, and the keys are the Secret `idleo-backup-storage`. Both Secrets come with the server (S5). Locally they go to the development MinIO, which makes its bucket as it starts.

**A point-in-time restore is a new Cluster.** `ops/backup/restore.sh --to <time|latest>` makes `idleo-restore-<name>`, bootstrapped from the live Cluster's object store at the target. It has no WAL archiver of its own, so it can't write into the backups it came from. It then runs `reapply-erasures` as an operator pod against the restored Cluster (`operator.database`), so a restore never brings back someone who was erased (ADR 0037). `ops/backup/verify-ledger.sh --cluster` runs the money check on it.

**WAL-G leaves the chart, not ./start.sh.** The chart and the images workflow no longer build `ops/postgres/`, and staging's and production's releases no longer pin it. ./start.sh's stack and the tests keep that image, WAL-G and its OpenPGP-encrypted development backups exactly as before. The control plane's backup watch and the NoBackup alert stay too, for that stack, and `scripts/ops/alert-drill.sh` still proves the alert. The droplet-era systemd timer and `reapply-erasures.sh` are gone.

**Monitoring.** The in-cluster Prometheus scrapes each instance's meters (port 9187, job `database`). Its meters include the timeline, from a custom query, which moves on at every failover. In staging and production the remote write sends Grafana Cloud the series the alerts and panels use, as it already sends Flagger's. Four alerts are in `ops/alerts/database.yaml`, each with a promtool test and a section in docs/runbooks/alerts.md:

- **DatabaseStandbyBehind:** the standby is more than 30 s behind, or none is streaming. It pages.
- **DatabaseArchivingFailing:** the last archive attempt failed, or more than 5 segments are waiting. It pages.
- **DatabaseNoBaseBackup:** no base backup has finished in 26 hours. It pages.
- **DatabaseFailover:** the timeline moved in the last 15 minutes. It goes in the daily digest.

Platform health's two backup panels read CloudNativePG's meters.

**Two control planes.** The chart runs the control plane at two replicas. An autoscaler (HPA) keeps it between 2 and 2 by CPU. Under Flagger, the Canary's `autoscalerRef` gives the primary those bounds (`primaryScalerReplicas`), and the canary one copy. A PodDisruptionBudget (`minAvailable: 1`) never lets a drain or an eviction take the last one. Topology spread and a preferred anti-affinity put the copies on different nodes when there are several. Both select either version's pods by the label Flagger leaves alone (`idleo.in/control-plane`). Three copies don't fit the memory budget at a canary's peak (below), so the maximum stays 2 until there are more nodes; with three nodes, raising `controlPlane.autoscaling.maxReplicas` is the only change.

### The audit

Every scheduled job, durable event listener and piece of one instance's memory, each in exactly one category.

- **Safe as is:** rows claimed with `FOR UPDATE SKIP LOCKED`, or work that is idempotent by a unique key.
- **Per instance by design:** about this instance's own streams, caches or gauges. Gauges are read with `max`/`min` across instances, never `sum`.
- **Must run once:** under a PostgreSQL advisory lock. That is `OneAtATime` (`pg_try_advisory_lock` by name, held on one connection while the work runs on others) for work spanning transactions or calls out, or `pg_try_advisory_xact_lock` for a job that is one transaction. One mechanism; `RetentionJobs` and `Erasure` moved onto `OneAtATime` from their own copies of it. `jobs.one_at_a_time{job, outcome}` counts each turn.
- **Shared state needed:** moved to Postgres or Valkey.

Money jobs are marked ₹.

| Scheduled job (36) | Category | Why |
| --- | --- | --- |
| `MeteringJobs.tick` ₹ | Safe as is | Each session locked `FOR UPDATE SKIP LOCKED`; minutes unique by (session, minute); ledger postings idempotent by `txn_id` |
| `TopUps.abandonStale` ₹ | Safe as is | One idempotent `UPDATE … WHERE status = 'pending'` |
| `PaymentReconciliation.nightly` ₹ | Safe as is | The day is claimed by a unique key; the other instance's insert fails and it steps aside |
| `PayoutReconciliation.nightly` ₹ | Safe as is | The same: the day claimed by a unique key |
| `PayoutRuns.weekly` ₹ | Safe as is | The payday's run claimed by a unique key |
| `PayoutService.resend` ₹ | Safe as is | RazorpayX's `X-Payout-Idempotency` is the payout's id; the answer is applied under the payout's row lock, transitions state-guarded |
| `PayoutService.poll` ₹ | Safe as is | Fetches only; applied under the row lock, state-guarded |
| `WalletReconciliation.nightly` ₹ | Must run once | Already: `pg_try_advisory_xact_lock` |
| `FreeCreditExpiry.daily` ₹ | Must run once | Already: `pg_try_advisory_xact_lock` |
| `CreditRefunds.send` ₹ | Must run once, **fixed** | Razorpay takes no idempotency key for a refund: two instances sending one part could refund it twice (its receipt only finds an earlier refund after a refusal). Now `OneAtATime` "billing:refund-parts", on the schedule and after an approval alike |
| `Invoices.monthly` ₹ | Must run once, **fixed** | A unique index already stops a second usage invoice for a renter's month, but two instances raced renter by renter and the loser threw. Now `OneAtATime` |
| `BalanceCheck.beforePayday` ₹ | Must run once, **fixed** | One check of RazorpayX's balance a payday, not one an instance |
| `PayoutMethods.sweep` ₹ | Must run once, **fixed** | A UPI check's answer was applied without a row lock: two instances could each announce the change |
| `EventPublicationRetry.retry` | Must run once, **fixed** | See "Event publications" below |
| `Ratings.daily` | Must run once, **fixed** | Delete-all then insert in one transaction: two at once would collide on every host's row |
| `RetentionJobs.runAll` | Must run once | Already, a lock per rule; now `OneAtATime` |
| `Erasure.sweep` | Must run once | Already, a lock per request; now `OneAtATime` with the request as key |
| `UsagePartitions.daily` | Must run once | Already: `pg_try_advisory_xact_lock` |
| `Exports.sweep` | Safe as is | The next export claimed with `SKIP LOCKED` |
| `BookingJobs.retryRematches` | Safe as is | The matcher is version-guarded: a second run changes nothing |
| `BookingJobs.expireOffers` | Safe as is | `SKIP LOCKED` |
| `BlocklistRechecks.scheduled` | Safe as is | `SKIP LOCKED` (`ScanStore.toJudge`) |
| `ScannerQueue.sweep` | Safe as is | `SKIP LOCKED` |
| `HostLosses.sweep` | Safe as is | `SKIP LOCKED` |
| `CredentialRefreshes.queueDue` | Safe as is | `SKIP LOCKED` |
| `ProvisioningTimeouts.sweep` | Safe as is | `SKIP LOCKED`, and the transition is state-guarded |
| `SessionProgress.checkFinishing` | Safe as is | `SKIP LOCKED` |
| `HostScoreService.nightly` | Safe as is | An upsert of scores computed from the same rows |
| `CommandDelivery.sweep` | Per instance | Delivers to this instance's streams; expiring old commands is an idempotent update |
| `LeaseRenewals.renew` | Per instance | Renews the sessions whose agents stream here |
| `AgentReleaseFeed.refresh` | Per instance | Each instance's copy of the signed release feed |
| `HostMetrics.refresh` | Per instance | Fleet gauges; dashboards take `max` |
| `BackupWatch.check` | Per instance | ./start.sh's backup gauges; the alert takes `max`/`min` |
| `PaymentReconciliation.count` ₹ | Per instance | A gauge read from the table; the alert takes `max` |
| `PayoutReconciliation.count` ₹ | Per instance | The same |
| `BalanceCheck.gauge` ₹ | Per instance | The same |

So: 17 safe as is, 8 per instance, 11 must run once (5 already were, 6 fixed), none needing shared state. Of the money jobs, 7 were safe, 2 already locked and 4 fixed.

| Durable event listener | Category | Why |
| --- | --- | --- |
| `NotificationListeners` (8 events) | Safe as is | Each message's dedupe key is the event it answers |
| `MeteringJobs` (session started, ended, attempt lost) ₹ | Safe as is | `on conflict do nothing`, `coalesce`, waivers unique by minute |
| `BookingJobs` (session requested, re-match, cancelled) | Safe as is | The matcher is version-guarded |
| `EndedSessions`, `HostScoreRefresh`, `ImageBlockedSessions` | Safe as is | `on conflict do nothing`; an upsert; a state-guarded cancel |
| `HostStatusSender` (standing, earnings changed) | Safe as is | `AgentCommands.replace` keeps only the latest HostStatus |
| `SelfTestSender` | **Fixed** | Each delivery queued another RunSelfTest, so an event delivered twice ran the GPU test twice. Now `AgentCommands.enqueueOnce`, with a message id made from the run's id (`message_id` is unique) |
| `RemovedHosts`, `HostStatusSender` and `BlocklistRechecks` on in-memory events | Per instance | They act on this instance's streams or start a sweep. A removed host's stream on the other instance is closed at its next once-a-minute "seen" check (`recordSeen` refuses a removed host), and its certificate is refused on reconnect: bounded, not immediate |

| One instance's memory | Category | Why |
| --- | --- | --- |
| Request limits (`RequestLimits`), renter limits (`SessionLimits`) | Shared already | Counted in Valkey, fixed windows; the renter's in Postgres under a per-renter lock. Proved across both |
| Agent streams (`ConnectedAgents`), `InstanceId` | Per instance | A stream lives on one instance; presence in Valkey names it, and commands reach it by its channel |
| Live session streams (`SessionStreams`) | Per instance | Each is woken by Valkey's `sessions:changes` hint from any instance, and re-reads on its own timer. `sessions.streams.max-per-renter` (3) is per instance: it protects that instance's threads, so a renter may hold 3 on each |
| The presence retry (`SessionMatcher.presenceRetries`) | Per instance | If the instance dies, the shared re-match loop (`BookingJobs`, from the database) picks the booking up |
| Presence after Valkey restarts | **Fixed** | Valkey keeps nothing on disk; a restarted one has no presence, so every host read offline until it heartbeated. Now presence is unknown for one TTL (15 s) after Valkey comes back empty (`agentgateway:presence-since`, which an instance's start writes as 0) |
| `PublicAvailability`, `SourceMaps`, `BrowserVitals`, `StorageHealthIndicator` caches; retention and reconciliation "last success" gauges | Per instance | Caches of shared data, and gauges read with `min`/`max` |
| Timers (`AgentStreamServices`, `SessionStreams`) | Per instance | Each serves this instance's streams |

**Event publications.** Spring Modulith 2.1 claims a publication before resubmitting it: it marks it RESUBMITTED unless it already is. But two instances working through the incomplete list at once could each claim the same one in turn, the second right after the first's listener had completed it, since COMPLETED isn't RESUBMITTED either. That would deliver it twice. `EventPublicationRetry` now runs one instance at a time. What's left is the window every single instance always had: a listener slower than its retry backoff (30 s, doubling). The one listener that wasn't safe to run twice (`SelfTestSender`) is fixed, so even that window delivers nothing twice that matters. Publications are never republished on restart (Modulith's default, unchanged).

**Proof.** `TwoInstancesTests` runs two whole control planes in one JVM against one PostgreSQL and one Valkey. It shows four things:

- Of two instances ticking at the same moment, one runs a must-run-once job and the other skips. Each of the jobs fixed above skips its turn while another instance holds its lock.
- A booking made on A reaches an agent streaming to B, and a renter watching on A sees what the agent reports to B within the hint, not the re-read.
- A request limit holds across both.
- After Valkey loses its data, presence on both reads unknown, never offline, until the agent heartbeats.

`scripts/k8s/failover-test.sh` proves it on the Mac's cluster under Flux, as production runs, with renters' reads and writes through the Gateway the whole time and two fake-GPU agents connected. The `failover` workflow runs it weekly. Measured on 2026-10-31:

| Step | What renters and hosts saw |
| --- | --- |
| A control-plane pod killed hard (`--grace-period=0`), holding both agents' streams | Agents back on the other in 1.9 s and 1.9 s; the self-test queued while they reconnected was delivered; the running booking COMPLETED, billed once; 0 of 121 reads and 0 of 121 writes failed |
| The database's primary pod killed hard | The standby promoted in 19.8 s; writes refused for 19.8 s; 12 of 115 reads and 12 of 115 writes failed; the booking COMPLETED, billed once; the old primary back as the standby; the money check passes |
| Valkey restarted | See the final run below |
| Both control planes evicted one at a time | See the final run below |
| A point-in-time restore drill | See the final run below |

**Connections, measured.** At rest the primary holds 20 connections from `idleo_app` (two control planes at Hikari's default of 10 each) and the operator's and exporter's few, 31 backends in all against `max_connections = 100`. The peak adds 10 for a canary's control plane, 10 for the migration Job and 10 for an operator command (each a JVM with a pool), about 61. That leaves room, so there is no PgBouncer. **The trigger:** add it when the planned peak (control planes, plus one canary, times their pool, plus 30 for the Job, a command and CloudNativePG) passes 80, 80% of `max_connections`, which is more than 4 control planes at today's pool. Raising `max_connections` instead costs each backend's memory on the 1 GiB instance. When it comes, it must be session pooling for the control plane, or the migration Job and the advisory locks bypass it. Transaction pooling breaks session-level advisory locks (`OneAtATime`: the lock and its unlock could land on different server connections), Flyway's own session lock, and server-side prepared statements unless PgBouncer's prepared-statement support is on.

**Memory, measured** on the Mac's k3d node under Flux (one node, like the server): the numbers and the decision are in "Consequences".

## Alternatives considered

- **Keeping the StatefulSet and adding a standby by hand** (repmgr, Patroni). Patroni is proven, but it's another moving part with its own consensus store (etcd, or the Kubernetes API through RBAC we'd write). Its backups would still be ours to wire. CloudNativePG is a CNCF project built for exactly this on Kubernetes: failover through the API server, backups, PITR bootstrap, managed roles, meters, all declared in one resource the chart owns.
- **Keeping WAL-G in the cluster beside CloudNativePG.** It encrypts on the way out with our OpenPGP key, which the plugin can't (it offers only the store's server-side encryption). But CloudNativePG's failover, PITR bootstrap and the backup state on the Cluster all assume its own archiver; running two archivers would race on the same WAL. We accept R2's encryption at rest and a bucket-scoped token in place of client-side encryption, and say so in backups.md. The token is now the key to guard. Open question: whether a later plugin release's client-side encryption is worth turning on.
- **Synchronous replication required (`dataDurability: required`).** No commit would ever be acknowledged without the standby. But with the standby down, every write would stop: losing one instance would be an outage, the opposite of the point. `preferred` loses nothing while both are up, and keeps writing when one isn't.
- **A managed database** (ADR 0036 decided against it, 2026-10-21): still the server's job.
- **A distributed lock library (ShedLock) for must-run-once jobs.** It's a second mechanism beside the advisory locks the code already used for exactly this; the rule is one.
- **Leader election for all scheduled work** (one active instance runs every job). Simpler to state, but it makes per-instance work (delivering to this instance's streams, renewing their leases) impossible, and a dead leader's jobs wait for the election. Locking each job that needs it, and claiming rows for the rest, keeps every instance useful.
- **The HPA up to 3 now.** It doesn't fit 8 GB at a canary's peak (Consequences).

## Consequences

- A crashed process, a bad pod, a rolling update, a failed disk under one instance or a corrupt data directory costs at most seconds, and nothing committed. Measured: no renter request failed when a control plane died or was drained, and a database failover refused writes for about 20 s.
- **What one node can't protect against:** the node itself. If the server dies, both control planes, both database instances and their disks go together, and IdleO is down until a new server is built and the database restored from R2 (backups.md). That costs up to the WAL not yet archived, the RPO, about 30 s at most. The RTO is the restore plus a cluster to restore into. This is the same as before for a lost server; what changed is everything smaller than that.
- **What three nodes add, with only counts changing:** the topology spread and anti-affinity (already written, `preferred`) put the two control planes and the two database instances on different nodes, so a node's death becomes a failover, not an outage. `controlPlane.autoscaling.maxReplicas` can go to 3. The database could go to 3 instances (`database.instances`) with quorum commit (`number: 1` of 2 standbys). The anti-affinities could become `required`. local-path volumes would have to become network or replicated storage, so a rebuilt instance has somewhere to land.
- Every scheduled job and durable listener now has a recorded reason it's safe with several instances (the audit above). A new one must be put in a category in review; conventions.md says how.
- The restore is slower than WAL-G's on a laptop (about 50 s against 3 s), because it makes a new Cluster rather than start a container; it's the way production restores, so that's the number that counts.
- Backups are no longer encrypted with our key before they leave the server. R2 encrypts them at rest, and a token scoped to the one bucket guards them.
- The chart can't yet make `idleo-database` itself from the backups (a Cluster bootstrapped by recovery, with a new archive name so it doesn't write over the old one). Recovering rows from a restored Cluster works today. Open question: reopening the whole service on a restored database, after both instances are lost, is S5's to build and rehearse with the server.
- S5 must provide: the R2 bucket for backups, its token (`idleo-backup-storage`) and the Flux Secret with its address and name (`r2-backups`), the two users' Secrets (`idleo-database-migration`, `idleo-database-application`), and a first restore rehearsal on the server.
