# Deployment

What a production deployment needs that the local stack doesn't, and what must be done before launch. The server side runs from signed images and one Helm chart on k3s (ADR 0045, kubernetes.md); Flux deploys it from git, with SOPS-encrypted Secrets and canary releases (ADR 0046, deploying.md); the deployment rounds fill in the rest: CloudNativePG, the cluster's policies, and the server with its domain.

## Secrets the control plane won't start without

Each comes from the secret store as an environment variable; the prod profile refuses to start without it, or with the value the tests commit (`ProductionSettingsGuard`, ADR 0013).

| Variable | What it is | Changing it |
| --- | --- | --- |
| `IDENTITY_ADMIN_TOTP_KEY` | 32 random bytes in base64: seals the admins' TOTP secrets (ADR 0027) | Every admin must be revoked and granted again (docs/runbooks/admin.md) |
| `IDENTITY_INVITE_CODE_KEY` | 32 random bytes in base64: the HMAC key invite codes and admin recovery codes are stored under (ADR 0024) | Every unused invite code stops working: make new ones (docs/runbooks/invites.md); admins get new recovery codes by being revoked and granted again |

Make one with `openssl rand -base64 32`. Never reuse a key across staging and production.

## Two database users (ADR 0045)

In a cluster the control plane no longer runs as the database's owner. The owner could drop the triggers that keep the audit log and the ledger append-only, so the work is split:

1. **A migration user,** `idleo_migration`, owns the database and so every schema and table. Only the chart's migration Job runs as it, running Flyway from `com.idleo.migration.DatabaseMigration`. The Job is a Helm hook: before an upgrade's pods roll, and after a first install once the database is up.
2. **An application user,** `idleo_app`, is the login the control plane serves requests as, on the `cluster` profile with Flyway off. It is a member of `idleo_application` and of `pg_monitor`, so the backup watch can see WAL waiting to be archived. Migration V61 grants the role:
   - `USAGE` on each module's schema, and `SELECT`, `INSERT`, `UPDATE`, `DELETE` on its tables, never `TRUNCATE`;
   - on the 13 append-only tables (`identity.audit_log`, `billing.ledger_entries`, `billing.reservation_changes` and the rest whose triggers refuse every change): `SELECT` and `INSERT` only;
   - `USAGE` on the sequences;
   - no DDL: no `CREATE` on any schema, and it owns nothing. Next months' usage partitions come from `metering.ensure_usage_partition`, a function the migration user owns.
3. **Every new table's migration grants the role what the table needs,** in the same migration, so a table can't ship without its grants.

CloudNativePG makes both users, as the Cluster's managed roles, from the passwords in their Secrets (`templates/database.yaml`, ADR 0047; database.md says how to add one). `ApplicationRoleTests` connects as the application user, reads its own privileges, and fails on any `UPDATE`, `DELETE` or `TRUNCATE` on an append-only table, any object it owns, any schema it can create in, or any table left ungranted. `scripts/k8s/smoke.sh` runs it against the cluster's database. `./start.sh` and the tests still run Flyway as the database's superuser.

## The address a request comes from (ADR 0045)

The per-address limits (calls without a sign-in, CLI sign-ins started an hour, ADR 0025) count `request.getRemoteAddr()`. Behind the ingress that would be the ingress's address, so every visitor would share one counter. In a cluster:

- the `cluster` profile sets `server.forward-headers-strategy: native`, so the control plane reads the client's address from X-Forwarded-For;
- Tomcat believes that header only from `INGRESS_PROXIES`, the pod network, and the chart's network policy lets only Traefik's pods reach the web port. A client can't write its own header to dodge or burn someone else's limit. The prod profile refuses forwarded headers without `server.tomcat.remoteip.internal-proxies` (`ProductionSettingsGuard`), because Tomcat's default believes every private address;
- Traefik's Service keeps the client's own address (`externalTrafficPolicy: Local`), so the header it writes is the client's.

`ForwardedAddressTests` checks both directions on the cluster profile's Tomcat. `scripts/k8s/smoke.sh` checks it through Traefik: a request with a made-up X-Forwarded-For counts under the address the ingress saw. On the server (S5), also check that two clients get separate counters.

## Before launch: four sites, four addresses (ADR 0029, ADR 0058)

The website is at the root address (`https://idleo.in/`), the renter app at `https://app.idleo.in/`, the host site at `https://host.idleo.in/` and, once it moves, the admin console at `https://admin.idleo.in/`. Each is its own build with its own output, deployed as its own site:

