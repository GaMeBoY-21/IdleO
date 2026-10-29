# 0045. Containers, Helm and k3s

- **Status:** Accepted
- **Date:** 2026-10-29

## Context

Until now the server side ran only as a developer's processes: `./start.sh` and the end-to-end script start the control plane with `./mvnw spring-boot:run` beside compose.yaml's containers. Production needs one way to run the same parts on our own server (ADR 0036 put the database there), with the deployment runbook's two pre-launch items done properly. The first is two database users, so the user that serves requests can't drop the append-only tables' triggers. The second is the client's real address behind the ingress, so per-address limits (ADR 0025) can't be dodged. It also needs images we can prove we built, and a laptop that runs what the server runs.

## Decision

**Images.** Each server-side part is an image built from this repository: `control-plane`, `web`, `scanner` and `postgres`.

- `control-plane`: built with Maven and run from Spring Boot's layered jar on Eclipse Temurin's 25 JRE. The JRE is copied onto distroless `cc` (glibc, no shell, no package manager), and the heap is 65% of the container's limit.
- `web`: nginx-unprivileged serving the app and the website as two server blocks. The blocks are generated from each build's `headers.json`, `path-headers.json`, `redirects.json` and `rewrites.json` (`web/scripts/nginx-config.ts`), the same rules `serve.ts` applies.
- `scanner` and `postgres`: the existing images.

Every base is pinned by digest. Every image runs as a fixed non-root user on a read-only root and carries OCI labels for its source, revision and version. Valkey is the official image, pinned. `.github/workflows/images.yml` builds and scans every image with Trivy (pinned) on every pull request, and fails on any CRITICAL or HIGH finding that has a fix. On main and on a release tag, a second job loads the very bytes that were scanned and pushes them to `ghcr.io/gameboy-21/idleo-<name>:<commit>`. That job alone has `packages`, `id-token` and `attestations` write. It signs each image keylessly with cosign through GitHub's OIDC issuer (no key exists to leak), attaches a CycloneDX bill of materials from syft, and adds a build provenance attestation. `scripts/images/verify.sh` checks all three against this repository's workflow identity, by digest.

**One chart, k3s.** `deploy/helm/idleo` installs the control plane, scanner, web, Valkey and PostgreSQL (simple StatefulSets until CloudNativePG). Its default values are production's, and `values-local.yaml`, `values-staging.yaml` and `values-production.yaml` set only hosts, sizes, issuers and development's stand-ins. k3s runs it on one node, with k3s's own Traefik, local-path storage and network policy controller. Every pod has a fixed non-root user, the runtime's seccomp profile and no service-account token. Every container has a read-only root, all capabilities dropped, no privilege escalation, and requests and limits sized for one 8 GB node (values.yaml has the sums). A helm-unittest suite holds every pod to that, and kubeconform checks every environment's rendering strictly (`scripts/checks/deploy.sh`). On the Mac, `scripts/k8s/up.sh` makes a k3d cluster of the same k3s release (pinned by digest, one server, a registry of its own), builds the images, makes the local Secrets from the development CA and keys, and installs the chart with `values-local.yaml`. `smoke.sh` proves it end to end.

**Gateway API.** Browsers and API clients come in through the Gateway API (a Gateway and HTTPRoutes for the API, the app and the website), not Ingress objects. It is the Kubernetes standard, k3s ships its CRDs (v1.5) and Traefik implements it, and it carries the redirect and TLS settings in portable fields rather than Traefik's annotations. cert-manager issues the Gateway's certificate. Locally it comes from a CA of our own, made by `up.sh` and kept in `private/k8s/ca`, so a rebuilt cluster keeps a CA the Mac was told to trust once. The CA is name-constrained to `localhost` and the names under it and limited to TLS servers, so trusting it can't let it vouch for any other site. In production it will come from Let's Encrypt (S5).

**Agents bypass the ingress.** The agents' gRPC port is a LoadBalancer Service of its own, straight to the control plane's pods, so their mutual TLS ends at the control plane (ADR 0010). It is plain TCP: no proxy holds a certificate or sees a client certificate. A Traefik TLSRoute with passthrough would also keep TLS intact, but we chose the Service for three reasons:

- A restart or upgrade of Traefik would cut every agent's long-lived stream at once.
- The route depends on SNI, so every agent would have to use a name the route knows.
- The control plane sees each agent's own address (`externalTrafficPolicy: Local`).

