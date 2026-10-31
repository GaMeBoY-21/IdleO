# Backups

The database holds every balance and every earning. It runs on CloudNativePG as a primary and a standby, and it is backed up continuously, to the second, with a restore rehearsed every month (ADR 0036, ADR 0047). database.md covers failover, the standby and the database's users.

## What is backed up, and how often

| What | How | How often | Kept |
| --- | --- | --- | --- |
| PostgreSQL, whole | A base backup, by the Barman Cloud plugin (the chart's ScheduledBackup `idleo-database-daily`) | Daily at 03:00 India time, and once as soon as a new cluster is ready | 30 days |
| PostgreSQL, every change | Each WAL segment as it fills, and at least every 30 seconds (`archive_timeout = 30s`) while anything is written | Continuously | 30 days, as far back as the oldest base backup kept |
| Object storage (uploads, checkpoints, outputs, logs, releases) | Nothing: it is R2's, which keeps its own copies | | As the lifecycle rules say |
| Valkey | **Not backed up.** It holds only what may expire: presence, request counters and hints (ADR 0011). Lost, it fills again within a minute; presence reads "unknown", never "offline", meanwhile | | |

Together the base backups and the WAL allow **point-in-time recovery**: the database as it was at any second of the last 30 days. Losing one instance costs nothing (the standby has every commit: replication is synchronous while it's there). Losing both and their disks (the server) costs the WAL not yet archived: at most about 30 seconds.

## Where it lives

- **The software:** CloudNativePG 1.30.1 and its Barman Cloud plugin v0.15.1, pinned in `deploy/infrastructure/base/cloudnative-pg.yaml`. The chart's `templates/database.yaml` holds the Cluster (its `plugins` entry makes the plugin the WAL archiver), the `ObjectStore` named `idleo-backups` (where, how compressed, 30 days kept) and the ScheduledBackup.
- **The backups:** S3-compatible storage. In staging and production, R2: the bucket and its address come from the Flux Secret `r2-backups` (`R2_BACKUPS_ENDPOINT`, `R2_BACKUPS_BUCKET`), under a folder per environment (`staging`, `production`), and the keys from the Secret `idleo-backup-storage` (`ACCESS_KEY_ID`, `ACCESS_SECRET_KEY`: an R2 API token with **Object Read & Write** on that bucket only). Locally, the development MinIO's `idleo-backups` bucket, folder `local`. Under the folder, `idleo-database/base/` holds the base backups and `idleo-database/wals/` the WAL, gzip-compressed.
- **Encryption:** R2 encrypts everything at rest, and only the bucket's own token can read it. The plugin can't encrypt before upload as WAL-G did with our OpenPGP key (ADR 0047 says why that was given up); the token is the key to guard.
- **./start.sh's stack** keeps WAL-G (`ops/postgres/`, ADR 0036) and its own MinIO bucket, encrypted with the development key (`ops/backup/dev-key.sh`); `ops/backup/backup-now.sh` takes a full backup there, and the control plane's backup watch (the NoBackup alert, alerts.md) reads it. Nothing in the cluster uses it.

What it says about itself: `kubectl -n idleo get cluster,backups` (the cluster's `Continuous archiving` condition, each backup's phase) and the Platform health dashboard's "Last base backup" and "WAL waiting to be archived", from the database's own meters (ops/alerts/database.yaml alerts on them).

## Restoring

Restore into a **new** Cluster, never over the live one. `ops/backup/restore.sh` does it: a Cluster of its own (`idleo-restore-<name>`, one instance) bootstrapped from the live one's backups, the newest base backup before the target and then the WAL replayed to it. It has no WAL archiver, so it can't write into the backups it reads.

```sh
export KUBECONFIG=<the cluster's>            # the Mac's cluster's, private/k8s/kubeconfig, when unset
ops/backup/restore.sh --to 2026-10-20T14:05:00+05:30 --name before-mistake
# or --to latest: everything archived
```

It waits until the new database has replayed to the target, promoted itself and answers, and prints the Cluster, the base backup it started from and how long it took (the RTO).

**It then erases again everyone on the erased list** (ADR 0037): the control plane's `reapply-erasures` operator command, run as a pod against the restored Cluster (`IDLEO_DATABASE=idleo-restore-<name> scripts/k8s/ops.sh reapply-erasures`; on a server, render `templates/operator-command.yaml` with `operator.database` set). The list is `privacy.erased_users` and, outside the database, the `erasures/` objects in the sessions' bucket, so someone erased after the moment restored to is erased again too. A restore that can't do this fails, and its database must not be used until it has. `--no-reapply` leaves the database as the backup had it, only to compare it with the moment restored to (the drill does). Our backups keep an erased person for up to `legal.retention.backups` (30 days, LEGAL(confirm)); the privacy notice says so.

**Then, before anyone uses it, run the money check:**

```sh
ops/backup/verify-ledger.sh --cluster idleo-restore-<name>
```

It checks that every ledger transaction balances, all debits equal all credits, no wallet is negative or holds more than its balance, every wallet equals its ledger entries, every wallet's reserved amount equals its active holds, every hold equals its recorded changes, no host's earnings are below zero, and the payout rules (ADR 0040). Exit 0 means reopen; anything else means don't, and find out why first.

**To get one table or a few rows back** (the usual case: something deleted by mistake), copy them from the restored Cluster into the live one by hand, as the migration user, then delete the restored Cluster: `kubectl -n idleo delete cluster idleo-restore-<name>`.

**To reopen on the restored database** (the live one's data is lost or wrong throughout, both instances gone): the chart can't yet make `idleo-database` itself from the backups, so this is not a step to improvise. Until it can (ADR 0047, "Open question"; with the server, S5), recover what's needed from the restored Cluster as above, and if the whole database is gone, keep the restored Cluster running, stop every writer (`flux suspend kustomization idleo`, then scale `idleo-control-plane-primary` to zero), and delete nothing: the restored Cluster is the data.

## The drill

`ops/backup/drill.sh` proves the restore on the Mac's cluster (after `scripts/k8s/up.sh`), against the live database: a person granted credit, a base backup taken now, a ticker committing a row every second with its WAL position, a marker row, then a moment T taken with every table locked and each table's checksum recorded. Then data is deleted on purpose (the marker and the person), and it restores to T into a new Cluster: every table must match T's checksums, the marker and the person must be back, the money check must pass, and must fail on a wallet changed behind the ledger's back. Its restore time is the RTO. Finally, at a random moment of the next minute, it measures what a loss of the whole database (both instances and their disks) would cost: the newest tick in the WAL archived by then, against that moment. That is the RPO, the most a restore to the latest point could replay.

```sh
ops/backup/drill.sh            # one run, about 2 minutes
ops/backup/drill.sh --runs 3
ops/backup/drill.sh --keep     # leave the restored Cluster up to look at
```

`scripts/k8s/failover-test.sh` runs it as its last step; `scripts/check-all.sh` runs it (the drill suite) after bringing the cluster up.

Measured on the development Mac, 2026-10-30, with `archive_timeout = 30s` (112 tables):

| Run | Loss came | Data lost (RPO) | Restore to T (RTO) | Base backup |
| --- | --- | --- | --- | --- |
| 1 | 23 s after T's WAL was archived | 20.1 s | 52.5 s | 4 s |

The restore's time is mostly CloudNativePG making the new Cluster (a recovery job, then the instance's own start), not the data: it is about 50 s at today's size and grows with the data and the WAL since the last base backup, so rehearse it monthly and note the time. ./start.sh's WAL-G restored the same in about 3 s (ADR 0036); the Cluster is the price of restoring the way production runs.

**What a loss costs:** losing an instance, nothing; losing the server, the seconds since the last archived WAL segment, at most about 30 seconds plus the time to upload one segment: well inside the technical design's 5 minutes.

**Rehearse in production every month**, on the first working day: `ops/backup/restore.sh --to latest --name rehearsal`, then `verify-ledger.sh --cluster idleo-restore-rehearsal`, note the restore time and the newest row's time, and delete the restored Cluster. On one 8 GB node it borrows 512 MiB for a few minutes; do it at a quiet hour. Write the numbers in the operations log. Also restore once whenever CloudNativePG or the plugin is upgraded.

## When a backup fails

Three alerts (ops/alerts/database.yaml, alerts.md): DatabaseArchivingFailing (WAL isn't reaching the backups), DatabaseNoBaseBackup (none finished in 26 hours) and, for the standby, DatabaseStandbyBehind. What to do when archiving stops is database.md, "When WAL archiving stops".

- **A base backup failed:** `kubectl -n idleo get backups` shows the failed one and its error; `kubectl -n idleo describe backup <name>` and the plugin's sidecar log (`kubectl -n idleo logs <primary> -c plugin-barman-cloud`) say why. Take one by hand once it's fixed: `kubectl -n idleo apply` a Backup with `method: plugin` (as `ops/backup/drill.sh` does), and watch its phase reach `completed`.
- **Causes, in order of likelihood:** the R2 token was revoked or rotated (`idleo-backup-storage`), the bucket's name or address changed (`r2-backups`), R2 is down (its status page), the plugin isn't running (`kubectl -n cnpg-system get pods`), or its certificate expired (cert-manager).

After the fix, watch the alert clear within a few minutes.
