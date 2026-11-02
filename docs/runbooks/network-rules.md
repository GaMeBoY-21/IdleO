# Adding a network rule

Every namespace we own denies all traffic by default, both ways, and then allows exactly what each kind of pod needs (ADR 0048, which has the whole table). A new connection that isn't in the table times out: the pod's log shows a connect timeout, never a refusal.

## Where the rules are

| Namespace | File |
| --- | --- |
| `idleo` | The chart: `templates/network.yaml` (the default deny, and the ways out each pod opts into by a label), and each component's own ingress rule beside it (`control-plane.yaml`, `database.yaml`, `valkey.yaml`, `web.yaml`, `scanner.yaml`, `canary.yaml`, `development.yaml`) |
| `kyverno`, `cert-manager`, `cnpg-system`, `flagger-system` | `deploy/infrastructure/base/<name>.yaml` |
| `monitoring` | `deploy/infrastructure/base/prometheus/prometheus.yaml`; the remote write's way out is `deploy/infrastructure/remote-write/network.yaml` |
| `flux-system` | `deploy/flux/network.yaml`; the Mac's registry is `deploy/clusters/local/flux-system/registry.yaml` |

## The ways out in `idleo`

A pod in the chart reaches something outside itself only through a label:

| Label | Reaches |
| --- | --- |
| `idleo.in/dns-client` | CoreDNS, port 53 |
| `idleo.in/database-client` | Any CloudNativePG instance, 5432 |
| `idleo.in/valkey-client` | Valkey, 6379 |
| `idleo.in/internet-client` | The internet on 443, never `networkPolicy.internetExcept` (the private ranges, the cluster's, link-local with the metadata address, CGNAT, loopback) |
| `idleo.in/mail-client` | The internet on the mail port (587) |
| `idleo.in/development-client` | The Mac's development stand-ins (`development.enabled` only) |

The control plane and operator commands carry all of them (`idleo.controlPlaneClient`). The migration Job carries `dns-client` and `database-client`. The scanner carries `dns-client` and `internet-client`, plus its own rule to the control plane's internal API. The web server and Valkey carry none: they reach nothing.

## Adding one

1. **Find it in the code first.** A rule names a real caller and a real port, read from the code or the vendor's documentation, not guessed. Write down who calls whom, on which port, and why.
2. **Allow both ends.** A connection inside the cluster needs the caller's egress *and* the callee's ingress: one without the other still times out. In `idleo`, prefer an existing label on the caller; add a new label (and its policy in `network.yaml`) only for a new kind of destination, and give it only to the pods that need it.
3. **Never widen the internet.** A new outside host on 443 needs nothing (the policy can't name hosts). A new outside port gets its own policy and label, like `mail-client`, never a change to `internet-client`. Nothing gets the private ranges or `169.254.169.254`.
4. **Ports by number to another pod, by name only on the pod's own ingress.** A policy matches the port after the Service is resolved: the container's port, not the Service's.
5. **Add the row to ADR 0048's table**, in the same pull request.
6. **A new kind of pod** in the chart starts with `{{- include "idleo.networkPolicyWait" $ | nindent 8 }}` as its first init container (the chart's tests fail without it).
7. **Prove it.** `scripts/checks/deploy.sh` renders it; `scripts/k8s/security-test.sh` (the `security` workflow runs it on the pull request) must still pass, and if the new connection is one a broken-into pod mustn't make, add a probe for it there, with a control that's allowed.

## Debugging a blocked connection

A debug container shares the pod's network, so it's held by the same policies (it must be a pinned, allowed image, as every container is; Valkey's has bash):

```sh
image="$(kubectl -n idleo get pod idleo-valkey-0 -o jsonpath='{.spec.containers[0].image}')"
kubectl -n idleo debug -it pod/<the pod> --image="$image" --profile=restricted -- \
  bash -c 'timeout 3 bash -c "exec 3<>/dev/tcp/<address>/<port>" && echo open || echo closed'
kubectl -n idleo get networkpolicies
kubectl -n idleo describe networkpolicy <name>
```

k3s enforces policies with its own controller (kube-router). Traffic from the node itself (the kubelet's probes, the API server calling a webhook) is always allowed, so probes and webhooks need no rule.

**A pod stuck in `Init` on `wait-for-network-policy`.** Every pod of ours, and Kyverno's, waits before it starts until kube-router enforces its rules (ADR 0048, "A new pod's first moments"), which it sees as the node's kubelet port, 10250, no longer answering. Normally that takes 0.3 to 3 s. If a pod waits longer, kube-router hasn't caught up: `kubectl -n idleo logs <the pod> -c wait-for-network-policy`, and k3s's own log (`journalctl -u k3s`, or `docker logs k3d-idleo-server-0` on the Mac) for network policy errors. After 300 s the container fails and the kubelet starts it again, so the pod never runs without its rules. Never give a pod of ours a rule to the node's port 10250: its wait would then never end (Prometheus, a vendor's pod, is the one that has it, and doesn't wait).
