# 0048. Cluster security

- **Status:** Accepted
- **Date:** 2026-10-31

## Context

ADRs 0045 to 0047 built the server side as one k3s node under Flux: the control plane (two copies, released as canaries), the web server, the image scanner, Valkey, CloudNativePG's database, and beside them cert-manager, Flagger, Prometheus and Flux. Each of our pods already ran non-root on a read-only root with every capability dropped and no service-account token (rule 6), and three network policies guarded the control plane, the database and Valkey on the way in. Nothing else was enforced. Any pod could reach any other pod and the internet; any image, by tag or by digest, signed or not, could run; Secrets sat in plain text in k3s's datastore on disk; nothing recorded who read them.

**The threat** is one broken-into pod: the web server (it serves files to the internet), the scanner (it reads images renters choose, ADR 0042), or the control plane itself (it holds every key). The attacker has code running as that pod's user. What must hold afterwards:

- it can't reach anything its kind doesn't need: no database from the web server, no Valkey from the scanner, no cloud metadata address from anyone;
- it can't start a new image we didn't build and sign, or a privileged pod that would reach the node;
- it can't talk to the Kubernetes API;
- it can't read Secrets off the disk.

Each is proved by a test that tries it and must fail (`scripts/k8s/security-test.sh`).

The server is one 8 GB node, and at a canary's peak the requests already total about 6.1 GiB (ADR 0047), so everything added here is measured and nothing existing is cut.

## Decision

### Pod Security "restricted", enforced

Every namespace we own carries `pod-security.kubernetes.io/enforce: restricted`: `idleo`, `kyverno`, `cert-manager`, `cnpg-system`, `flagger-system`, `monitoring` and `flux-system` (Flux shipped only `warn`; a kustomize patch in `deploy/flux` adds `enforce`). Every vendor's pods meet it, so none needs `baseline`. Flagger's chart sets only a user and a read-only root, so its values add the rest (no privilege escalation, every capability dropped, `RuntimeDefault` seccomp); the API server's own default caught it. The API server's admission configuration (`deploy/k3s/pod-security.yaml`) makes "restricted" the default for any namespace that doesn't say otherwise, with `kube-system` exempt, as k3s ships it. The scanner runs in `idleo`, so it has no namespace of its own.

### The network: deny by default

