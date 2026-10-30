# Deploying

Nobody deploys by hand (ADR 0046). Flux keeps each cluster as its folder of `main` says (`deploy/clusters/<env>`), and every release is pinned by digest in that folder's `idleo/release.yaml`. A deploy is a pull request that changes that one file. This runbook covers how a change gets to staging and production, promoting, rolling back, a failed canary, and the secrets. The Mac's cluster is kubernetes.md.

## How a change reaches staging

1. A pull request merges to `main`.
2. The images workflow (`.github/workflows/images.yml`) builds, scans and publishes the four images and the chart for that commit, each signed with cosign keyless.
3. Its last job, "deploy to staging", writes the commit's digests into `deploy/clusters/staging/idleo/release.yaml` (`scripts/deploy/release.py write staging <commit>`). It then verifies every one with `scripts/deploy/verify-release.sh`: each image's signature, bill of materials and provenance, and the chart's signature.
4. It opens **"Deploy <short sha> to staging"** and turns on auto-merge. An older open staging pull request is closed as superseded.
5. The required checks run on it, started by the job itself, since GitHub doesn't start workflows for a pull request opened with Actions' token. When they pass, GitHub merges it by rebase.
6. Flux sees the new commit within 2 minutes. It applies the release: the migration Job first, then Flagger's canary of the control plane, while the web, scanner and database roll as usual.

