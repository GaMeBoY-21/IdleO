# Kubernetes

The server side runs from signed images, installed by one Helm chart (`deploy/helm/idleo`) on k3s (ADR 0045), and Flux keeps each cluster as git says (ADR 0046). On the Mac the same chart runs in a k3d cluster with `values-local.yaml`, either installed by plain Helm for quick work, or under Flux as staging and production run it (`--gitops`). This runbook covers that cluster; how a change reaches staging and production is deploying.md.

Every command below expects the cluster's kubeconfig, which the scripts keep apart from `~/.kube/config`:

```sh
export KUBECONFIG=private/k8s/kubeconfig
```

## Up

```sh
scripts/k8s/up.sh            # plain Helm, for quick work
scripts/k8s/up.sh --gitops   # under Flux, as staging and production: push your branch first
```

It needs the local age key, `private/k8s/age/local.agekey`, which the committed local Secrets are encrypted for (see "The local Secrets" below). It installs k3d, kubectl and helm with Homebrew if they're missing, and says so; sops it asks you to install (`brew install sops age`). It stops `./start.sh`'s stack first. The two fit in Docker Desktop's 7.75 GiB together only while both idle: 3.9 GiB measured, with the cluster about 2.3 GiB of it. The chart's limits alone are about 6 GiB with the development services, so a busy cluster would starve the stack. Then it does six things:

1. Creates the cluster: k3s pinned by digest, one server node and a registry, `k3d-idleo-registry` on `localhost:5050`.
2. Installs cert-manager.
3. Builds the four images from the working tree and pushes them to the registry. Each is tagged with the commit, plus the time when the tree has changes, so a rebuild always rolls out.
4. Applies the local Secrets, decrypted from their SOPS files, and the development ConfigMaps (`scripts/k8s/secrets.sh`).
5. Installs or upgrades the chart.
6. Prints the addresses.

Run it again after any change: the cluster stays, the images are rebuilt from cache, and the chart is upgraded (the migration Job runs first).

**Under Flux (`--gitops`).** The same cluster and images, then, in place of steps 2, 4 and 5:

