# control-plane

The Java 25 Spring Boot 4 control plane, built as Spring Modulith modules. The eleven modules and what each owns are listed in docs/plan/technical-design.md lines 533–545. It also holds the agents' gRPC streams.

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

```
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Spring Boot starts PostgreSQL from `compose.yaml` and applies the Flyway migrations. Readiness is then UP at `http://localhost:8080/actuator/health/readiness`. Stopping the app stops the database container; `docker compose down` removes it.

## Profiles

| Profile | Database | Logs |
| --- | --- | --- |
| `local` | `compose.yaml`, started by Spring Boot | Human-readable |
| `test` | Testcontainers; set automatically by the build | ECS JSON |
| `prod` | From the environment variables below | ECS JSON |

## Environment variables

Only the `prod` profile reads environment variables. Every one is listed, with a fake value, in `.env.example`; a real `.env` stays out of git.

## What's here so far

- **Modules.** Eleven empty modules, one package each, with `@ApplicationModule`.
- **Database.** One database schema per module, created by `db/migration/V1__create_module_schemas.sql`. Flyway owns all DDL and keeps its history in the `flyway` schema.
- **Errors.** Every error is RFC 9457 problem details (`application/problem+json`), including 401 and 403.
- **Security.** Deny by default: only `/actuator/health/liveness` and `/actuator/health/readiness` are open.
- **Health.** Readiness includes the database; liveness doesn't.

Business features, the outbox and Valkey arrive with the modules that need them.
