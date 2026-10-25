# Backups

The database holds every balance and every earning. It is backed up continuously, and a restore is rehearsed every month (ADR 0036).

## What is backed up, and how often

| What | How | How often | Kept |
| --- | --- | --- | --- |
| PostgreSQL, whole | A full backup (`wal-g backup-push`) | Nightly at 02:00 India time (`ops/backup/systemd/idleo-backup.timer`) | 30 days |
| PostgreSQL, every change | Each WAL segment as it fills, and at least every 30 seconds (`archive_timeout = 30`) while anything is written | Continuously | 30 days, as far back as the oldest full backup kept |
| Object storage (uploads, checkpoints, outputs, logs, releases) | Nothing: it is R2's, which keeps its own copies | | As the lifecycle rules say (technical design, "Data stores") |
| Valkey | **Not backed up.** It holds only what may expire: presence, request counters and hints (ADR 0011). Lost, it fills again within a minute; nothing is lost that matters | | |

Together the full backups and the WAL allow **point-in-time recovery**: the database as it was at any second of the last 30 days, up to about 30 seconds before a loss.

## Where it lives

- **The software:** `ops/postgres/`, the PostgreSQL image we run in development and production: the tests' PostgreSQL pinned by digest, plus WAL-G pinned by version (3.0.9) and checksum. `archive-wal` is PostgreSQL's `archive_command`; `backup-full` takes a full backup and deletes what's older than 30 days.
- **The backups:** S3-compatible storage: R2 in production (a bucket of its own, `idleo-backups`, never the sessions' bucket), MinIO locally. Under the prefix, `basebackups_005/` holds the full backups and `wal_005/` the WAL.
- **Encryption:** everything is encrypted with the backup key's public half (OpenPGP, WAL-G's `WALG_PGP_KEY_PATH`) before it leaves the server. The server holds only the public half: it can write backups but never read them. The private half is offline (below), and is needed only to restore.

The database's container needs, from the server's environment (never in a file in the repository):

| Variable | What |
| --- | --- |
| `WALG_S3_PREFIX` | `s3://idleo-backups/production` |
| `AWS_ENDPOINT` | R2's S3 endpoint, `https://<account id>.r2.cloudflarestorage.com` |
| `AWS_REGION` | `auto` |
| `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` | An R2 API token with **Object Read & Write** on `idleo-backups` only |
| `WALG_PGP_KEY_PATH` | The public half's file, mounted read-only, for example `/run/backup.pub.asc` |

The control plane watches the backups with a key that may only read them (`BACKUPS_STORAGE_ENDPOINT`, `_BUCKET`, `_PREFIX`, `_REGION`, `_ACCESS_KEY_ID`, `_SECRET_ACCESS_KEY`; an R2 token with **Object Read only**), and the prod profile refuses to start without them. It needs `pg_monitor` to see the archiver (docs/runbooks/deployment.md). The no-backup alert (docs/runbooks/alerts.md) reads what it sees.

## The backup key

The development key is made by `ops/backup/dev-key.sh` into the gitignored `private/backup-dev/`. Production's is made once, the same way as the release key: on a machine you trust, never the server.

1. On that machine, with Docker: `docker run --rm -it -v "$PWD/backup-key:/out" --entrypoint bash postgres:18.6` and in it `gpg --quick-generate-key "IdleO production backups <backups@your-domain>" rsa4096 encrypt never`, giving it a long passphrase.
2. Export both halves: `gpg --armor --export > /out/backup.pub.asc` and `gpg --armor --export-secret-keys > /out/backup.key.asc`.
3. Put `backup.pub.asc` on the server for the database's container. It's public; it can only encrypt.
4. Keep `backup.key.asc` and its passphrase **offline**, in two places: a password manager, and an encrypted USB drive kept away from the computer. Delete every other copy. Without it no backup can ever be read, and with it anyone holding the backups can read them.
5. To restore, `restore.sh --key backup.key.asc` with `WALG_PGP_KEY_PASSPHRASE` set in the shell for that one command.

Changing the key: make a new one, give the server its public half, restart the database's container, and take a full backup at once. Keep the old private half until 30 days of backups made with it have been deleted.

## Restoring

Restore into a **new** database, never over the live one. `ops/backup/restore.sh` does it in a container and volume of its own, and never archives, so it can't write into the backups it reads.

```sh
export WALG_S3_PREFIX=s3://idleo-backups/production AWS_ENDPOINT=https://<account>.r2.cloudflarestorage.com \
  AWS_REGION=auto AWS_ACCESS_KEY_ID=<read key> AWS_SECRET_ACCESS_KEY=<its secret>
WALG_PGP_KEY_PASSPHRASE='…' ops/backup/restore.sh --key /path/to/backup.key.asc --to 2026-10-20T14:05:00+05:30
# or --to latest: everything archived
```

It picks the newest full backup finished before the target, fetches it, replays the WAL to the target and promotes. It prints the container, its port and how long it took.