Watch it on the server with `kubectl -n flux-system get kustomizations` and `kubectl -n idleo get canary -w`. The commit's status on GitHub shows each Kustomization's outcome (Flux's notification controller).

## Promoting staging to production

1. Run the **promote** workflow (Actions → promote → "Run workflow"), or `gh workflow run promote.yml`.
2. It copies staging's release into production's, verifies it again, and opens **"Promote <short sha> to production"** with the commits between production's release and staging's.
3. Read the summary, wait for the checks, and merge it. Nothing merges it for you.
4. Flux applies it. The control plane goes out as a canary: 10%, 25%, 50%, then 100% of the API's traffic, two minutes a step, about ten minutes in all. Each step checks three things: the share of 5xx stays below 1%, p95 latency stays below 800 ms, and session starts aren't more than 10 points down. A failure rolls it back at once (see "A failed canary").

The migration Job runs before the canary, so the release before keeps serving on the new schema. That is why every migration must work with both releases (expand and contract below).

## Rolling back

Revert the pull request that deployed the release (`gh pr revert <number>`, or "Revert" on its page), and merge the revert like any other change. Flux applies the release before, as a canary of its own, so going back takes the same ten minutes as going forward. The database isn't rolled back: its migrations stay. Under expand and contract, the release before works with them.

A canary that fails is rolled back by Flagger by itself; still revert its pull request, so git says what runs again.

Never `kubectl edit` or `helm upgrade` a cluster by hand: Flux puts back whatever git says within its interval, and the change leaves no record.

## A failed canary

CanaryFailed pages (alerts.md, "Canary failed"), and the release's commit status on GitHub goes to failed. To see why:

```sh
kubectl -n idleo describe canary idleo-control-plane           # the events name the check that failed, and its value
kubectl -n flagger-system logs deploy/flagger --since=1h
kubectl -n flux-system describe kustomization idleo             # "health check failed" with the canary's phase
```

All traffic is already back on the release before, and the canary's pods are scaled to zero. Revert the release's pull request, fix the cause, and release again. If the failure came from outside the release (a database or Valkey problem shows in the primary's meters too), fix that, then release the same commit again by promoting it once more.

## Expand and contract

Every migration in a release must work with that release and the one before (`scripts/checks/migrations.sh` enforces it in the control plane's required check):

- **Add any time:** new tables, new nullable columns, columns with defaults, new indexes.
- **Drop or rename only later:** first a release stops reading the old column or table, and it reaches production. Only then can a later release's migration drop it. That migration says so in a line of its own, which the check requires for any DROP, RENAME or ALTER … TYPE:

  ```sql
  -- contract: drop sessions.old_label, unread since the release that read display_name, safe since 1a2b3c4
  ```

The migrations up to V61 predate the rule and are left as they are (never edit an applied migration).

## Secrets

Each environment's Secrets are SOPS files under `deploy/clusters/<env>/secrets/<namespace>/`. Their `data` and `stringData` are encrypted for that environment's age key (`.sops.yaml`), and Flux decrypts them in the cluster with the private key it holds as the Secret `flux-system/sops-age`. Install `sops` and `age` (`brew install sops age`). The deploy check refuses any Secret under `deploy/` that isn't encrypted for its environment's key.

**An environment's key** (staging and production, with the server):

```sh
scripts/deploy/age-key.sh production
```

It writes the key to `private/age/production.agekey` and puts its public half in `.sops.yaml` (commit that). It prints only the public half. Then:

1. Put the file's contents in the password manager.
2. Load it into the cluster: `kubectl -n flux-system create secret generic sops-age --from-file=age.agekey=private/age/production.agekey`.
3. Delete the file: `rm private/age/production.agekey`.

The private half is never committed, pasted into chat or put in GitHub's secrets.

**Adding a secret.** Write the Secret manifest with its plain values in a file that is not in the repository, encrypt it into place, and list it in its folder's `kustomization.yaml`:

```sh
kubectl create secret generic idleo-control-plane --namespace idleo --from-env-file=/path/outside/the/repo.env \
  --dry-run=client -o yaml > /tmp/idleo-control-plane.yaml
sops encrypt --filename-override deploy/clusters/production/secrets/idleo/control-plane.yaml \
  /tmp/idleo-control-plane.yaml > deploy/clusters/production/secrets/idleo/control-plane.yaml
rm /tmp/idleo-control-plane.yaml
```

**Changing a value.** `sops edit deploy/clusters/production/secrets/idleo/control-plane.yaml` needs the environment's private key on the machine (from the password manager, into a file named by `SOPS_AGE_KEY_FILE`; delete it afterwards). Commit, open a pull request, and once it merges Flux applies it. Pods that read the Secret as environment variables need a restart to see it: `kubectl -n idleo rollout restart deploy/idleo-control-plane-primary`.

**Rotating the key** (yearly, or when someone who had it leaves): get the old key into a file, run `scripts/deploy/age-key.sh <env> --rotate`, and commit `.sops.yaml` and the re-encrypted files. Replace `sops-age` in the cluster with the new key, store the new key in the password manager, and delete both files.

**If a private age key leaks,** everything ever encrypted for it is readable by whoever has it, including every past version in git's history:

1. Rotate the key at once (above), so new values aren't readable with the old one.
2. Change **every** value that environment's Secrets hold: the database passwords, the signing and sealing keys (deployment.md says what changing each means), the Razorpay and RazorpayX keys, the Grafana Cloud and GitHub tokens. Re-encrypt each one.
3. Treat it as a breach if any of those keys could reach personal data (breach.md).

What each environment's Secrets hold:

| Secret | Namespace | What |
| --- | --- | --- |
| `idleo-database` | idleo | The superuser's, migration user's and application user's passwords |
| `idleo-control-plane` | idleo | The control plane's keys and the agents' CA (deployment.md lists them) |
| `grafana-cloud-metrics` | flux-system | `GRAFANA_CLOUD_PROMETHEUS_URL` and `_USER`, which Flux substitutes into Prometheus's remote write |
| `grafana-cloud-metrics` | monitoring | `GRAFANA_CLOUD_PROMETHEUS_TOKEN`, a Grafana Cloud token that may only write metrics |
| `github-commit-status` | flux-system | `token`: a fine-grained GitHub token for this repository with "Commit statuses: write" and nothing else |

## Bootstrapping a server's Flux (S5)

Once the server runs k3s, with the environment's key made:

```sh
kubectl apply --server-side -k deploy/flux
kubectl -n flux-system create secret generic sops-age --from-file=age.agekey=private/age/<env>.agekey
kubectl apply -f deploy/clusters/<env>/flux-system/sync.yaml
```

From then on Flux follows `main` and keeps itself up to date. An upgrade of Flux is a pull request that changes `deploy/flux` (its kustomization says how to export the new release).
