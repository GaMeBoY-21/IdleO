# Kubernetes

The server side runs from signed images, installed by one Helm chart (`deploy/helm/idleo`) on k3s (ADR 0045). On the Mac the same chart runs in a k3d cluster with `values-local.yaml`. This runbook covers that cluster; the server's own steps come with it.

Every command below expects the cluster's kubeconfig, which the scripts keep apart from `~/.kube/config`:

```sh
export KUBECONFIG=private/k8s/kubeconfig
```

## Up

```sh
scripts/k8s/up.sh
```

It installs k3d, kubectl and helm with Homebrew if they're missing, and says so. It stops `./start.sh`'s stack first. The two fit in Docker Desktop's 7.75 GiB together only while both idle: 3.9 GiB measured, with the cluster about 2.3 GiB of it. The chart's limits alone are about 6 GiB with the development services, so a busy cluster would starve the stack. Then it does six things:

1. Creates the cluster: k3s pinned by digest, one server node and a registry, `k3d-idleo-registry` on `localhost:5050`.
2. Installs cert-manager.
3. Builds the four images from the working tree and pushes them to the registry. Each is tagged with the commit, plus the time when the tree has changes, so a rebuild always rolls out.
4. Makes the local Secrets (`scripts/k8s/secrets.sh`).
5. Installs or upgrades the chart.
6. Prints the addresses.

Run it again after any change: the cluster stays, the images are rebuilt from cache, and the chart is upgraded (the migration Job runs first).

| What | Address |
| --- | --- |
| Website | https://idleo.localhost:8443/ |
| App | https://app.idleo.localhost:8443/ |
| API | https://api.idleo.localhost:8443/ |
| Agents (gRPC, mutual TLS) | localhost:19443 |
| Sign-in (development) | http://signin.idleo.localhost:18099/idleo-local |
| Storage (development) | http://storage.idleo.localhost:19100 |
| Mail (development) | http://localhost:18025 |

`*.localhost` is this machine. The HTTPS certificates come from a CA made once, in `private/k8s/ca/ca.pem`, which survives `down.sh`. To open the pages without a warning, trust it once in Keychain Access: drag `ca.pem` into the login keychain, open it, and set "When using this certificate" to Always Trust. Or pass `--cacert private/k8s/ca/ca.pem` to curl.

An enrolled agent on this machine connects to `localhost:19443` with `private/dev-ca/root.pem`, as with `./start.sh`, since the cluster uses the same development CA.

## Proof

```sh
scripts/k8s/smoke.sh
```

It checks, in order:

1. The website and the app load over HTTPS with their headers, and HTTP redirects.
2. Readiness is UP.
3. The migration Job ran as `idleo_migration`, and `idleo_app` can't create a table.
4. A made-up X-Forwarded-For is ignored.
5. A fake-GPU agent on this machine enrols, connects through the agents' load balancer and shows online.
6. A booking runs to COMPLETED.
7. A rolling restart of the control plane answers every request, and the agent is back within its backoff.

Logs: `private/k8s/logs/`. The `k8s` workflow runs `up.sh` and `smoke.sh` on a runner, on changes to `deploy/`, a Dockerfile, `scripts/k8s/` or the images workflow, and weekly.

## Status, memory and down

```sh
scripts/k8s/status.sh     # pods, memory by pod and node, and the cluster's containers as Docker counts them
scripts/k8s/down.sh       # delete the cluster and its registry, with the database in it
```

The chart's requests and limits are sized for one 8 GB node: the sums are in `deploy/helm/idleo/values.yaml`. After `down.sh`, start `./start.sh` again if you want the normal stack.

## Logs

The images write their logs to standard output, as JSON from the control plane:

```sh
kubectl -n idleo logs deploy/idleo-control-plane --tail=100 -f
kubectl -n idleo logs deploy/idleo-control-plane --previous         # the run before a restart
kubectl -n idleo logs -l batch.kubernetes.io/job-name=idleo-migrate --tail=-1    # every migration attempt
kubectl -n idleo get events --sort-by=.lastTimestamp | tail -30
```

## A shell for debugging

The images have no shell: the control plane's base is distroless. Attach an ephemeral container that shares the pod's processes and network instead:

```sh
kubectl -n idleo debug -it deploy/idleo-control-plane --image=busybox:1.37 --target=control-plane -- sh
```

From there, `ps`, `wget -qO- localhost:8080/actuator/health/readiness` and `/proc/1/root/app/` reach the running container. The ephemeral container stays in the pod's spec until the pod is replaced, so roll the deployment afterwards (`kubectl -n idleo rollout restart deploy/idleo-control-plane`). The database has a shell of its own:

```sh
kubectl -n idleo exec -it idleo-postgres-0 -- psql -U postgres -d controlplane
```

## Redeploying one image

`up.sh` rebuilds and upgrades everything, from cache, in about a minute. To roll out one image you built yourself, push it under a new tag, then set it on the release:

```sh
tag=$(git rev-parse --short=12 HEAD)-$(date +%s)
docker build -f control-plane/Dockerfile -t localhost:5050/idleo-control-plane:$tag .
docker push localhost:5050/idleo-control-plane:$tag
kubectl -n idleo set image deploy/idleo-control-plane control-plane=k3d-idleo-registry:5050/idleo-control-plane:$tag
kubectl -n idleo rollout status deploy/idleo-control-plane
```

This skips the migration Job, so use it only when the change has no migration. The next `up.sh` puts the chart's tag back.

## Operator commands

Each command is a one-off pod with the control plane's image, settings and database user, rendered from the installed release (`templates/operator-command.yaml`). It prints its result and is deleted:

```sh
OPERATOR=nikki scripts/k8s/ops.sh grant-credits --user <id> --paise 50000 --reason "'Pilot credit'" --key pilot-1
OPERATOR=nikki scripts/k8s/ops.sh activate-host --host <id> --reason "'Checked by hand'"
```

## The two database users

The database's first start makes them (`ops/postgres/initdb/10-idleo-users.sh`), with the passwords in `private/k8s/database.env`:

- `idleo_migration` owns the database. Only the migration Job, a Helm hook, logs in as it.
- `idleo_app` is the control plane's login, in the role `idleo_application`. It owns nothing, has no DDL, and has only SELECT and INSERT on the append-only tables.

A migration that adds a table grants `idleo_application` what that table needs, in the same migration: `ApplicationRoleTests` fails otherwise. Check by hand:

```sh
kubectl -n idleo exec idleo-postgres-0 -- psql -U postgres -d controlplane -c '\dp billing.ledger_entries'
```

## Images and signatures

`.github/workflows/images.yml` publishes `ghcr.io/gameboy-21/idleo-<name>:<commit>` from main. Each image is signed with cosign keylessly and carries a CycloneDX bill of materials and build provenance. Check one before trusting it:

```sh
scripts/images/verify.sh ghcr.io/gameboy-21/idleo-control-plane:<commit>
```