Every namespace above has a NetworkPolicy that denies all traffic both ways, then policies that allow exactly what each pod needs. The table was built from the code and the vendors' documentation, not guessed, and it differs from first guesses in three places: the control plane never calls the scanner (the scanner pulls work from the control plane's internal API, `SCANNER_CONTROL_PLANE`); Flagger never calls the Gateway (it moves the HTTPRoute's weights through the Kubernetes API, and calls Prometheus); and email is SMTP submission on 587, not HTTPS (`application-prod.yaml`).

In `idleo`, a pod reaches outside itself only through labels it opts into (`templates/network.yaml`): `idleo.in/dns-client`, `database-client`, `valkey-client`, `internet-client` (443), `mail-client` (587) and `development-client` (the Mac's stand-ins). The web server and Valkey carry none.

| From | To | Port | Why |
| --- | --- | --- | --- |
| Traefik (kube-system) | control plane | 8080 | the API through the Gateway |
| Traefik | web | 8080, 8081 | the app and the website (the Mac; Cloudflare Pages in production) |
| Traefik | cert-manager's HTTP-01 solver | 8089 | Let's Encrypt checking a name (S5) |
| anyone, through its load balancer | control plane | 9443 | agents' gRPC; mutual TLS guards it (ADR 0010) |
| scanner | control plane | 8095 | the scanner's internal API (ADR 0042) |
| Prometheus (monitoring) | control plane, database | 9464, 9187 | the meters Flagger judges and the database's |
| the canary's load (`idleo-canary-load`) | control plane | 8080 | a request a second to the canary |
| control plane, migration Job, operator commands | database (any CloudNativePG instance) | 5432 | the primary through `idleo-database-rw`; a restored Cluster too |
| database instances | each other | 5432, 8000 | streaming, a standby joining |
| CloudNativePG's operator (cnpg-system) | database | 8000 | the instance manager |
| control plane, operator commands | Valkey | 6379 | presence, limits, hints |
| control plane, migration Job, operator commands, scanner, the canary's load, database | CoreDNS | 53 | names |
| control plane, operator commands | the internet | 443 | Razorpay and RazorpayX, Identity Platform's keys, R2, Grafana Cloud, the PAN check, the agents' release feed |
| control plane, operator commands | the internet | 587 | the mail server |
| scanner | the internet | 443 | renters' registries, Trivy's database |
| database instances | the internet; the Kubernetes API | 443; 6443 | backups to R2; the instance manager keeps the Cluster's state |
| web, Valkey | nothing | | |
| (the Mac) control plane, operator commands | sign-in, storage, mail stand-ins | 8080, 9000, 1025 | development's identity, S3 and mail (ADR 0013) |
| (the Mac) database instances | storage stand-in | 9000 | backups to MinIO |
| (the Mac) this machine | sign-in, storage, mail stand-ins | their ports | the browser, the agent and the tests |
| kyverno: the admission controller | DNS, API; the internet | 53, 443/6443; 443 | registries, Sigstore's transparency log and TUF |
| cert-manager: every pod | DNS, API | 53, 443/6443 | |
| cert-manager: the controller | the internet | 443, 80 | Let's Encrypt; HTTP-01's self-check |
| cnpg-system: both pods | DNS, API | 53, 443/6443 | |
| cnpg-system: the operator | database; the backup plugin | 8000; 9090 | |
| flagger-system: Flagger | DNS, API; Prometheus | 53, 443/6443; 9090 | |
| monitoring: Prometheus | DNS, API; control plane, database, Flagger | 53, 443/6443; 9464, 9187, 8080 | finding and scraping its targets |
| monitoring: Prometheus (staging, production) | the internet | 443 | the remote write to Grafana Cloud |
| flux-system: every controller | each other; DNS, API | any; 53, 443/6443 | artifacts and events between controllers |
| flux-system: source and notification controllers | the internet | 443 | GitHub, GHCR, Sigstore; commit statuses |
| (the Mac) flux-system: the source controller | the cluster's registry | 5000 | the chart up.sh pushes |

In all: 7 default-deny policies, and 24 policies with 58 allow rules in staging and production (the Mac adds 6 policies for its stand-ins and registry, and has no remote write). "The API" is the `kubernetes` Service's address on 443 and k3s's own port 6443 behind it, which is what a policy sees once the Service is resolved. Traffic from the node itself (the kubelet's probes, the API server calling a webhook) is always allowed by k3s's policy controller, so probes and webhooks need no rule.

**Outside traffic.** A NetworkPolicy can't name a host, so "the internet" is the port to every address except the private ranges (10/8, 172.16/12, 192.168/16, which hold the cluster's pods 10.42/16 and Services 10.43/16 and the server's networks), link-local 169.254/16 (the cloud's metadata address, 169.254.169.254), carrier-grade NAT 100.64/10 (Tailscale's addresses) and loopback (`networkPolicy.internetExcept`; an environment adds its server's public address in S5). Only the pods in the table get it.

**Per-host egress is the next step:** an egress proxy (Squid or Envoy with an allow list of hosts), or Cilium in place of k3s's flannel and kube-router, whose FQDN policies name hosts. It would stop a broken-into control plane from sending what it holds to a host of the attacker's on 443, which today's rules allow. It's worth it once the server holds real money and real people's data (live payments, ADR 0039 and 0040), or when a second node arrives and the network is rebuilt anyway; today it would add a proxy in the path of every outside call, or a different network for the whole cluster, for a test-mode service.

Agents' gRPC load balancer and the Gateway keep working: the smoke, canary and failover tests pass (below).

### Only pinned, allowed, signed images

Two layers, so the parts that need no network hold even when Kyverno can't answer:

- **Native admission policies**, which the API server checks itself (`deploy/policies/base/images-pinned.yaml`, `pods-unprivileged.yaml`), in every namespace but `kube-system`, Kyverno's own included: every image names its registry, is pinned by digest, is never tagged `latest`, and is one of the repositories in the ConfigMap `kyverno/idleo-allowed-images` (a missing list refuses everything); no privileged container, no host network, PID or IPC namespace, no hostPath volume. Pod Security already refuses the last ones; these say why in plain words and hold in a namespace someone forgot to label. Debug containers are checked too.
- **Kyverno v1.19.2** (`deploy/infrastructure/base/kyverno.yaml`, chart and every image pinned by digest) checks signatures with ImageValidatingPolicies (`images-signed.yaml`), Kyverno's current kind (ClusterPolicy is deprecated in 1.19). Our images, `ghcr.io/gameboy-21/idleo-*`, must carry a keyless signature from `.github/workflows/images.yml` on `refs/heads/main` or a `v*` tag through GitHub's OIDC issuer and the public transparency log, plus the same workflow's CycloneDX bill of materials and SLSA build provenance: the three checks `scripts/images/verify.sh` makes. Our cosign v3 signatures and attestations are Sigstore bundles attached as OCI referrers, and Kyverno verifies them.

Only the admission controller runs: one copy, 160/384 MiB, with no background, cleanup or reports controller (no policy acts on existing resources and nothing reads reports), and no cloud credential helper (every image it checks is public, and the helpers would ask the metadata address its network policy blocks).

**The vendors**, checked one by one:

| Vendor | Images | Signed? | What's checked |
| --- | --- | --- | --- |
| Flux | source, kustomize, helm, notification controllers | keyless, `fluxcd/gha-workflows` controller-release at a tag | digest and signature |
| Flagger | flagger | keyless, its `release.yml` at a tag | digest and signature |
| curl | the canary's load (`curlimages/curl`) | a cosign key whose published half doesn't verify it | digest only |
| CloudNativePG | operator | keyless, its `release-publish.yml` at a tag | digest and signature |
| CloudNativePG | PostgreSQL | keyless, `postgres-containers`' `bake_targets.yml` on main | digest and signature |
| CloudNativePG | Barman Cloud plugin and its sidecar | no | digest only |
| cert-manager | controller, webhook, cainjector, startupapicheck, acmesolver | a cosign signature by a key whose published half (2021) doesn't verify them | digest only |
| Kyverno | admission controller, its init container, CLI, readiness checker | no signature; SLSA provenance from the SLSA GitHub generator, checked when pinned. Its namespace never reaches its own webhooks | digest only, in admission |
| Prometheus, Valkey | official images | no | digest only |
| Traefik, CoreDNS, k3s's own | in kube-system | not checked: kube-system is exempt so the cluster can always recover | k3s's release, pinned by digest |

The chart's Valkey and the Mac's stand-ins now name `docker.io`; cert-manager's images, which were by tag, are pinned by digest.

**The Mac's cluster.** Images built on the Mac aren't signed, so `deploy/policies/local` adds the cluster's own registry (`k3d-idleo-registry:5050/idleo-*`) and development's three stand-ins to the allowed list, under a key of their own (`development`). That exception never exists in staging or production: `scripts/checks/deploy.sh`, a required check, builds each cluster's policies as Flux does and fails if staging's or production's name the local registry or have the `development` list, shows that it refuses the Mac's, and runs `kyverno test` on both (`scripts/deploy/policy-test.sh`, `deploy/policies/tests/`): 28 expectations, from staging's real signed control plane admitted and the same repository's unsigned attestation digest refused, to the local registry, a tag, `latest`, a missing registry, an unlisted repository, privileged, hostPath and host network refused, and each signed vendor admitted, with signatures checked against GHCR and the transparency log.

**When Kyverno is down.** Its webhooks fail closed: while it can't answer, no pod is admitted outside `kube-system` and `kyverno`, which are never sent to it, so Kyverno and the cluster can always come back (docs/runbooks/kyverno.md). Pods already running carry on; what waits is every new pod, admitted by its controller's own retry once Kyverno answers. Its webhook timeout is Kubernetes' maximum, 30 s: one of our images takes 12 to 14 s to admit on the Mac (the signature and two attestations, each fetched and checked, with no cache for this kind of policy; the same with its network policy removed), and 15 s refused staging's real image once. Measured by killing Kyverno's pod and a control-plane pod together (`security-test.sh`, check 11): Kyverno's new pod was ready 31.8 s after the kill (26.0 s in an earlier run); meanwhile a test pod was refused with "failed calling webhook", the control plane's replacement was refused 11 times and retried, and the other control plane answered all 46 renters' requests (in the earlier run, 1 of 42 failed: the request in flight as the pod died, before its address left the Service, which a hard kill allows). The control plane was back to two ready 58.4 s after the kill (60.0 s earlier), about 27 s after Kyverno. It's in the runbook.

**When GHCR or Sigstore is down.** Measured first on the Mac, with Kyverno's way out cut by its network policy (a pod restarted after the cut, so no connection from before survives):

| With Kyverno unable to reach | Before | Now |
| --- | --- | --- |
| Sigstore (Rekor and the TUF mirror) only | admitted, slowly: our image 14.5 s, PostgreSQL's 1.4 s (cosign v3's bundles carry the log's proof) | the same for a new digest; a known one in milliseconds |
| GHCR | every pod with a signed image refused in 0.1 to 0.6 s; a killed control plane stayed down; a killed database primary left **no writable primary** (Kyverno refused the label patch CloudNativePG promotes the standby with) until GHCR returned | a known digest admitted in 12 to 62 ms; the primary killed: writes back at 20.6 to 23 s, the old primary rebuilt at 31.6 to 35 s; a control plane killed: back to two in about 12 s |
| GHCR, after a node restart | **all of IdleO down** (every pod a new object, every one refused) until GHCR returned, then back 195 s later | back in 72 s, with GHCR still unreachable |

A digest already verified is admitted without the network, in under a second; a new one is checked in full, and refused while it can't be ("no signature from … (or GHCR or Sigstore could not be reached to check one: docs/runbooks/image-refused.md)"). Evaluated in the order the round set:

1. **Offline verification against a trusted root.** The attestor's `trustedRoot` loads Sigstore's root from a file in place of its TUF mirror, but Kyverno 1.19.2's keyless path initialises TUF anyway (`initTUFAndFetch`), so a restarted Kyverno with Sigstore unreachable still fails. And it couldn't help with GHCR: the signatures and attestations are in GHCR, beside the image.
2. **Kyverno's verification cache.** It exists for ImageValidatingPolicies, but costs each entry by its payload's size against a budget of 1000 (`imageVerifyCacheMaxSize`), so a bill of materials or provenance, megabytes each, is never kept; it's in memory, so a restart or a node restart empties it; and Kyverno fetches every matched image from the registry before it validates, so with GHCR down nothing reaches the cache at all.
3. **Built: the controller's verification stands for its pods.** Every pod controller (Deployment, StatefulSet, DaemonSet, Job, CronJob) is checked in full when it's written: the signature policies cover them (Kyverno's autogen), so one naming an unsigned digest is refused at write. Kyverno keeps watched copies of them (`deploy/policies/base/pod-controllers.yaml`, five GlobalContextEntries), and each signature policy's match condition sends it only pods naming an image that no controller outside `kube-system` and `kyverno` names. A pod whose images its controllers already name skips the check, its images verified when the controller was. CloudNativePG makes its instances' pods itself, without a controller to name them, but the control plane's Deployment names the database's image (its schema-wait init container is that image, `database.image`), so they pass too. The match is by the exact `image` string, digest included, so a controller can't vouch for a different digest. `scripts/deploy/signed-policies.py` writes every signature policy from one list; `deploy.sh` fails if the file differs from what it writes, and `kyverno test` has a Deployment's case each way (28 expectations in all).

What it costs: an image a controller names is trusted for as long as the controller exists, even if its signature were withdrawn; a withdrawn signature is a new release anyway, which replaces the digest. The copies are in Kyverno's memory (measured below).

**Priority classes** (`deploy/infrastructure/base/priority-classes.yaml`) set the eviction order on a full node: the platform first to stay (Kyverno and CloudNativePG's operator are `idleo-platform`; Flux and k3s's Traefik already run as `system-cluster-critical`), then the control plane, the migration Job and the database (`idleo-core`), then everything else at the default.

### The Kubernetes API, RBAC and secrets

No pod of ours mounts a service-account token (the chart's unit tests hold every pod to it; the security test finds none on 13 running pods), and every namespace's `default` service account now mounts none either (kube-bench 5.1.5), so a pod that forgets still gets none. `security-test.sh` writes what every service account in the cluster may do (`kubectl auth can-i --list`) to `private/k8s/logs/security-rbac.txt`, and fails if any account in `idleo` but CloudNativePG's can read Secrets or make pods. In `idleo`:

- `default`: nothing.
- `idleo-database` (CloudNativePG's instances): get and watch on its own Cluster's Secrets by name (the users', the replication and server certificates, the superuser's), and get, watch and list on `idleo-backup-storage`, the backups' keys. The instance manager needs them; the operator makes the Role.

The operators keep what they need, recorded here and accepted:

- **Flux's kustomize and helm controllers are cluster-admin** (`cluster-reconciler-flux-system`). Flux applies every object in git, CRDs, ClusterRoles and webhooks included, so anything less is a list of every kind that ever appears, kept in step by hand. That's accepted because Flux is the deploy path itself: whoever can merge to main can change the cluster anyway, and Flux reads from git, never writes, and has no listener (its network policy lets nothing in from outside its namespace).
- CloudNativePG's operator reads Secrets and makes pods cluster-wide (its instances); cert-manager reads and writes Secrets (certificates) and makes pods (HTTP-01 solvers); Flagger reads Secrets (it copies the canary's), and makes Deployments and Services; Prometheus may only read pods; Kyverno reads its own policies, webhooks and images' metadata, and no Secret outside its namespace.
- k3s's own: `system:masters` and its Traefik installers (`helm-kube-system-traefik*`) are cluster-admin, as k3s ships them.

**Secrets encrypted at rest.** k3s's `--secrets-encryption` (AES-CBC, its stable provider; secretbox is still experimental in k3s) is on in the k3d cluster (`up.sh`) and in the server's configuration (`deploy/k3s/server/config.yaml`). The security test writes a Secret and a ConfigMap carrying the same random marker, copies k3s's SQLite datastore and its WAL off the node, and finds the ConfigMap's value in plain text and the Secret's nowhere, with `k8s:enc:aescbc:v1:` where Secrets are stored. The key is in the server's own folder (`server/cred`), so encryption protects copies of the datastore without it: backups of k3s (S5) keep the two apart.

**The audit log.** `deploy/k3s/audit-policy.yaml`, at metadata level: reads and changes of Secrets (not watches), exec, attach, port-forward and debug containers, and changes to roles, bindings and service-account tokens; nothing else, and never a body. k3s rotates it at 50 MB, keeps four old files and none older than 30 days: at most 250 MB of the disk. On the Mac through every test in this round it grew 6.3 MB in 2 h 38 min of bootstrapping, four security tests, a canary test, a failover test and a smoke test (about 2.4 MB an hour, most of it controllers reading their own Secrets: Kyverno its TLS pair, Flux its release records, CloudNativePG its Cluster's), and about 1.2 MB an hour when idle, so the 250 MB holds about 8 days at rest. It holds operators' usernames and addresses, so it's in docs/reference/personal-data-map.md; docs/runbooks/audit-log.md says how to read it.

**Valkey has a password.** One ACL user, `idleo`, with its password from the SOPS Secret `idleo-valkey`; the default user is off, and `FLUSHALL`, `FLUSHDB`, `CONFIG`, `DEBUG`, `KEYS`, `MODULE`, `ACL`, `MONITOR`, saving, replication and `SHUTDOWN` are refused to it (`valkey.refusedCommands`). The control plane uses none of them (its Valkey calls are Lua scripts of `HGET`, `HSET`, `DEL`, `LPUSH`, `LTRIM`, `LINDEX`, `PEXPIRE`, pub/sub and the client's own commands; the smoke test's `--scan` is `SCAN`). The ACL list is written to memory as Valkey starts. A rotation accepts the previous password too, so it costs no failed request (docs/runbooks/valkey-password.md).

### Scanning

**The Trivy Operator was tried** on the Mac's cluster (chart 0.32.1, Trivy Operator 0.30.1, one scan at a time, scanning `idleo`): the operator held about 190 MiB, and each scan job peaked at 476 to 586 MiB (six jobs, cgroup `memory.peak`); in its first minutes it found 1 CRITICAL and 61 HIGH in the PostgreSQL image, 46 HIGH in Valkey's, fixed or not. It doesn't fit: the operator alone is about 190 MiB, each scan job peaks around half a gigabyte, and it scans whenever a workload changes, which is a deploy, which is a canary's peak, the one moment there's least room. It isn't installed.

**Instead, a nightly CI job** (`.github/workflows/scan.yml`, `scripts/images/scan-deployed.sh`) runs Trivy, pinned as the images workflow pins it, on the exact digests staging and production pin, and on a k3d cluster built from main under Flux: every running image in the namespaces we own, ours and the vendors', and their manifests, RBAC and secrets. A fixable CRITICAL or HIGH vulnerability, or a CRITICAL or HIGH finding, fails it; accepted findings are `scripts/images/trivyignore.yaml`, each with a reason and an expiry. The first findings: our three images, at the digests staging and production pin, have no fixable CRITICAL or HIGH. Production's release still names the retired `postgres` image (43 fixable, in its gosu and WAL-G binaries), which the chart hasn't run since ADR 0047; the next promotion drops it, and the scan reads only the images the chart runs. On the cluster, most vendors' images carry fixable HIGHs (Flux's controllers 11 to 17 each, Flagger 13, CloudNativePG's operator 10 and its plugin 11, Kyverno 4, cert-manager 1, the load tester 3 CRITICAL and 141 HIGH, CloudNativePG's PostgreSQL 2 CRITICAL and 30 HIGH, Valkey 3), mostly the Go runtime they were built with; and four misconfigurations: Kyverno's, its migration Job's and Flagger's pods had no pod-level security context (fixed: their values and a post-render patch), and the release's values ConfigMap "holds secrets" (a false positive: its `secret` keys name Secrets; accepted in `trivyignore.yaml` until 2027-10-31). The nightly job fails until each vendor is upgraded past its findings or a finding is accepted with a reason.

**Then every vendor was upgraded** to its latest stable release, pinned by digest, signatures verified where there are any (`cosign verify` against each policy's identity, and `kyverno test`): Flux v2.9.6 to v2.10.0 (its four controllers), Valkey 9.1.2 to 9.1.3, and the rest already at their latest (CloudNativePG 1.30.1 and its PostgreSQL 18.6, the Barman Cloud plugin v0.15.1, Kyverno v1.19.2, cert-manager v1.21.3, Flagger 1.45.0, Prometheus v3.15.1). Flagger's load tester went (below). Flux's controllers, Prometheus, Valkey, PostgreSQL, curl and cert-manager's cainjector and webhook are clean. What's left is in vendors' latest releases, mostly the Go they were built with: CloudNativePG's operator 10, the Barman Cloud plugin 11 and its sidecar 22 (two CRITICAL: certifi's emSign roots), Flagger 13, Kyverno 2 in each of its two images, cert-manager's controller 1, and PostgreSQL's image ships Debian's snakeoil key, which nothing uses. Each is accepted in `trivyignore.yaml` by its id and the binary or package it's in (`paths` or `purls`), with why and an expiry of 2026-12-30, 60 days on: `deploy.sh` refuses an entry with no reason, no scope or an expiry further than 60 days, and the scan fails on an expired one even if the finding has gone. The values ConfigMap's KSV-0109 is scoped to that ConfigMap. Nothing on our images is accepted: they're scanned without the file, at build and at night, for secrets too. Trivy 0.76.1 itself, in our scanner's image, carried go-jose 4.1.5 (CVE-2026-116587); no Trivy release has the fix yet, so the scanner builds Trivy from its v0.76.1 tag with go-jose 4.1.6 (`scanner/Dockerfile`), and staging's and production's scanner fail the scan until the next deploy carries that build.

**Renovate keeps them current** (`.github/renovate.json5`, `.github/workflows/renovate.yml`): Dependabot reads neither Flux's OCIRepositories and kustomize image overrides nor images inside Helm values. Every Monday one grouped pull request moves Flux, the vendors' charts and every image pinned in `deploy/` (and the checks' pinned tools) to the latest stable release, re-pinned by digest; its checks prove the signatures (`kyverno test`) and the cluster (the security, gitops and k8s workflows) before a person merges it. It runs as the deploy app (ADR 0046), with a token for that run that may write only contents and pull requests. Dependabot keeps everything else.

**A red scan opens a ticket.** The workflow pushes `idleo_nightly_scan_passed` (1 or 0) and the findings' count to Grafana Cloud over OTLP, as the other CI jobs that report do; `ops/alerts/security.yaml` raises NightlyScanFailing when the last one in 30 hours was 0, and NightlyScanMissing when none came, both at severity `digest`, the daily ticket route (docs/runbooks/alerts.md).

### kube-bench

kube-bench v0.16.0 ran the k3s CIS 1.9 benchmark (the newest it has targets for) against the k3d cluster (`scripts/k8s/kube-bench.sh`, a one-off Job in kube-system): 31 pass, 28 fail, 57 warn, 14 info. Most failures are the benchmark not reading k3s: its k3s profile reads the API server's flags from journald, which a k3d node doesn't have, while k3s's own log shows every one already set. What it found, and what happened:

- **Fixed:** 5.1.5 (default service accounts mounted tokens; now none does in our namespaces). 4.2.13 (no pod PID limit; the server's kubelet gets `pod-max-pids=4096`; the busiest pod on the Mac peaked at 598). 4.2.12 (the kubelet's ciphers; the server's config names the strong ones).
- **Not real** (k3s sets them; the flags are in its log): 1.2.1 anonymous auth off, 1.2.4 and 1.2.5 the kubelet's client certificate and CA, 1.2.6 to 1.2.8 authorization `Node,RBAC`, 1.2.10 and 1.2.14 admission plugins (`NodeRestriction`, no `AlwaysAdmit`), 1.2.15, 1.3.2 and 1.4.1 profiling off, 1.2.22, 1.2.24 and 1.2.25 the service-account key, serving certificate and client CA, 1.2.29 strong ciphers, 1.3.3 to 1.3.5 the controller manager's service-account credentials, key and root CA, 1.4.2 the scheduler on 127.0.0.1, 4.2.4 the kubelet's read-only port 0.
- **Not applicable:** 1.2.26 and the etcd section (one node on SQLite, no etcd).
- **Accepted:** 1.1.20 (certificates are 644: they're public; their keys are 600). 1.1.9 and 1.1.10 (the CNI file under k3d is 644 root:root; k3s writes it 600 on a server, rechecked in S5). 5.1.1 (cluster-admin: Flux, as above, and k3s's own). 5.1.3 (wildcards in Flux's, Kyverno's and the operators' roles, their vendors'). 5.1.6 (tokens are mounted only by the vendors' controllers, which call the API). The manual 5.x warnings are what this ADR does: Pod Security and admission policies (5.2), network policies in every namespace (5.3), signed images (5.5).
- **The server's flags** are `deploy/k3s/server/config.yaml`: secrets encryption, `protect-kernel-defaults` (with `server/sysctl.conf`), the admin kubeconfig 0600, the audit log, Pod Security's default, `service-account-lookup`, `terminated-pod-gc-threshold=10`, and for the kubelet a 5-minute idle stream timeout, the PID limit and strong ciphers.

### The proof

`scripts/k8s/security-test.sh` runs on the Mac's cluster under Flux (the `security` workflow: pull requests touching `deploy/`, `scripts/k8s/` or `scripts/deploy/`, and weekly). Every attempt that must fail has a control that may: Prometheus reaches the meters and the node's API port that the web pod can't, the control plane reaches the database that the scanner can't. Network probes run in a debug container inside the pod, which shares its network and so its policies. On the Mac, the last run: 60 checks, none failed, 347 s (S4: 52, 229 s, before check 12).

| # | What was tried | What happened |
| --- | --- | --- |
| 1 | an image under our name the images workflow didn't sign | refused (`idleo-images-signed: no signature`) |
| 1 | staging's control plane, signed with its bill of materials and provenance | admitted |
| 1 | the same, under another workflow's identity | refused |
| 1 | kyverno test on staging's policies | 28 of 28 |
| 2 | an image by tag; a debug container by tag | refused ("pinned by digest") |
| 3 | a root pod, a privileged pod, a hostPath pod | refused |
| 4 | from the web pod: PostgreSQL, Valkey, the meters, DNS, the internet | all unreachable |
| 5 | from the scanner pod: PostgreSQL, Valkey, the control plane's web and meters ports | unreachable; its internal API and the internet on 443 reachable, as allowed |
| 6 | from the web, scanner, control plane, Valkey and canary-load pods: 169.254.169.254 and the node's API port | all unreachable; Prometheus, the control, reaches both the node's port and the meters |
| 7 | from the control plane: PostgreSQL, Valkey, the internet on 443 | reachable |
| 8 | Valkey without a password, with a wrong one; its user's KEYS, FLUSHALL, CONFIG, DEBUG | NOAUTH, WRONGPASS, refused, refused, refused, refused; its PING answered |
| 9 | our pods' tokens; `default`'s rights | none on 13 pods; can't read Secrets or make pods |
| 10 | a Secret's value in k3s's datastore on disk | absent (the ConfigMap control present; `k8s:enc:aescbc:v1`; encryption Enabled) |
| 11 | Kyverno and a control-plane pod killed together | above |
| 12 | Kyverno cut off from the internet and restarted (its new pod's own probe can't reach GHCR) | a digest two verified Deployments name: admitted, 12 to 23 ms; never-seen digests of ours, unsigned and signed: refused, saying why; the database's primary killed: writes back at 20.3 s, the old primary rebuilt at 31.2 to 32.3 s; a control plane killed: back in 10.9 s; reconnected: the signed digest admitted |

Then the smoke, canary and failover tests, against S3's numbers: all pass.

| Test | S3 (ADR 0046, 0047) | Now |
| --- | --- | --- |
| Smoke: a booking to COMPLETED | 21 s | 21 s |
| Smoke: a rolling restart; the agent back | 27 s, 100 requests answered; 874 ms | 25 s, 86 answered; 879 ms |
| Canary: a good release to promotion | 849 s, 553 requests answered | 886 s (+4%), 573 answered |
| Canary: a bad release rolled back | 479 s | 479 s |
| Failover: a control plane killed; agents back | 1.9 s and 1.3 s, 0 failed | 1.8 s and 1.2 s, 0 of 114 failed |
| Failover: the database's primary killed | promoted in 19.6 to 19.8 s; writes refused 16.2 to 24.8 s | promoted in 20.3 s (+3%); writes refused 27.4 s (11% over S3's slowest run) |
| Failover: Valkey restarted | presence unknown 15.9 s | 15.9 s |
| Failover: both control planes drained | 0 failed | 0 of 26 failed |
| Failover: the restore drill | restored in 52.3 to 54.5 s; RPO up to 30 s | 60.9 s (12% over S3's slowest); RPO 28.2 s |

The two that moved more than 10% are the ones that make new database pods, and each new PostgreSQL pod now waits for Kyverno to check its signature (1.5 to 2.2 s an admission, measured); the restore makes several. Writes refused includes the control planes' pools reconnecting, which varied by 8.6 s across S3's own runs. The canary test needed one fix first: each step's load (`hey -z 2m`) ran the whole interval, and the load tester refuses a call while the last one runs, so a call that came a moment early started nothing and the next check found no requests ("no values found"), failing a good release. Each step's load then ran 110 s (`canary.loadSeconds`); since replaced by a plain loop ("The canary's load", below).

**The canary's load.** Flagger's load tester (`flagger-loadtester`) carried 3 CRITICAL and 141 HIGH, needed webhooks from Flagger and its own network rules, and refused a call while the last ran, the race above. A canary needs only a request a second to the canary's own Service for Flagger to judge, so `idleo-canary-load` is a pinned `curlimages/curl` that loops `curl` against `idleo-control-plane-canary` (5m CPU and 8 MiB requested, 16 MiB at most; no ingress; egress only to the control plane's 8080 and DNS). It runs all the time, so no step can start without load; with no canary the Service has no endpoints and each call fails at once, which costs about 10m of CPU and 1 MiB, measured. The webhooks, the load tester and `canary.loadSeconds` are gone.

**Rerun after the outage work, the upgrades and the load's change** (the canary test from a fresh cluster, the others on it), against S4's:

| Test | S4 | Now |
| --- | --- | --- |
| Canary: a good release to promotion | 886 s, 573 answered | 888 s, all 580 answered |
| Canary: a bad release rolled back | 479 s | 475 s |
| Failover: a control plane killed; agents back | 1.8 s and 1.2 s | 1.7 s and 1.4 s, 0 of 122 failed |
| Failover: the database's primary killed | promoted in 20.3 s; writes refused 27.4 s | 21.0 s (+3%); writes refused 19.9 s, within S3's 16.2 to 24.8 s |
| Failover: Valkey restarted | 15.9 s | 15.9 s |
| Failover: the restore drill | 60.9 s | 52.6 s, within S3's 52.3 to 54.5 s; RPO 10.4 s |
| Smoke: a booking to COMPLETED | 21 s | 21 s |
| Smoke: a rolling restart; the agent back | 25 s; 879 ms | 27, 32 and 33 s in three runs, every request answered; 231 to 569 ms |

The writes refused and the restore are back because a new PostgreSQL pod no longer waits for Kyverno: its image is known. The rolling restart isn't comparable: since S4 the smoke test waits for every old control plane to go, not only the first (its fix just before this round); each new pod is admitted at once, its init container done in 1 s (it waited 1.5 to 2.2 s for Kyverno before) and ready at 10 to 11 s.

## Alternatives considered

- **Kyverno ClusterPolicies (verifyImages and validate rules) for everything.** One engine, but ClusterPolicy is deprecated in Kyverno 1.19, and every rule would depend on Kyverno answering. Native ValidatingAdmissionPolicies run in the API server, so the digest, registry and privilege rules hold with Kyverno down; only signatures, which need the registry and the transparency log, wait for it.
- **Kyverno failing open (`failurePolicy: Ignore`).** Pods would never wait on Kyverno, but an attacker who can stop it (by exhausting its memory, say) could then run anything. Failing closed, with Kyverno's own namespace and kube-system exempt and Kyverno evicted last, keeps the check and the way back.
- **Sigstore's policy-controller, or Connaisseur, for signatures.** Each verifies keyless signatures too, but Kyverno also tests policies offline in CI (`kyverno test`), which is how staging's and production's policies are proved on every pull request without a cluster.
- **Cilium for the network, now.** FQDN egress, and an identity-based policy that would make the table shorter. It replaces k3s's flannel and network policy controller, costs more memory than kube-router, and changes the whole cluster's network for a test-mode service. It's the next step, with per-host egress (above).
- **The Trivy Operator.** Measured, and it doesn't fit (above). A nightly CI scan sees the same images and manifests, costs the server nothing, and fails where people look.
- **Valkey's password by `requirepass` or renamed commands.** One shared password for the default user, and renaming leaves the commands in place under another name. An ACL user with the commands removed is Valkey's own way, and a second password during a rotation is built in.
- **secretbox for encryption at rest.** The algorithm Kubernetes recommends over AES-CBC, but experimental in k3s. AES-CBC's weakness is a padding oracle, which needs someone asking the API server to decrypt, not reading a copy of the disk; switch when k3s calls secretbox stable.

## Consequences

- A broken-into web server, scanner or Valkey reaches nothing it doesn't need, and no pod reaches the metadata address, the node's API port or the server's networks: proved, check by check, on every pull request that touches the deployment.
- Nothing runs that isn't pinned and on the list; nothing of ours runs that the images workflow didn't sign and attest; a new vendor image is a reviewed line in `images-pinned.yaml`, and a new signed vendor a policy and a test case.
- While Kyverno is down, no new pod starts outside kube-system and its own namespace: a crash during a deploy or a failover waits for it (measured above). Each of our pods takes 12 to 14 s longer to admit than before, which a rolling update or a canary pays per pod.
- A new connection that isn't in the table times out. Adding one is a change in two places (both ends), a row here and a probe in the security test (docs/runbooks/network-rules.md).
- **Memory, measured** on the Mac's node under Flux: at rest the pods use about 2.0 GiB (Kyverno 56 MiB of it, 156 MiB at its peak under three verifications at once), and the node's working set was 4.4 to 5.1 GiB at rest and 5.5 GiB at a canary's peak (S3: 4.0 to 4.4 and 5.3), sampled every 10 s through the whole canary test; the pods themselves use what they did in S3 or less, and the rest is page cache from a day of image builds on the same node. Requested in production: 4.9 GiB at rest and 6.3 GiB at a canary's peak with Kyverno's 160 MiB, against 8 GB with about 1 GiB for the OS: about 0.75 GiB spare at the peak, where ADR 0047 had 1 GiB. Nothing existing was cut.
- **Memory, after the outage work** (sampled every 10 s through the whole canary test, 232 samples): the node's working set 4.4 GiB at rest and 5.6 GiB at the canary's peak (its promotion; S4 5.5 GiB), the pods 3.0 GiB at the peak; Kyverno 100 to 106 MiB with its watched copies of every pod controller (S4: 56 MiB at rest), inside its 160 MiB request. The load tester's 3 MiB became curl's 1 MiB.
- **What a second node changes:** the topology spread and anti-affinities (ADR 0047) spread the workloads, and Kyverno could run two copies (its PodDisruptionBudget on) so one node's loss doesn't stop admission. The network policies are by label, not address, so they hold across nodes; "traffic from the node itself" becomes traffic from either node, which kube-router allows the same way.
- S5 must: put `deploy/k3s/server/` in place before k3s first starts (the config, the audit and Pod Security files, the sysctl file); add the server's public address to `networkPolicy.internetExcept`; make the `idleo-valkey` Secret for staging and production; keep k3s's backups apart from its `server/cred` folder; and run kube-bench there, where journald lets it read k3s's flags.
- Open question: whether a cache for image verification arrives in Kyverno's ImageValidatingPolicy that keeps attestations (today's costs each entry by its size, so it never does) and survives a restart; it would take the 12 to 14 s off a new digest's every pod after the first, and the match condition above could then go.
- A new digest still can't start while GHCR or Sigstore is down: a deploy or a vendor upgrade waits for them, by design.
