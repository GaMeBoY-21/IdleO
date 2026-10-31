# Reading the audit log

The Kubernetes API server keeps an audit log (ADR 0048) of who read or changed a Secret, opened a shell, a port or a debug container into a pod, or changed who may do what (roles, bindings, service-account tokens). It records the request's metadata only: who (a person's kubeconfig user, or a service account), what (verb, resource, namespace, name), when, from which address, and the answer. Never a body, so never a Secret's value. Everything else is left out. The policy is `deploy/k3s/audit-policy.yaml`.

On the server it's `/var/lib/rancher/k3s/server/logs/audit.log`, rotated by k3s at 50 MB, four old files kept, none older than 30 days (`deploy/k3s/server/config.yaml`): at most 250 MB. On the Mac's cluster it's the same path inside the k3d node container.

It holds operators' usernames and addresses: personal data (docs/reference/personal-data-map.md), read only for an incident or a review, and never copied off the server except into the incident's own private record.

## Reading it

Each line is one JSON event. With `jq` on the server (or `docker exec k3d-idleo-server-0 cat …` piped to the Mac's `jq`):

```sh
log=/var/lib/rancher/k3s/server/logs/audit.log
# Who opened a shell, a port-forward or a debug container, and into what
jq -c 'select(.objectRef.subresource | test("exec|attach|portforward|ephemeralcontainers"; "x")? )
  | {at: .requestReceivedTimestamp, who: .user.username, from: .sourceIPs, verb, pod: (.objectRef.namespace + "/" + .objectRef.name), code: .responseStatus.code}' "$log"
# Every read of a Secret by a person (service accounts start system:serviceaccount:, k3s's own system:)
jq -c 'select(.objectRef.resource == "secrets" and (.user.username | startswith("system:") | not))
  | {at: .requestReceivedTimestamp, who: .user.username, verb, secret: (.objectRef.namespace + "/" + (.objectRef.name // "*")), code: .responseStatus.code}' "$log"
# Changes to who may do what
jq -c 'select(.objectRef.apiGroup == "rbac.authorization.k8s.io")
  | {at: .requestReceivedTimestamp, who: .user.username, verb, what: (.objectRef.resource + " " + .objectRef.name)}' "$log"
# The rotated files too
ls -l /var/lib/rancher/k3s/server/logs/
```

## What's normal

- Service accounts reading Secrets they're meant to: Flux's kustomize controller (`sops-age`, and the Secrets it applies), CloudNativePG's operator and instances (their Cluster's), cert-manager (its certificates'), Kyverno (its own TLS), the kubelet (`system:node:…`, a pod's Secrets as it starts).
- `kubectl exec` by an operator during a runbook (database.md's psql, valkey-password.md's ping), and the security test's debug containers on the Mac.

## What isn't

- A person's `list` of Secrets across a namespace, or a `get` of one no runbook reads.
- A shell or debug container into the control plane, Valkey or a database instance outside a runbook or an incident.
- Any create or change of a Role, ClusterRole or binding that isn't Flux applying a merged pull request (Flux's user is `system:serviceaccount:flux-system:kustomize-controller` or `helm-controller`).
- A `serviceaccounts/token` request by anyone but the kubelet.
- A 403 answer to something a person tried (someone probing what they may do).

Any of these: follow breach.md.
