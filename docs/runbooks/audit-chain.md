# The audit log's chain and its off-site copy

The admin audit log (`identity.audit_log`, ADR 0051) is append-only and tamper-evident. A database trigger chains every row: its `hash` is the SHA-256 of the previous row's hash and its own canonical content (`identity.audit_row_content`). A job checks the whole chain every day at 01:30 India time, and any admin with `audit.read` can press **Check the chain now** on the Audit log screen. Each finished India day's rows, and the chain head after them, are written off site.

This is the admin console's log. For the Kubernetes API server's, see audit-log.md.

## When the chain is broken (the paging alert)

Treat it as an incident: someone may have changed the record of what admins did. Follow breach.md alongside this.

1. **Don't change anything in the database.** Note the time.
2. Read the result: the Audit log screen's **Latest check**, or:
   ```sql
   select checked_at, rows_read, head_seq, broken_seq, detail, trigger
   from identity.audit_chain_checks order by checked_at desc limit 5;
   ```
   `broken_seq` is the first row that doesn't add up, and `detail` says how:
   - "its hash is not what its content makes": that row was changed;
   - "a row before it is missing": rows were deleted;
   - "the newest rows are missing": the end of the log was removed.
3. Compare with the off-site copy of that day (below). The copy has the row as it was written. Its head says what the chain was at the end of that day.
4. Find who could do it: the app's database user has no `UPDATE` or `DELETE` on this table, so this took the migration user, a superuser or a restore. Check the Kubernetes audit log (audit-log.md) for shells into the database, and the database's own logs.
5. A restore to a time before the newest rows also shows here as "the newest rows are missing" only if the head survived it. After a planned restore, check once and record the result in the incident's notes.
6. **Never repair the chain in place.** The log stays as found. Record what happened in the incident, and keep the off-site copies for the affected days.

## When the check hasn't run (AuditChainUnchecked)

Find `audit-chain-check` in the control plane's logs. It runs under `OneAtATime`, so one instance does it. Press **Check the chain now** to confirm the chain, then fix what stopped the job.

## When a day has no copy (AuditCopyMissing)

The job tries every 15 minutes and catches up missed days in order, so a short outage fixes itself. If the alert stays:

- Look for `audit-offsite-copy` or "differs from the log" in the control plane's logs.
- **"differs from the log"** means the store holds other bytes under that day's key: treat it as a broken chain above, because either the store or the log has changed.
- Otherwise check `AUDIT_OFFSITE_*` and the token's expiry.

## The store (R2, with a bucket lock)

S5 makes it. In production it's a bucket of its own, with a lock so nobody can replace or delete a file for 8 years, `legal.retention.audit-log`:

```sh
wrangler r2 bucket create idleo-audit
# Bucket lock on the audit/ prefix: ops/backup/r2-audit-lock.json, through Cloudflare's API.
curl -X PUT "https://api.cloudflare.com/client/v4/accounts/$ACCOUNT/r2/buckets/idleo-audit/lock" \
  -H "Authorization: Bearer $CF_TOKEN" -H "Content-Type: application/json" \
  --data @ops/backup/r2-audit-lock.json
```

Then an R2 token with **Object Read & Write on that bucket only**, set as `AUDIT_OFFSITE_ENDPOINT`, `AUDIT_OFFSITE_BUCKET`, `AUDIT_OFFSITE_ACCESS_KEY_ID` and `AUDIT_OFFSITE_SECRET_ACCESS_KEY` in the control plane's SOPS Secret. The prod profile refuses to start without them.

A file is written once, with `If-None-Match: *`, and never replaced. R2 has no object versions, so the lock is what makes each day's file permanent.

## Reading a copy

`audit/<year>/<day>.ndjson.gz` holds one JSON row per line, in chain order. `audit/<year>/<day>.head.json` holds the day's row count, first and last `seq`, the head hash, and the SHA-256 of the rows file. Each `hash` is `sha256(prev_hash || content)`, and the first row of a day has as `prev_hash` the head of the day before, so the files join with no gaps.
