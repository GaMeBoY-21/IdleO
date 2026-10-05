# control-plane

The Java 25 Spring Boot 4 control plane, built as Spring Modulith modules. The eleven modules and what each owns are listed in docs/plan/technical-design.md lines 534–546. It also holds the agents' gRPC streams.

Starts in Phase 1 (compute core): sessions, scheduler and leases.

Versions and the reasons for them are in `docs/decisions/0008-control-plane-build-and-versions.md`.

## What you need

- **Java 25.** On a Mac: `brew install openjdk@25`, then point `JAVA_HOME` at it. To make that permanent, add this line to `~/.zshrc`:
  ```
  export JAVA_HOME=/opt/homebrew/opt/openjdk@25/libexec/openjdk.jdk/Contents/Home
  ```
- **Docker**, for the Testcontainers tests and the local database.
- **Maven** comes from the wrapper (`./mvnw`), which pins the version. Don't use a system `mvn`; the enforcer rejects any other Maven version.

## Build and test

```
./mvnw verify
```

That one command runs everything, and any failure fails the build:
- the compile, with `-Xlint:all -Werror`
- the enforcer: Java 25, Maven 3.9.16, dependency convergence, no duplicate dependencies
- every test, including Flyway against a real PostgreSQL 18 in Testcontainers and the Spring Modulith boundary check
- the Spotless format check
- a JaCoCo report in `target/site/jacoco/`

`./mvnw spotless:apply` fixes formatting. The module documentation is written to `target/spring-modulith-docs/`.

## Run locally

The app needs at least one trusted ID token issuer. Copy `.env.example` to `.env` (gitignored) and fill in its "Local" section (see `docs/runbooks/identity-provider-setup.md`). Create the throwaway development CA once, then run:

```
scripts/dev-ca.sh
scripts/run-local.sh
```

The script loads `.env`, always uses the `local` profile, and refuses to start if `SPRING_PROFILES_ACTIVE` or `SPRING_PROFILES_INCLUDE` asks for `prod`. Production sets its profile and secrets in its deployment, never in a file.

Spring Boot starts PostgreSQL and Valkey from `compose.yaml` and applies the Flyway migrations. Readiness is then UP at `http://localhost:8080/actuator/health/readiness`, and the agents' gRPC port listens on 9443 (TLS 1.3, with the dev CA's `localhost` certificate). Stopping the app stops the database container; `docker compose down` removes it.

## Profiles

| Profile | Database | Logs |
| --- | --- | --- |
| `local` | `compose.yaml`, started by Spring Boot | Human-readable |
| `test` | Testcontainers; set automatically by the build | ECS JSON |
| `prod` | From the environment variables below | ECS JSON |

## Environment variables

Every variable is listed, with a fake value, in `.env.example`; a real `.env` stays out of git.
- **Identity issuers:** `IDENTITY_ISSUERS_0_ISSUERURI` and `IDENTITY_ISSUERS_0_AUDIENCE`. Every profile except `test` needs them, and the app refuses to start without one.
- **Database, Valkey, CA and gRPC TLS:** the `prod` profile reads them from the deployment's environment (see `docs/runbooks/certificate-authority.md`). They're not in `.env`; locally the dev CA stands in.

## What's here so far

- **Modules.** Eleven modules, one package each, with `@ApplicationModule`. Identity, hosts and agentgateway have code; the other eight are still empty.
- **Database.** One database schema per module, created by `db/migration/V1__create_module_schemas.sql`. Migrations always name their schema. Flyway owns all DDL and keeps its history in the `flyway` schema.
- **Errors.** Every error is RFC 9457 problem details (`application/problem+json`), including 401 and 403.
- **Security.** Deny by default: only `/actuator/health/liveness` and `/actuator/health/readiness` are open.
- **Identity** (ADR 0009). Every other request needs an ID token from a trusted issuer, belonging to an active user.
  - Users are created on first sign-in.
  - `GET /v1/me` and `PATCH /v1/me` serve the signed-in user's own account.
  - The provider's ids never leave the identity module.
- **Shared kernel**, in the root package: the one `Clock`, the one UUIDv7 generator (`IdGenerator`), and the `Idempotency-Key` helper (`IdempotentRequests`).
- **Hosts** (ADR 0010). `POST /v1/hosts` (with an `Idempotency-Key`), `GET /v1/hosts`, `GET /v1/hosts/{id}`, and `POST /v1/hosts/{id}/enrolment-code` for a one-time code.
- **Agent enrolment.** `EnrollmentService.Enroll` over gRPC on port 9443: TLS 1.3 only, client certificates optional. It trades a code and a CSR for a 30-day certificate from our own CA.
- **Agent stream** (ADR 0011). On the same port, `AgentGatewayService` requires a client certificate from our CA, for a live, unrevoked certificate of an unsuspended host.
  - `OpenStream` takes `Hello` (recording the host's OS, agent version and GPUs) and answers `Welcome`.
  - Heartbeats keep host presence in Valkey alive for 15 seconds.
  - `RenewCertificate` works in a certificate's last 10 days.
- **Generated code.** Java for `../proto` is generated during the build into `target/generated-sources/protobuf`, by a `protoc` the build downloads itself. Nothing generated is committed.
- **Health.** Readiness includes the database; liveness doesn't.

Other business features and the outbox arrive with the modules that need them.