- The chart is packaged from the tree and pushed beside the images. A chart built on this machine has no signature from the images workflow, so this one source isn't verified; `canary-test.sh` has the cluster verify staging's real chart instead.
- Flux v2.9.6 is applied from `deploy/flux`. It is given the local age key (`sops-age`) and two ConfigMaps in `flux-system`: `local-source` (the branch and commit to follow) and `local-release` (this build's tag and digests).
- Flux then reads `deploy/clusters/local` from GitHub at this commit, so the branch must be pushed and `deploy/` committed; `up.sh` refuses otherwise. It installs cert-manager, Flagger and Prometheus, decrypts the Secrets, and installs the release with the control plane as Flagger's primary (`idleo-control-plane-primary`).

Run it again after a change and what changed is released as a canary. Switching between plain Helm and Flux needs `down.sh` first; `up.sh` says so. Watch Flux with `kubectl -n flux-system get gitrepositories,kustomizations,helmreleases -A` and the canary with `kubectl -n idleo get canary`.

| What | Address |
| --- | --- |
| Website | https://idleo.localhost:8443/ |
| App | https://app.idleo.localhost:8443/ |
| API | https://api.idleo.localhost:8443/ |
| Agents (gRPC, mutual TLS) | localhost:19443 |
| Sign-in (development) | http://signin.idleo.localhost:18099/idleo-local |
| Storage (development) | http://storage.idleo.localhost:19100 |
| Mail (development) | http://localhost:18025 |

`*.localhost` is this machine. The HTTPS certificates come from the local CA, which is one of the committed local Secrets; `up.sh` writes its certificate to `private/k8s/ca/ca.pem`.

**Trusting the CA is safe,** because it can vouch for this machine only:

- its name constraints permit `localhost` and the names under it (every `*.localhost` is this machine) and the loopback addresses;
- its key usage permits TLS servers only.

A browser refuses any certificate it signs for another site, even with its key in hand: `smoke.sh` checks that one for `www.example.com` fails with "permitted subtree violation". See for yourself with `openssl x509 -in private/k8s/ca/ca.pem -noout -text` (X509v3 Name Constraints).

To trust it for TLS in your login keychain (macOS asks for your password; Chrome and Safari use the keychain):

```sh
security add-trusted-cert -r trustRoot -p ssl -k ~/Library/Keychains/login.keychain-db private/k8s/ca/ca.pem
```

To stop trusting it, delete "IdleO local cluster CA" in Keychain Access, or run `security remove-trusted-cert private/k8s/ca/ca.pem`. Without trusting it, pass `--cacert private/k8s/ca/ca.pem` to curl.

An enrolled agent on this machine connects to `localhost:19443` trusting `private/k8s/agent-ca.pem`, the development CA's root from the cluster's Secrets, which `up.sh` writes.

## The local Secrets

The cluster's Secrets are committed in `deploy/clusters/local/secrets/idleo/` as SOPS files, encrypted for the local age key (ADR 0046): development's three database passwords, the control plane's development CA and keys, and the local CA. Nothing in them is used outside a cluster on a laptop.

- **The key:** `private/k8s/age/local.agekey`, made by `scripts/deploy/age-key.sh local`. The same key is the repository secret `SOPS_AGE_KEY_LOCAL`, for the k8s and gitops workflows. On a new machine, copy the key from the old one (or from the password manager).
- **Read one:** `SOPS_AGE_KEY_FILE=private/k8s/age/local.agekey sops decrypt deploy/clusters/local/secrets/idleo/database.yaml`.
- **Change them:** `scripts/k8s/secrets.sh --make` encrypts this machine's development secrets again: `private/k8s/database.env`, `private/dev-ca` and `private/k8s/ca`, each made first if it's missing. Commit the result. New database passwords need a new database: `down.sh`, then `up.sh`.
- **A new local key:** `scripts/deploy/age-key.sh local --rotate` encrypts the files for the new key. Commit them and `.sops.yaml`, then put the new key in the repository secret: `gh secret set SOPS_AGE_KEY_LOCAL < private/k8s/age/local.agekey`, and `gh secret set SOPS_AGE_KEY_LOCAL --app dependabot` the same way.

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

Logs: `private/k8s/logs/`. The `k8s` workflow runs `up.sh` and `smoke.sh` on a runner, on changes to `deploy/`, a Dockerfile, `scripts/k8s/` or the images workflow, and weekly. `smoke.sh` works under Flux too, where the rolling restart is of the primary.

## Canaries

```sh
scripts/k8s/canary-test.sh
```

It runs `up.sh --gitops` and then proves five things:

1. The cluster's Flux verifies staging's real chart (signed by the images workflow on main) with the same verify block staging and production use, and refuses it for another workflow's identity.
2. A good release goes through 10, 25, 50 and 100% to promotion, after its migration Job, with every renter's request answered.
3. A bad release (`web.faults.server-error-percent=30`, development only) is rolled back by Flagger at its first failed check, the renters' requests return to the good version, and CanaryFailed's expression is true.
4. A migration that drops a column without a contract line is refused.
5. An agent built from the previous release runs a booking through the canary.

It prints how long each release took. It takes about 40 minutes and leaves the cluster on the good release, with the bad one failed. The `gitops` workflow runs it on pull requests that touch `deploy/`, and weekly.

## Status, memory and down

```sh
scripts/k8s/status.sh     # pods, memory by pod and node, and the cluster's containers as Docker counts them
scripts/k8s/down.sh       # delete the cluster and its registry, with the database in it
```

Under Flux the cluster also runs Flux's four controllers, Flagger, its load tester and Prometheus, and during a canary a second control plane. `status.sh` shows each one's memory.

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

With plain Helm only (under Flux, run `up.sh --gitops` again, which releases it as a canary). `up.sh` rebuilds and upgrades everything, from cache, in about a minute. To roll out one image you built yourself, push it under a new tag, then set it on the release:

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

The database's first start makes them (`ops/postgres/initdb/10-idleo-users.sh`), with the passwords from the local Secrets (a copy is in `private/k8s/database.env`):

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

The chart is published beside them as `ghcr.io/gameboy-21/charts/idleo:0.1.0-<commit>`, signed the same way; `cosign verify` checks it against the workflow's identity (`scripts/deploy/verify-release.sh` does both for a whole release).
