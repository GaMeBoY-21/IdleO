# Deployment

What a production deployment needs that the local stack doesn't, and what must be done before launch. The server side runs from signed images and one Helm chart on k3s (ADR 0045, kubernetes.md); the deployment rounds fill in the rest: Flux and SOPS, CloudNativePG, the cluster's policies, and the server with its domain.

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

The database's first start makes both users from the passwords in its Secret (`ops/postgres/initdb/10-idleo-users.sh`). `ApplicationRoleTests` connects as the application user, reads its own privileges, and fails on any `UPDATE`, `DELETE` or `TRUNCATE` on an append-only table, any object it owns, any schema it can create in, or any table left ungranted. `scripts/k8s/smoke.sh` checks the same in the cluster. `./start.sh` and the tests still run Flyway as the database's superuser.

## The address a request comes from (ADR 0045)

The per-address limits (calls without a sign-in, CLI sign-ins started an hour, ADR 0025) count `request.getRemoteAddr()`. Behind the ingress that would be the ingress's address, so every visitor would share one counter. In a cluster:

- the `cluster` profile sets `server.forward-headers-strategy: native`, so the control plane reads the client's address from X-Forwarded-For;
- Tomcat believes that header only from `INGRESS_PROXIES`, the pod network, and the chart's network policy lets only Traefik's pods reach the web port. A client can't write its own header to dodge or burn someone else's limit. The prod profile refuses forwarded headers without `server.tomcat.remoteip.internal-proxies` (`ProductionSettingsGuard`), because Tomcat's default believes every private address;
- Traefik's Service keeps the client's own address (`externalTrafficPolicy: Local`), so the header it writes is the client's.

`ForwardedAddressTests` checks both directions on the cluster profile's Tomcat. `scripts/k8s/smoke.sh` checks it through Traefik: a request with a made-up X-Forwarded-For counts under the address the ingress saw. On the server (S5), also check that two clients get separate counters.

## Before launch: the website and the app have two addresses (ADR 0029)

The website is at the root address (`https://idleo.in/`) and the app at its own (`https://app.idleo.in/`). They are two builds with two outputs, deployed as two sites:

- **Build the app and the website with both settings,** `IDLEO_SITE_URL=https://idleo.in` and `IDLEO_APP_URL=https://app.idleo.in`: `pnpm build` makes `web/dist/` (the app) and `pnpm build:site` makes `web/dist-website/` (the website). A deployed build without either, or with one that is plain HTTP or on the machine, is refused (`scripts/checks/web.sh` checks both builds refuse it).
- **Serve each folder from its own host,** with that folder's `headers.json` as response headers, except a page listed in the app's `path-headers.json` (the Credit page, `/credit/`), which gets those headers instead: its own security policy lets Razorpay's Checkout run there and nowhere else (ADR 0039). The app's build also needs `VITE_CHECKOUT_URL=https://checkout.razorpay.com/v1/checkout.js`; a deployed build refuses any other. The website's `redirects.json` is `/about/` to `/`: send it as a permanent redirect; the page written at `/about/` sends the browser on where a host can't.
- **Serve `/link/<id>/` with the app's `/link/index.html`** (a rewrite, not a redirect): the approval page's address carries the link's id, and no such folder exists. `web/dist/rewrites.json` lists it. Set `HOSTS_LINK_WEB_APP_URL` (else `IDENTITY_WEB_APP_URL`) on the control plane to `https://app.idleo.in`, since that is the base of the address the agent opens; production refuses plain HTTP and localhost.
- **CORS:** the API's allowed origins (`web.allowed-origins`, `WEB_ALLOWED_ORIGINS`) must be exactly `https://app.idleo.in` **and** `https://idleo.in`. The app calls the API with a bearer token, and the website's pricing and earn pages ask for the live prices (`GET /v1/public/availability`); a missing origin makes the live figures fall back to "typical". Production refuses `*`, plain HTTP and localhost (`ProductionSettingsGuard`).
- **The identity provider's authorised domains** need `app.idleo.in` (Firebase Authentication, Authorized domains, and the sign-in link's domain), since sign-in happens in the app; the website never signs anyone in. Add the app's address to the redirect addresses of every sign-in method.
- **Check it:** from `https://idleo.in/`, "Get started" lands on `https://app.idleo.in/sign-in/?start=1`, sign-in returns to the app's Home, and the browser console shows no CORS error on the website's pricing page.