- **Build each with every address,** `IDLEO_SITE_URL=https://idleo.in`, `IDLEO_APP_URL=https://app.idleo.in` and `IDLEO_HOST_URL=https://host.idleo.in`: `pnpm build` makes `web/dist/` (the renter app) and `web/dist-host/` (the host site), and `pnpm build:site` makes `web/dist-website/` (the website). A deployed build without one of them, or with one that is plain HTTP or on the machine, is refused (`scripts/checks/web.sh` checks the builds refuse it).
- **Serve each folder from its own host,** following the rules the build wrote beside its pages: `headers.json` as response headers, except a page listed in `path-headers.json` (the renter app's Credit page, `/credit/`), which gets those headers instead, since its own security policy lets Razorpay's Checkout run there and nowhere else (ADR 0039); `redirects.json` as permanent redirects, keeping the query; `rewrites.json` as rewrites. The renter app's build also needs `VITE_CHECKOUT_URL=https://checkout.razorpay.com/v1/checkout.js`; a deployed build refuses any other.
- **The renter app's old hosting addresses redirect** to the host site's (`redirects.json`): `/hosting/` to `/`, `/hosting/machine/?id=…` to `/laptop/?id=…`, `/link/<id>/` to the host site's `/link/<id>/`, and the rest as `src/sites/host/paths.ts` lists them, so emails, bookmarks and older Windows apps keep working. The website's `redirects.json` is `/about/` to `/`.
- **Serve `/link/<id>/` with the host site's `/link/index.html`** (a rewrite, not a redirect): the approval page's address carries the link's id, and no such folder exists. Set `HOSTS_LINK_WEB_APP_URL` on the control plane to `https://host.idleo.in`, since that is the base of the address the agent opens; production refuses plain HTTP and localhost.
- **The control plane's email links:** `NOTIFICATIONS_WEB_APP_URL=https://app.idleo.in` for a renter's, `NOTIFICATIONS_HOST_SITE_URL=https://host.idleo.in` for a host's; production refuses either missing, plain HTTP or on the machine.
- **CORS:** the API's allowed origins (`web.allowed-origins`, `WEB_ALLOWED_ORIGINS`) must be exactly `https://app.idleo.in`, `https://host.idleo.in` **and** `https://idleo.in`. The sites call the API with a bearer token, and the website's pricing and earn pages ask for the live prices (`GET /v1/public/availability`); a missing origin makes the live figures fall back to "typical". Production refuses `*`, plain HTTP and localhost (`ProductionSettingsGuard`). Storage's CORS (object-storage.md) allows the renter app and the host site: an appeal's evidence is uploaded from either.
- **The identity provider's authorised domains** need `app.idleo.in` and `host.idleo.in` (and `admin.idleo.in` with the console's move): Firebase Authentication, Authorized domains, and the sign-in link's domain, since each site signs in on its own origin; the website never signs anyone in. Add every site's address to the redirect addresses of every sign-in method.
- **DNS:** a record for `host.` (and `admin.`) beside `app.`, pointing where each site is served (below).
- **Check it:** from `https://idleo.in/`, "Get started" lands on `https://app.idleo.in/sign-in/?start=1` and "Start hosting" on `https://host.idleo.in/sign-in/`; sign-in returns to each site's Home; `https://app.idleo.in/hosting/machine/?id=1` arrives at `https://host.idleo.in/laptop/?id=1`, query and all; and the browser console shows no CORS error on the website's pricing page.

### Four sites on Cloudflare Pages (ADR 0058)

One Pages project per site, each from the same repository and the same commit, each with its own build command and output folder. Every build writes Pages' own `_headers` and `_redirects` from the rules above (`scripts/pages-config.ts`), so nothing is set by hand in the dashboard but the project itself.

| Project | Custom domain | Build command (in `web/`) | Output folder | Build settings |
| --- | --- | --- | --- | --- |
| `idleo-website` | `idleo.in` | `pnpm install --frozen-lockfile && pnpm build:site` | `web/dist-website` | the three addresses, `VITE_API_URL`, `IDLEO_DEPLOYMENT=production`, `IDLEO_RELEASE` |
| `idleo-app` | `app.idleo.in` | `pnpm install --frozen-lockfile && IDLEO_WEB_SITES=app pnpm build` | `web/dist` | the three addresses, `VITE_API_URL`, `VITE_STORAGE_URL`, `VITE_CHECKOUT_URL`, `VITE_AUTH=firebase` and Firebase's three, `IDLEO_DEPLOYMENT=production`, `IDLEO_RELEASE` |
| `idleo-host` | `host.idleo.in` | `pnpm install --frozen-lockfile && IDLEO_WEB_SITES=host pnpm build` | `web/dist-host` | the same as `idleo-app` |
| `idleo-admin` | `admin.idleo.in` | made with the console's move to its own site | | |

