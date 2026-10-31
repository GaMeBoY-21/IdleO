# Kyverno down

Kyverno (namespace `kyverno`, ADR 0048) checks the signatures of every pod's images as the pod is admitted (`deploy/policies/base/images-signed.yaml`). Its webhooks **fail closed**: while it can't answer, no pod is admitted anywhere but in `kube-system` and Kyverno's own namespace, which are never sent to it, so Kyverno itself, k3s's Traefik and CoreDNS can always start again. The rules that need no network (pinned images, the allowed list, no privileged pods) are native admission policies the API server checks itself, so they hold whether Kyverno is up or not.

What is already running keeps running: Kyverno only stands at the door. What waits while it's down is every *new* pod: a control plane replacing one that died, a rolling update, the migration Job, a CloudNativePG failover's rebuilt instance, an operator command. Each one is admitted as soon as Kyverno answers again, by its controller's own retry; nobody has to do anything.

## Is it down?

```sh
export KUBECONFIG=<the cluster's>     # the Mac's: private/k8s/kubeconfig
kubectl -n kyverno get pods                                   # one admission controller, Running and 1/1
kubectl -n idleo get events --field-selector reason=FailedCreate | grep -i webhook   # pods it couldn't admit
kubectl -n kyverno logs deploy/kyverno-admission-controller --tail=100
```

A refusal while it's down reads `failed calling webhook "...kyverno..."`, not a policy's message.

## What it takes to come back

Measured on the Mac (`scripts/k8s/security-test.sh`, check 11): Kyverno's pod and a control-plane pod killed hard at the same moment. Kyverno's Deployment starts a new pod at once (its namespace isn't checked); while it starts, the control plane's replacement is refused and retried; the other control plane serves every request. The times are in ADR 0048.

It's evicted last on a full node (priority class `idleo-platform`). One copy, held to 384 MiB; if it's OOM-killed (`kubectl -n kyverno describe pod` says `OOMKilled`), raise its limit in `deploy/infrastructure/base/kyverno.yaml` (measure first: `kubectl top pod -n kyverno`) in a pull request.

## When it doesn't come back

1. **Its pod won't start.** `kubectl -n kyverno describe pod -l app.kubernetes.io/component=admission-controller`: an image it can't pull (the registry, or a digest that's gone), a crash in its log, or no room on the node. Fix that; nothing else is blocked from starting Kyverno.
2. **It's up but can't verify.** A new image is refused with a verification error, not a webhook error: `Get "https://ghcr.io/v2/": ... connection refused` (it can't reach GHCR) or the policy's own `could not be reached to check one` (Sigstore). It must reach GHCR, Sigstore's transparency log and its TUF mirror on HTTPS. Check its network policy (`deploy/infrastructure/base/kyverno.yaml`), the server's own outbound connection, and [githubstatus.com](https://www.githubstatus.com) and [status.sigstore.dev](https://status.sigstore.dev).

   **What still starts meanwhile:** any pod whose images a verified Deployment, StatefulSet, DaemonSet, Job or CronJob already names: a control plane or database pod replacing one that died, a failover, a node restart. Those images were checked in full when their controller was written, so Kyverno admits the pod without asking GHCR or Sigstore (in tens of milliseconds; ADR 0048, "When GHCR or Sigstore is down"). What waits is a *new* digest: a deploy, a canary, a vendor upgrade. It's refused until both answer again, and its controller's own retry admits it then; nobody has to do anything but wait or fix the connection.
3. **An outage that matters more than the check.** Only with the product owner's say, and only for as long as it takes: let pods through without the signature check by setting the webhooks to ignore failures, and put them back right after:

   ```sh
   # Off (pods are admitted without signatures checked; the native policies still hold):
   kubectl get validatingwebhookconfigurations -o name | grep kyverno |
     xargs -I{} kubectl patch {} --type=json -p='[{"op":"replace","path":"/webhooks/0/failurePolicy","value":"Ignore"}]'
   # Back on: Kyverno rewrites its webhooks from the policies by itself once it runs; or
   flux reconcile kustomization policies --with-source
   ```

   Every pod admitted meanwhile is unchecked: afterwards, run `scripts/images/verify.sh` on each of our images that started, and record it in the incident (breach.md if anything didn't verify).

Never delete Kyverno's webhook configurations or its HelmRelease to get pods through: Flux puts them back, and an uninstall removes the checks entirely until it's reinstalled.