**It then erases again everyone on the erased list** (ADR 0037): `ops/backup/reapply-erasures.sh`, the control plane's `reapply-erasures` operator command pointed at the restored database. The list is `privacy.erased_users` and, outside the database, the `erasures/` objects in the sessions' bucket, so someone erased after the moment restored to is erased again too. In production give it the jar and load the server's environment first, as for any operator command: `restore.sh … --jar /srv/idleo/control-plane.jar`. A restore that can't do this fails, and its database must not be used until it has. `--no-reapply` leaves the database as the backup had it, only to compare it with the moment restored to (the drill does, then runs it). Our backups keep an erased person for up to `legal.retention.backups` (30 days, LEGAL(confirm)); the privacy notice says so.

**Then, before anyone uses it, run the money check:**

```sh
ops/backup/verify-ledger.sh --container idleo-restore-<name>
```

It checks that every ledger transaction balances, all debits equal all credits, no wallet is negative or holds more than its balance, every wallet equals its ledger entries, every wallet's reserved amount equals its active holds, every hold equals its recorded changes, and no host's earnings are below zero. Exit 0 means reopen; anything else means don't, and find out why first.

Reopening on the restored database:

1. Stop the control plane (every instance), so nothing writes to the old database.
2. Point `DATABASE_URL` at the restored database (or move its volume to the database server), then start the database container with archiving (the image archives whenever `WALG_S3_PREFIX` is set), and take a full backup at once: the restored database is on a new timeline, and its first backup makes it restorable again.
3. Start the control plane. Agents reconnect and their running sessions are reconciled (ADR 0016); sessions that ran after the restore point are what was lost.
4. Run `reconcile-wallets` (docs/runbooks/granting-credits.md): 0 mismatches.

## The drill

`ops/backup/drill.sh` proves all of this on a fresh stack: real activity (an account with credit, a host with a fake GPU, sessions charged and the host earning), a full backup, more activity, a moment T taken with every table locked and each table's checksum recorded, more activity, then the database lost (killed, its disk deleted). It restores to T and checks every table matches T's checksums (every ledger entry, reservation, session and audit row up to T, and nothing after T), runs the money check, and checks the money check fails on a wallet changed behind the ledger's back. Then it restores to the last archived WAL and measures what was lost against a ticker that commits a row every second.

```sh
ops/backup/drill.sh            # one run, about 5 minutes
ops/backup/drill.sh --runs 3   # three, each on a fresh stack
```

It stops its fake Razorpay and RazorpayX on exit, Ctrl-C, terminate and hang-up (a trap), and also any left by an earlier drill that was killed outright, so no port stays held; `--keep` is the only way to leave the stack up. `scripts/check-all.sh` runs it once (`--no-drill` leaves it out). The loss comes a random 0 to 59 seconds after T's WAL was archived, as a real one would land anywhere in archiving's cycle.

Measured on the development Mac, 2026-10-21, with `archive_timeout = 30`, three runs in a row, each on a fresh stack (63 tables, about 35 MB of data):

| Run | Loss came | Data lost | Restore to T | Full backup | WAL archived |
| --- | --- | --- | --- | --- | --- |
| 1 | 40 s after T's WAL was archived | 6.8 s | 2.7 s | 6.5 MB | 0.5 MB |
| 2 | 34 s after | 1.0 s | 2.7 s | 6.5 MB | 0.5 MB |
| 3 | 7 s after | 4.3 s | 2.7 s | 6.5 MB | 0.5 MB |

Every run restored to T with every table's checksum equal to T's, the session booked after T absent, the money check passing, and the money check failing on a changed wallet. Two more runs the same day, in a batch stopped by a parsing bug since fixed, lost 24.0 and 22.7 s. With `archive_timeout = 60` (2026-10-20) the three runs lost 16.9, 7.8 and 22.3 s.

**What a loss costs:** the seconds since the last archived WAL segment, at most about 30 seconds (`archive_timeout = 30`) plus the time to upload one segment: well inside the technical design's 5 minutes. **A restore** takes about 3 s at today's size; it grows with the data and the WAL since the last full backup, so rehearse it monthly and note the time.

**Rehearse in production every month**, on the first working day: restore last night's backup into a new database on a spare droplet (`restore.sh --to latest`), run `verify-ledger.sh`, note the restore time and the newest row's time, and delete the droplet. Write the numbers in the operations log. Also restore once whenever the backup key or WAL-G changes.

## When a backup fails

The no-backup alert fires when no full backup has finished in 26 hours, WAL archiving is more than 10 minutes behind, or the control plane can't read the backups.

- **Nightly backup failed:** `journalctl -u idleo-backup --since yesterday`. Run it by hand to see the error: `ops/backup/backup-now.sh --container idleo-postgres`.
- **Archiving behind:** `docker logs idleo-postgres 2>&1 | grep -i archive`. PostgreSQL keeps every segment until it's archived, so nothing is lost while it's behind, but `pg_wal` grows and fills the disk; fix it before the disk alert.
- **Causes, in order of likelihood:** the R2 token was revoked or rotated (`AWS_ACCESS_KEY_ID`), the bucket's name or the prefix changed, the public key file isn't mounted, the disk is full, R2 is down (its status page).
- **The control plane can't read them:** its read key (`BACKUPS_STORAGE_*`), or the database user lacks `pg_monitor`, or `archive_mode` is off (the database's container started without `WALG_S3_PREFIX`).

After the fix, take a full backup by hand and watch the alert clear within two minutes.