**Two database users.** ops/postgres's first-start script makes `idleo_migration`, which owns the database and so every schema and table, and `idleo_app`, a login in the `idleo_application` role. Migration V61 grants that role each module's tables (never TRUNCATE), revokes UPDATE and DELETE on the 13 append-only tables, and from then on every migration grants what its new tables need. Next months' usage partitions, the one thing the application used to create, are made by a SECURITY DEFINER function that the migration user owns. The control plane runs on the `cluster` profile (`application-cluster.yaml`) with Flyway off, as `idleo_app`. Flyway runs only in the chart's migration Job, as `idleo_migration`, from `com.idleo.migration.DatabaseMigration`: the datasource and Flyway, nothing else. The Job is a `pre-upgrade` hook, so a release's schema is migrated before its pods roll. On a first install it is a `post-install` hook, since the database doesn't exist before then; the control plane's init container waits for the schema. `ApplicationRoleTests` connects as the application user and fails on any UPDATE, DELETE or TRUNCATE on an append-only table, any object it owns, any schema it could create in, and any table a migration left ungranted. `./start.sh` and the tests still run Flyway as the database's superuser, as before.

**The client's real address.** The cluster profile turns on `server.forward-headers-strategy: native` and sets Tomcat's internal proxies to `INGRESS_PROXIES`, the pod network. A network policy lets only Traefik's pods reach the control plane's web port, so "from the pod network" means "from the ingress". A made-up X-Forwarded-For from anywhere else is ignored (`ForwardedAddressTests`, and `smoke.sh` through Traefik). The prod profile refuses forwarded headers without the internal proxies set (`ProductionSettingsGuard`), because Tomcat's default believes every private address.

**Rolling updates lose no agent.** One replica with `maxSurge: 1, maxUnavailable: 0`: the new pod is ready before the old one stops. The old one waits out a `preStop` sleep while the load balancers drop it. Then, on Spring's context-closed event, before any lifecycle stops, it ends every agent stream with `UNAVAILABLE` (`ReconnectOnShutdown`). The agent already reconnects with full-jitter backoff (agent/src/backoff.rs), and the load balancer sends it to the new pod. Spring's graceful shutdown then drains HTTP.

**What comes later.** S2 adds Flux and SOPS-encrypted Secrets, replacing `scripts/k8s/secrets.sh`. S3 replaces the PostgreSQL StatefulSet with CloudNativePG and its backups. S4 adds the cluster policies, which check every image's signature as `verify.sh` does. S5 brings the real server, with Let's Encrypt, its Traefik ports 80 and 443, and canaries.

## Alternatives considered

- **Docker Compose on the server.** It is the shape `./start.sh` already has, but it has no rolling update, no readiness gate on traffic, no network policy and no hooks. We would have had to write each of those ourselves.
- **Full Kubernetes (kubeadm) or a managed cluster.** One node doesn't need etcd's quorum or a cloud's control plane. k3s is a certified distribution in one binary, with the ingress, storage and network policy we need, and k3d runs the same release in Docker on a laptop.
- **Kustomize instead of Helm.** Overlays fit "differ only in what must differ", but hooks (the migration Job before an upgrade's pods) and rendering one pod on its own (an operator command) would have needed more tooling. Helm has both, and Flux (S2) drives Helm releases natively.
- **Flyway in the control plane at startup, as the migration user.** Every instance would then hold the owner's password, and two starting at once would race on Flyway's lock. A separate Job holds that password alone and runs once per release.
- **Terminating the agents' TLS at Traefik and re-encrypting.** It breaks mutual TLS: the control plane would see Traefik's certificate, not the agent's (ADR 0010).
- **An HTTPS endpoint for development's sign-in server and storage.** The control plane's JVM and the agent on the Mac would each need the local CA as a trust anchor. On macOS that means the Keychain, which needs a password the scripts never ask for. They stay plain HTTP on their own ports, as in `./start.sh`, at names the cluster's DNS maps to their Services, so a token's issuer and a presigned link are the same inside and out. Production uses Identity Platform and R2 over HTTPS.

## Consequences

- The Mac runs what the server will run: the same images, chart and k3s release. It fits in Docker Desktop's memory beside `./start.sh`'s stack only while both idle (3.9 of 7.75 GiB), not at the chart's limits, so `up.sh` stops that stack first.
- A migration must work with the code before it as well as after (it runs before the old pods go), as it already must for a rolling update. Every new table's migration grants `idleo_application`, or `ApplicationRoleTests` fails.
- The web image's build bakes in its addresses, so the published one is the local cluster's. A production fallback image is built with production's build arguments when the server exists (S5).
- An operator command in a cluster is a one-off pod with the control plane's image and settings (`scripts/k8s/ops.sh`), never a second JVM inside a serving pod.
- Images have no shell: debugging is `kubectl debug` with an ephemeral container (docs/runbooks/kubernetes.md).
- Open question: whether agents on networks that block every port but 443 need the passthrough route after all. If the pilot shows such hosts, a TLSRoute on 443 beside the Service would serve them without changing the others.