## Monitoring, backups and email (ADR 0036)

The prod profile refuses to start without each of these (`ProductionSettingsGuard`); the variables are listed with fake values in `control-plane/.env.example`.

| What | Variables | Runbook |
| --- | --- | --- |
| Metrics, traces and logs, to Grafana Cloud's OTLP endpoint | `OTLP_ENDPOINT`, `OTLP_AUTHORIZATION`; and `IDLEO_RELEASE` (the build), `IDLEO_ENVIRONMENT` (`production`), `IDLEO_INSTANCE` (a stable name for each instance, so a redeploy keeps its series) | monitoring.md |
| The backups, as the control plane watches them | `BACKUPS_STORAGE_ENDPOINT`, `_BUCKET`, `_PREFIX`, `_REGION`, `_ACCESS_KEY_ID`, `_SECRET_ACCESS_KEY` (an R2 token that may only read the bucket) | backups.md |
| Email through Resend, and its bounce webhook | `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `NOTIFICATIONS_EMAIL_FROM` (on our domain), `NOTIFICATIONS_EMAIL_WEBHOOK_SECRET` | email.md |
| Deleting an account at the sign-in provider (ADR 0037) | `IDENTITY_PROVIDER_ADMIN_CREDENTIALS`: the service account's JSON key, or `file:` and its path | identity-provider-setup.md, "12. A service account for erasing accounts" |
| The grievance officer on Contact and Privacy (ADR 0037), LEGAL(confirm) | `LEGAL_GRIEVANCE_NAME`, `LEGAL_GRIEVANCE_EMAIL`, `LEGAL_GRIEVANCE_POSTAL_ADDRESS` (development's placeholders are refused) | breach.md |
| Real visits' errors, named by file and line | `VITALS_SOURCE_MAPS`: a folder holding the deployed web builds' `dist-sourcemaps/` and `dist-website-sourcemaps/` (never served) | monitoring.md |

And beside the control plane:

- **The database runs `ops/postgres/`'s image** (PostgreSQL with WAL-G), with `WALG_S3_PREFIX`, `AWS_ENDPOINT`, `AWS_REGION`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` (an R2 token with read and write on the backup bucket, which must exist first) and `WALG_PGP_KEY_PATH` (the backup key's public half, mounted read-only). Its nightly backup is `ops/backup/systemd/idleo-backup.timer` (backups.md).
- **The application's database user gets `pg_monitor`** (`GRANT pg_monitor TO <application user>`), so the backup watch can see WAL waiting to be archived. Without it the no-backup alert fires, which is how you'd notice.
- **Only `/actuator/health/liveness` and `/readiness` are reachable from outside.** The load balancer waits for readiness (the database and the CA; object storage isn't part of it, ADR 0036) before sending traffic; the external uptime check reads liveness.
- **The web builds are made with `IDLEO_RELEASE`** set to the same release, so a JavaScript error names it, and their `*-sourcemaps/` folders go to `VITALS_SOURCE_MAPS`, never to the site's host.

## The database is ours (ADR 0036)

Production runs **our own PostgreSQL**, `ops/postgres/`'s image with WAL-G, on our server, not a managed database (decided 2026-10-21). That means:

- **Its disk is ours to watch:** the disk alert covers it (alerts.md, "Disk"); give the database its own volume, so a full root disk can't stop it.
- **Backups and restores are ours:** WAL archived at least every 30 seconds and a full backup nightly (backups.md); rehearse a restore monthly.
- **Upgrades are ours:** a PostgreSQL minor release is a new pinned digest in `ops/postgres/Dockerfile` (and the tests' image, which a test keeps identical), rolled out by restarting the container; a major release is a restore into the new version, rehearsed first with the drill.
- **No standby yet:** a lost server is a restore onto a new one (backups.md, "Restoring"), under 4 hours by the technical design's target. A standby with failover is for when the pilot needs it.
