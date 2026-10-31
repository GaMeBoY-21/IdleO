# Valkey's password

Valkey has one user, `idleo`, whose password is the Secret `idleo-valkey` (key `password`) in namespace `idleo`, a SOPS file under `deploy/clusters/<env>/secrets/idleo/valkey.yaml` (ADR 0048). The default user is off, so nothing gets in without it, and the commands that could wipe, reconfigure or sweep it (`FLUSHALL`, `FLUSHDB`, `CONFIG`, `DEBUG`, `KEYS`, `MODULE`, `ACL`, `MONITOR`, saving, replication, `SHUTDOWN`) are refused to that user too (`valkey.refusedCommands`); the control plane uses none of them. The list is written to memory as Valkey starts, never to disk.

The control plane and operator commands read the password from the same Secret (`VALKEY_PASSWORD`, in the Valkey URL as `redis://idleo:${VALKEY_PASSWORD}@idleo-valkey:6379`).

```sh
export KUBECONFIG=<the cluster's>     # the Mac's: private/k8s/kubeconfig
kubectl -n idleo exec idleo-valkey-0 -- valkey-cli --user idleo ping      # PONG (the pod has its own password)
```

## Rotating it

Valkey accepts the previous password too while the Secret has a `previous-password`, so the control planes can move over one at a time with no request failing. Valkey keeps nothing on disk (ADR 0011), so each of its restarts below costs what one always does: presence reads "unknown" for one heartbeat interval (ADR 0047), never "offline".

1. Make a new password: `openssl rand -hex 24`. Never paste it anywhere but the SOPS file.
2. Edit the Secret with both: `sops deploy/clusters/<env>/secrets/idleo/valkey.yaml`, set `previous-password` to the current `password`, and `password` to the new one (`stringData` is fine; SOPS encrypts it). Open the pull request; once it's merged Flux applies it.
3. Restart Valkey, so it accepts both: `kubectl -n idleo rollout restart statefulset/idleo-valkey`, then `kubectl -n idleo rollout status statefulset/idleo-valkey`.
4. Restart the control planes, so they use the new one: `kubectl -n idleo rollout restart deployment/idleo-control-plane-primary` (under Flagger; `deployment/idleo-control-plane` on plain Helm). One at a time, each ready before the next goes, as every rollout is.
5. Remove `previous-password` from the Secret in a second pull request, and restart Valkey once more (step 3): the old password stops working.
6. Check: `kubectl -n idleo exec idleo-valkey-0 -- valkey-cli --user idleo ping` answers PONG, and the control planes' readiness is UP.

On the Mac, `scripts/k8s/secrets.sh --make` makes a fresh one with every other local Secret (commit the result).

## If it leaked

Rotate it as above, but skip the previous password: set only the new `password`, restart Valkey and the control planes together, and accept the seconds the control planes can't reach Valkey (heartbeats count as not recorded; streams stay open). Then follow breach.md. The network policy still lets only the control plane and operator commands reach Valkey, so a leaked password alone doesn't reach it from anywhere else.