- **Root directory** is the repository's root (the builds read `api/`, `templates/` and `docs/site/`); **Node** is `web/.node-version`'s (`NODE_VERSION`), and pnpm comes from `packageManager` through corepack (`COREPACK_ENABLE_STRICT=1`).
- **No source maps are served:** each build moves them into `<folder>-sourcemaps/`, which isn't the output folder; keep them for `VITALS_SOURCE_MAPS` (below).
- **First-deploy check of the redirects:** Pages' documentation doesn't say whether `_redirects` passes the query on. Ask for `https://app.idleo.in/hosting/machine/?id=1` and check it arrives at `https://host.idleo.in/laptop/?id=1`. If the query is dropped, replace those lines with one Single Redirect rule per old address on the `app.idleo.in` zone, with "Preserve query string" on, and say so in ADR 0058.
- **Preview deployments** get their own addresses, which the API's CORS doesn't allow: a preview shows its pages, but not live data. That's intended.

## Monitoring, backups and email (ADR 0036)

The prod profile refuses to start without each of these (`ProductionSettingsGuard`); the variables are listed with fake values in `control-plane/.env.example`.

| What | Variables | Runbook |
| --- | --- | --- |
| Metrics, traces and logs, to Grafana Cloud's OTLP endpoint | `OTLP_ENDPOINT`, `OTLP_AUTHORIZATION`; and `IDLEO_RELEASE` (the build), `IDLEO_ENVIRONMENT` (`production`), `IDLEO_INSTANCE` (a stable name for each instance, so a redeploy keeps its series) | monitoring.md |
| The backups, as the control plane watches them | `BACKUPS_STORAGE_ENDPOINT`, `_BUCKET`, `_PREFIX`, `_REGION`, `_ACCESS_KEY_ID`, `_SECRET_ACCESS_KEY` (an R2 token that may only read the bucket) | backups.md |
| The audit log's off-site store | `AUDIT_OFFSITE_ENDPOINT`, `_BUCKET`, `_ACCESS_KEY_ID`, `_SECRET_ACCESS_KEY` (an R2 token for that bucket only, ADR 0051) | audit-chain.md |
| The admin console's passkeys | `IDENTITY_ADMIN_PASSKEYS_RP_ID`: the web app's registrable domain, without a scheme. Changing it later strands every passkey, though codes still work (ADR 0051) | admin.md |
| Email through Resend, and its bounce webhook | `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `NOTIFICATIONS_EMAIL_FROM` (on our domain), `NOTIFICATIONS_EMAIL_WEBHOOK_SECRET` | email.md |
| Deleting an account at the sign-in provider (ADR 0037) | `IDENTITY_PROVIDER_ADMIN_CREDENTIALS`: the service account's JSON key, or `file:` and its path | identity-provider-setup.md, "12. A service account for erasing accounts" |
| The grievance officer on Contact and Privacy (ADR 0037), LEGAL(confirm) | `LEGAL_GRIEVANCE_NAME`, `LEGAL_GRIEVANCE_EMAIL`, `LEGAL_GRIEVANCE_POSTAL_ADDRESS` (development's placeholders are refused) | breach.md |
| Real visits' errors, named by file and line | `VITALS_SOURCE_MAPS`: a folder holding the deployed web builds' `dist-sourcemaps/`, `dist-host-sourcemaps/` and `dist-website-sourcemaps/` (never served) | monitoring.md |

And beside the control plane:

- **The database is CloudNativePG's Cluster** (ADR 0047), with its backups through the Barman Cloud plugin to R2: the bucket must exist first, and its address, bucket and token are the Secrets `r2-backups` and `idleo-backup-storage` (deploying.md, backups.md).
- **Only `/actuator/health/liveness` and `/readiness` are reachable from outside.** The load balancer waits for readiness (the database and the CA; object storage isn't part of it, ADR 0036) before sending traffic; the external uptime check reads liveness.
- **The web builds are made with `IDLEO_RELEASE`** set to the same release, so a JavaScript error names it, and their `*-sourcemaps/` folders go to `VITALS_SOURCE_MAPS`, never to the site's host.

## The database is ours (ADR 0036, ADR 0047)

Production runs **our own PostgreSQL**, on our server, not a managed database (decided 2026-10-21): since ADR 0047 a CloudNativePG Cluster of a primary and a standby on the same node. That means:

- **Its disk is ours to watch:** the disk alert covers it (alerts.md, "Disk"); each instance has its own volume.
- **Backups and restores are ours:** WAL archived at least every 30 seconds and a base backup daily (backups.md); rehearse a restore monthly.
- **Upgrades are ours:** a PostgreSQL minor release is a new pinned image in the chart's `database.image`, rolled out by the operator standby first, then a switchover; a major release is a restore into the new version, rehearsed first with the drill. The operator and its plugin are upgraded by their pins in `deploy/infrastructure/base/cloudnative-pg.yaml`.
- **One node:** the standby takes over when the primary's process, pod or disk fails, but not when the server does; a lost server is a restore onto a new one (backups.md). More nodes put the instances apart (ADR 0047, "What three nodes add").
