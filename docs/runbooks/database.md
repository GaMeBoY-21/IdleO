# The database

IdleO's database is a CloudNativePG Cluster, `idleo-database` in namespace `idleo` (ADR 0047): a primary and a standby streaming from it, made by the operator in `cnpg-system` from the chart's `templates/database.yaml`. Everything that writes (the control planes, the migration Job, operator commands) reaches the primary through the Service `idleo-database-rw`, whichever instance that is. backups.md covers backups and restores.

```sh
export KUBECONFIG=<the cluster's>     # the Mac's: private/k8s/kubeconfig
kubectl -n idleo get cluster idleo-database          # instances, ready, status, primary
kubectl -n idleo describe cluster idleo-database     # conditions, the last events
primary="$(kubectl -n idleo get cluster idleo-database -o jsonpath='{.status.currentPrimary}')"
kubectl -n idleo exec -it "$primary" -c postgres -- psql -d controlplane   # the superuser, over the socket
```

There is no superuser login over the network (`enableSuperuserAccess: false`); `kubectl exec` is the way in, and it's recorded in the cluster's audit log.

## A failover

**What happens.** The operator watches the primary. When it stops answering (its process died, its pod was deleted, its disk failed, its node went), the operator promotes the standby, moves `idleo-database-rw` to it, and makes the old primary a standby again once it's back (a new pod, rebuilt from the new primary if its data can't follow). Commits wait for the standby while it's there (synchronous, `dataDurability: preferred`), so the standby has every committed write: a failover loses none. The control planes' connections to the old primary break; Hikari drops them and opens new ones through the Service, with nobody's help.

**What renters see.** Writes are refused for the seconds it takes (requests that need the database answer 503 meanwhile); reads that need it too. Measured by `scripts/k8s/failover-test.sh` on the Mac (its numbers are in ADR 0047). Agents stay connected: their streams are to the control planes, not the database.

**What to check, afterwards** (DatabaseFailover says it happened, once a day):

1. `kubectl -n idleo get cluster idleo-database`: 2 instances, 2 ready, "Cluster in healthy state", and which is primary now.
2. Why the old primary went: `kubectl -n idleo get events --sort-by=.lastTimestamp | grep idleo-database`, and its last log (`kubectl -n idleo logs <old primary> -c postgres --previous`). An OOM kill means its memory limit (`database.resources`) is too small for what it's asked; a full disk means `database.storage`.
3. The standby is streaming again: `kubectl -n idleo exec "$primary" -c postgres -- psql -Atc "select application_name, state, sync_state from pg_stat_replication"` shows the other instance `streaming`, `quorum` or `sync`.
4. Archiving carries on from the new primary (backups.md): `kubectl -n idleo get cluster idleo-database -o jsonpath='{.status.conditions[?(@.type=="ContinuousArchiving")].status}'` is `True`.
5. The money check: `ops/backup/verify-ledger.sh --cluster idleo-database` passes.

**When it doesn't fail over:** with the standby gone too (DatabaseStandbyBehind fired first), there's nothing to promote; the operator restarts the primary where it was. If neither instance can start, restore (backups.md).

## Promoting the standby by hand

For planned work on the primary's node or disk, move the primary first, so the switch is clean (the old primary shuts down after the standby has every WAL record) rather than a failover:

```sh
kubectl -n idleo patch cluster idleo-database --subresource=status --type=merge \
  -p "{\"status\": {\"targetPrimary\": \"<the standby's pod>\"}}"
```

(or, with the `kubectl cnpg` plugin, `kubectl cnpg promote idleo-database <the standby's pod>`). The operator does a switchover: writes pause for a few seconds, the standby becomes primary, and the old primary follows it. Check as after a failover. Changing a setting or the image does the same by itself (`primaryUpdateMethod: switchover`).

Never promote by running `pg_ctl promote` inside a pod: the operator doesn't know, and the two instances end up on different timelines.

## Adding a database user

Users are the Cluster's managed roles (`spec.managed.roles` in `templates/database.yaml`), not made by hand: what's written there is kept, and a role removed from the list is left alone (mark it `ensure: absent` to drop it). Each login's password is a `kubernetes.io/basic-auth` Secret (`username`, `password`), a SOPS file like every Secret (ADR 0046).

1. Make the Secret, encrypted for the environment's key: `kubectl create secret generic idleo-database-<who> --type=kubernetes.io/basic-auth --from-literal=username=<role> --from-literal=password="$(openssl rand -hex 24)" --dry-run=client -o yaml`, then `sops encrypt` it into `deploy/clusters/<env>/secrets/idleo/` and list it in that folder's kustomization (locally, `scripts/k8s/secrets.sh` makes the local ones).
2. Add the role under `managed.roles`, with the least it needs: `login: true`, the roles it's in (`inRoles`, for example `pg_read_all_data` for a read-only reporting login), and `passwordSecret: {name: idleo-database-<who>}`. Never `superuser`, `createrole` or `createdb`.
3. Its table privileges, if it needs more than a predefined role gives, come from a Flyway migration (granted by the migration user, as V61 grants `idleo_application`), never by hand.
4. If it connects from a new pod, that pod needs the label `idleo.in/database-client: "true"`, or the network policy keeps it out.
5. A pull request, as for any change. `ApplicationRoleTests` (and `scripts/k8s/smoke.sh`, against the cluster) still check the application user has no more than it should.

The two users the service runs as are fixed (ADR 0045): `idleo_migration` owns the database and only the migration Job logs in as it; `idleo_app`, a member of `idleo_application` and `pg_monitor`, is the control plane's. Changing either's password is changing its Secret: the operator changes the role's to match, and the control plane picks it up when its pods restart (`kubectl -n idleo rollout restart deployment idleo-control-plane-primary`).

## When WAL archiving stops

DatabaseArchivingFailing: the primary can't send WAL segments to the backups (backups.md). Nothing committed is lost meanwhile: PostgreSQL keeps every segment until it's archived, and the standby has them. But point-in-time recovery stops at the last segment archived, and kept segments fill the primary's disk.

1. What it says: `kubectl -n idleo get cluster idleo-database -o jsonpath='{.status.conditions[?(@.type=="ContinuousArchiving")]}'` and the plugin's log on the primary: `kubectl -n idleo logs "$primary" -c plugin-barman-cloud --tail=50`.
2. The usual causes: the storage key (`idleo-backup-storage`) revoked, rotated or without write access; the bucket or its address wrong (`r2-backups`); the storage down; the plugin itself not running (`kubectl -n cnpg-system get pods`).
3. How long there is: the space left on the primary's volume against the WAL written an hour (`kubectl -n idleo exec "$primary" -c postgres -- df -h /var/lib/postgresql/data`). If it will fill before the fix, grow the volume (`database.storage`, which the local-path provisioner can't; on the server, the disk) rather than turn archiving off.
4. Once fixed, the waiting segments go in order; watch "WAL waiting to be archived" on Platform health fall to 0 and the alert clear. Then take a base backup by hand (backups.md, "When a backup fails"), so recovery doesn't depend on the gap's WAL.

Never turn archiving off to free the disk: every segment it would have kept is a second that can't be restored.
