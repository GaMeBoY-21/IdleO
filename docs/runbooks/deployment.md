# Deployment

What a production deployment needs that the local stack doesn't, and what must be done before launch. The deployment round (docs/plan/execution-plan.md) fills in the rest: staging and production, R2, the domain and monitoring.

## Secrets the control plane won't start without

Each comes from the secret store as an environment variable; the prod profile refuses to start without it, or with the value the tests commit (`ProductionSettingsGuard`, ADR 0013).

| Variable | What it is | Changing it |
| --- | --- | --- |
| `IDENTITY_ADMIN_TOTP_KEY` | 32 random bytes in base64: seals the admins' TOTP secrets (ADR 0027) | Every admin must be revoked and granted again (docs/runbooks/admin.md) |
| `IDENTITY_INVITE_CODE_KEY` | 32 random bytes in base64: the HMAC key invite codes and admin recovery codes are stored under (ADR 0024) | Every unused invite code stops working: make new ones (docs/runbooks/invites.md); admins get new recovery codes by being revoked and granted again |

Make one with `openssl rand -base64 32`. Never reuse a key across staging and production.

## Before launch: two database users

Today the control plane runs as the database's owner, so the same user that runs Flyway's migrations also serves requests. The audit log is append-only by trigger (ADR 0027), but an owner can drop a trigger. Before launch:

1. **A migration user** that owns every schema and table. Flyway runs as it (`SPRING_FLYWAY_USER`, `SPRING_FLYWAY_PASSWORD`), at deployment, and nothing else does.
2. **An application user** the control plane serves requests as (`SPRING_DATASOURCE_USERNAME`), granted only what it needs:
   - `USAGE` on each module's schema, and `SELECT`, `INSERT`, `UPDATE`, `DELETE` on its tables, except as below;
   - on `identity.audit_log`, `billing.ledger_entries` and `billing.reservation_changes`: `SELECT` and `INSERT` only, no `UPDATE`, `DELETE` or `TRUNCATE`;
   - `USAGE` on the sequences it needs;
   - no DDL: no `CREATE` on any schema, and it owns nothing.
3. Flyway's migrations then grant the application user what each new table needs, in the same migration that creates it, so a table can't ship without its grants.

**The test to add once the split exists:** a Testcontainers test that connects as the application user and lists its privileges from `information_schema.role_table_grants` and `pg_namespace` (`has_schema_privilege(..., 'CREATE')`), and fails if it has any `UPDATE`, `DELETE` or `TRUNCATE` on the append-only tables, owns any object, or can create in any schema. Until then the database refuses edits to those tables only through their triggers.

## Before launch: the address a request comes from

The per-address limits (calls without a sign-in, CLI sign-ins started an hour, ADR 0025) count `request.getRemoteAddr()`. Behind a load balancer that is the load balancer's address, so every visitor would share one counter. Before launch:

- set `server.forward-headers-strategy: native` (or `framework`) so the control plane reads the client's address from the forwarded header;
- trust that header **only from the load balancer's own addresses** (Tomcat's `RemoteIpValve` `internal-proxies`, or the platform's equivalent), so a client can't write its own `X-Forwarded-For` to dodge or burn someone else's limit;
- check it: two clients behind the load balancer get separate counters, and a request carrying a made-up `X-Forwarded-For` from outside is counted under its real address.

## Before launch: the website and the app have two addresses (ADR 0029)

The website is at the root address (`https://idleo.in/`) and the app at its own (`https://app.idleo.in/`). They are two builds with two outputs, deployed as two sites:

- **Build the app and the website with both settings,** `IDLEO_SITE_URL=https://idleo.in` and `IDLEO_APP_URL=https://app.idleo.in`: `pnpm build` makes `web/dist/` (the app) and `pnpm build:site` makes `web/dist-website/` (the website). A deployed build without either, or with one that is plain HTTP or on the machine, is refused (`scripts/checks/web.sh` checks both builds refuse it).
- **Serve each folder from its own host,** with that folder's `headers.json` as response headers. The website's `redirects.json` is `/about/` to `/`: send it as a permanent redirect; the page written at `/about/` sends the browser on where a host can't.
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
| Real visits' errors, named by file and line | `VITALS_SOURCE_MAPS`: a folder holding the deployed web builds' `dist-sourcemaps/` and `dist-website-sourcemaps/` (never served) | monitoring.md |

And beside the control plane:

- **The database runs `ops/postgres/`'s image** (PostgreSQL with WAL-G), with `WALG_S3_PREFIX`, `AWS_ENDPOINT`, `AWS_REGION`, `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` (an R2 token with read and write on the backup bucket, which must exist first) and `WALG_PGP_KEY_PATH` (the backup key's public half, mounted read-only). Its nightly backup is `ops/backup/systemd/idleo-backup.timer` (backups.md).
- **The application's database user gets `pg_monitor`** (`GRANT pg_monitor TO <application user>`), so the backup watch can see WAL waiting to be archived. Without it the no-backup alert fires, which is how you'd notice.
- **Only `/actuator/health/liveness` and `/readiness` are reachable from outside.** The load balancer waits for readiness (database, storage, CA) before sending traffic; the external uptime check reads liveness.
- **The web builds are made with `IDLEO_RELEASE`** set to the same release, so a JavaScript error names it, and their `*-sourcemaps/` folders go to `VITALS_SOURCE_MAPS`, never to the site's host.
